/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.audit;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;
import org.apache.log4j.WriterAppender;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKUtil;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class ZKAuditLoggerTest extends QuorumPeerTestBase {
    private static final Logger LOG = Logger.getLogger(ZKAuditLoggerTest.class);
    private static int SERVER_COUNT = 3;
    private MainThread[] mt;
    private ZooKeeper zk;

    @Test
    public void testAuditLogs() throws Exception {
        System.setProperty(ZKAuditLogger.SYSPROP_AUDIT_ENABLE, "true");
        // setup the logger to capture all logs
        Layout layout = new SimpleLayout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setImmediateFlush(true);
        appender.setThreshold(Level.INFO);
        Logger zlogger = Logger.getLogger(ZKAuditLogger.class);
        zlogger.addAppender(appender);
        try {
            mt = startQuorum();
            CountdownWatcher watcher = new CountdownWatcher();
            zk = new ZooKeeper(
                    "127.0.0.1:" + mt[0].getQuorumPeer().getClientPort(),
                    ClientBase.CONNECTION_TIMEOUT, watcher);
            watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
            String expectedAuditLog = getStartLog();
            List<String> logs = readAuditLog(os, SERVER_COUNT);
            verifyLogs(expectedAuditLog, logs);

            String path = "/a";
            verifyCreateAuditLogs(os, path);
            verifySetDataAuditLogs(os, path);
            verifySetACLAuditLogs(os, path);
            verifyMultiOperationAuditLogs(os);
            verifyDeleteAuditLogs(os, path);
            verifyEphemralZNodeAuditLogs(os);

        } finally {
            zlogger.removeAppender(appender);
            os.close();
        }
    }

    private void verifyEphemralZNodeAuditLogs(ByteArrayOutputStream os)
            throws Exception {
        String ephemralPath = "/ephemral";
        CountdownWatcher watcher2 = new CountdownWatcher();
        ZooKeeper zk2 = new ZooKeeper(
                "127.0.0.1:" + mt[0].getQuorumPeer().getClientPort(),
                ClientBase.CONNECTION_TIMEOUT, watcher2);
        watcher2.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        zk2.create(ephemralPath, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);
        String session2 = "0x" + Long.toHexString(zk2.getSessionId());
        verifyLog(getAuditLog(AuditConstants.OP_CREATE, ephemralPath,
            AuditConstants.SUCCESS, null,
            CreateMode.EPHEMERAL.toString().toLowerCase(),
            session2), readAuditLog(os));
        zk2.close();
        waitForDeletion(zk, ephemralPath, 100);
        // verify that ephemeral node deletion on session close are captured
        // in audit log
        // Because these operations are done by ZooKeeper server itself,
        // there are no IP user is zkServer user, not any client user
        verifyLogs(getAuditLog(AuditConstants.OP_DEL_EZNODE_EXP, ephemralPath,
            AuditConstants.SUCCESS, null, null, session2,
            ZKAuditLogger.getZKUser(), null), readAuditLog(os, SERVER_COUNT));
    }

    private void verifyMultiOperationAuditLogs(ByteArrayOutputStream os)
            throws InterruptedException, KeeperException, IOException {
        List<Op> ops = new ArrayList<Op>();

        String multiop = "/b";
        Op create = Op.create(multiop, "".getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Op setData = Op.setData(multiop, "newData".getBytes(), -1);
        // check does nothing so it is audit logged
        Op check = Op.check(multiop, -1);
        Op delete = Op.delete(multiop, -1);

        String createMode = CreateMode.PERSISTENT.toString().toLowerCase();

        ops.add(create);
        ops.add(setData);
        ops.add(check);
        ops.add(delete);

        zk.multi(ops);
        List<String> multiOpLogs = readAuditLog(os, 3);
        // verify that each multi operation success is logged
        verifyLog(getAuditLog(AuditConstants.OP_CREATE, multiop,
            AuditConstants.SUCCESS, null, createMode),
                multiOpLogs.get(0));
        verifyLog(getAuditLog(AuditConstants.OP_SETDATA, multiop),
                multiOpLogs.get(1));
        verifyLog(getAuditLog(AuditConstants.OP_DELETE, multiop),
                multiOpLogs.get(2));

        ops = new ArrayList<Op>();
        ops.add(create);
        ops.add(create);
        try {
            zk.multi(ops);
        } catch (KeeperException exception) {
            Code code = exception.code();
            assertEquals(Code.NODEEXISTS, code);
        }

        // Verify that multi operation failure is logged, and there is no path
        // mentioned in the audit log
        verifyLog(getAuditLog(AuditConstants.OP_MULTI_OP, null,
                AuditConstants.FAILURE),
                readAuditLog(os));
    }

    private void verifyDeleteAuditLogs(ByteArrayOutputStream os, String path)
            throws InterruptedException, IOException, KeeperException {
        try {
            zk.delete(path, -100);
        } catch (KeeperException exception) {
            Code code = exception.code();
            assertEquals(Code.BADVERSION, code);
        }
        verifyLog(getAuditLog(AuditConstants.OP_DELETE, path,
                AuditConstants.FAILURE),
                readAuditLog(os));
        zk.delete(path, -1);
        verifyLog(getAuditLog(AuditConstants.OP_DELETE, path),
                readAuditLog(os));
    }

    private void verifySetACLAuditLogs(ByteArrayOutputStream os, String path)
            throws InterruptedException, IOException, KeeperException {
        ArrayList<ACL> openAclUnsafe = ZooDefs.Ids.OPEN_ACL_UNSAFE;
        try {
            zk.setACL(path, openAclUnsafe, -100);
        } catch (KeeperException exception) {
            Code code = exception.code();
            assertEquals(Code.BADVERSION, code);
        }
        verifyLog(
            getAuditLog(AuditConstants.OP_SETACL, path, AuditConstants.FAILURE,
                ZKUtil.aclToString(openAclUnsafe), null), readAuditLog(os));
        zk.setACL(path, openAclUnsafe, -1);
        verifyLog(
            getAuditLog(AuditConstants.OP_SETACL, path, AuditConstants.SUCCESS,
                ZKUtil.aclToString(openAclUnsafe), null), readAuditLog(os));
    }

    private void verifySetDataAuditLogs(ByteArrayOutputStream os, String path)
            throws InterruptedException, IOException, KeeperException {
        try {
            zk.setData(path, "newData".getBytes(), -100);
        } catch (KeeperException exception) {
            Code code = exception.code();
            assertEquals(Code.BADVERSION, code);
        }
        verifyLog(getAuditLog(AuditConstants.OP_SETDATA, path,
                AuditConstants.FAILURE),
                readAuditLog(os));
        zk.setData(path, "newdata".getBytes(), -1);
        verifyLog(getAuditLog(AuditConstants.OP_SETDATA, path),
                readAuditLog(os));
    }

    private void verifyCreateAuditLogs(ByteArrayOutputStream os, String path)
        throws KeeperException, InterruptedException, IOException {
        zk.create(path, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.PERSISTENT);
        // success log
        String createMode = CreateMode.PERSISTENT.toString().toLowerCase();
        verifyLog(
            getAuditLog(AuditConstants.OP_CREATE, path, AuditConstants.SUCCESS,
                null, createMode), readAuditLog(os));
        try {
            zk.create(path, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        } catch (KeeperException exception) {
            Code code = exception.code();
            assertEquals(Code.NODEEXISTS, code);
        }
        // Verify create operation log
        verifyLog(
            getAuditLog(AuditConstants.OP_CREATE, path, AuditConstants.FAILURE,
                null, createMode), readAuditLog(os));
    }

    private String getStartLog() {
        /**
         * <pre>
         * user=userName operation=ZooKeeperServer start  result=success
         * </pre>
         */
        return ZKAuditLogger
            .createLog(ZKAuditLogger.getZKUser(), AuditConstants.OP_START, null,
                null, null, null, null, AuditConstants.SUCCESS);
    }

    private String getAuditLog(String operation, String znode) {
        return getAuditLog(operation, znode, AuditConstants.SUCCESS);
    }

    private String getAuditLog(String operation, String znode, String status) {
        return getAuditLog(operation, znode, status, null, null);
    }

    private String getAuditLog(String operation, String znode, String status,
        String acl, String createMode) {
        String session = getSession();
        return getAuditLog(operation, znode, status, acl, createMode, session);
    }

    private String getAuditLog(String operation, String znode, String status,
        String acl, String createMode, String session) {
        String user = getUser();
        String ip = getIp();
        return getAuditLog(operation, znode, status, acl, createMode, session,
            user, ip);
    }

    private String getAuditLog(String operation, String znode, String status,
        String acl, String createMode, String session, String user, String ip) {

        String auditLog = ZKAuditLogger
            .createLog(user, operation, znode, acl, createMode, session, ip,
                status);
        LOG.info("expected audit log for operation '" + operation + "' is '"
            + auditLog + "'");
        return auditLog;
    }

    private String getSession() {
        return "0x" + Long.toHexString(zk.getSessionId());
    }

    private String getUser() {
        ServerCnxn next = getServerCnxn();
        Request request = new Request(next, -1, -1, -1, null,
                next.getAuthInfo());
        return request.getUsers();
    }

    private String getIp() {
        ServerCnxn next = getServerCnxn();
        InetSocketAddress remoteSocketAddress = next.getRemoteSocketAddress();
        InetAddress address = remoteSocketAddress.getAddress();
        return address.getHostAddress();
    }

    private ServerCnxn getServerCnxn() {
        Iterable<ServerCnxn> connections = mt[0].getQuorumPeer()
                .getActiveServer()
                .getServerCnxnFactory().getConnections();
        ServerCnxn next = connections.iterator().next();
        return next;
    }

    private void verifyLog(String expectedLog, String log) {
        String searchString = " - ";
        int logStartIndex = log.indexOf(searchString);
        String auditLog = log.substring(logStartIndex + searchString.length());
        assertEquals(expectedLog, auditLog);

    }

    private void verifyLogs(String expectedLog, List<String> logs) {
        for (String log : logs) {
            verifyLog(expectedLog, log);
        }
    }

    private String readAuditLog(ByteArrayOutputStream os) throws IOException {
        return readAuditLog(os, 1).get(0);
    }

    private List<String> readAuditLog(ByteArrayOutputStream os,
            int numberOfLogEntry)
                    throws IOException {
        return readAuditLog(os, numberOfLogEntry, false);
    }

    private List<String> readAuditLog(ByteArrayOutputStream os,
            int numberOfLogEntry,
            boolean skipEphemralDeletion) throws IOException {
        List<String> logs = new ArrayList<String>();
        LineNumberReader r = new LineNumberReader(
                new StringReader(os.toString()));
        String line = "";
        while ((line = r.readLine()) != null) {
            if (skipEphemralDeletion
                    && line.contains(AuditConstants.OP_DEL_EZNODE_EXP)) {
                continue;
            }
            logs.add(line);
        }
        os.reset();
        assertEquals(
                "Expected number of log entries are not generated. Logs are "
                        + logs,
                numberOfLogEntry, logs.size());
        return logs;

    }

    private MainThread[] startQuorum() throws IOException {
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique()
                    + ":"
                    + PortAssignment.unique() + ":participant;127.0.0.1:"
                    + clientPorts[i];
            sb.append(server + "\n");

        }
        String currentQuorumCfgSection = sb.toString();
        MainThread mt[] = new MainThread[SERVER_COUNT];

        // start all the servers
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection,
                    false);
            mt[i].start();
        }

        // ensure all servers started
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
        }
        return mt;
    }

    private void waitForDeletion(ZooKeeper zooKeeper, String path, long timeout)
            throws Exception {
        long elapsedTime = 0;
        long waitInterval = 10;
        Stat exists = zooKeeper.exists(path, false);
        while (exists != null && elapsedTime < timeout) {
            try {
                Thread.sleep(waitInterval);
            } catch (InterruptedException e) {
                Assert.fail("CurrentEpoch update failed");
            }
            elapsedTime = elapsedTime + waitInterval;
            exists = zooKeeper.exists(path, false);
        }
        Assert.assertNull("Node " + path + " not deleted in " + timeout + " ms",
                exists);
    }

    @After
    public void tearDown() {
        System.clearProperty(ZKAuditLogger.SYSPROP_AUDIT_ENABLE);
        System.clearProperty("zookeeper.admin.enableServer");
        for (int i = 0; i < SERVER_COUNT; i++) {
            try {
                if (mt[i] != null) {
                    mt[i].shutdown();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

}
