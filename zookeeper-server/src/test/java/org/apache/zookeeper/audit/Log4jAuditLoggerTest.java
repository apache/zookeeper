/*
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
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


public class Log4jAuditLoggerTest extends QuorumPeerTestBase {
    private static final Logger LOG = Logger.getLogger(Log4jAuditLoggerTest.class);
    private static int SERVER_COUNT = 3;
    private static MainThread[] mt;
    private static ZooKeeper zk;
    private static Logger zlogger;
    private static WriterAppender appender;
    private static ByteArrayOutputStream os;

    @BeforeAll
    public static void setUpBeforeClass() throws Exception {
        System.setProperty(ZKAuditProvider.AUDIT_ENABLE, "true");
        // setup the logger to capture all logs
        Layout layout = new SimpleLayout();
        os = new ByteArrayOutputStream();
        appender = new WriterAppender(layout, os);
        appender.setImmediateFlush(true);
        appender.setThreshold(Level.INFO);
        zlogger = Logger.getLogger(Log4jAuditLogger.class);
        zlogger.addAppender(appender);
        mt = startQuorum();
        zk = ClientBase.createZKClient("127.0.0.1:" + mt[0].getQuorumPeer().getClientPort());
        //Verify start audit log here itself
        String expectedAuditLog = getStartLog();
        List<String> logs = readAuditLog(os, SERVER_COUNT);
        verifyLogs(expectedAuditLog, logs);
    }

    @BeforeEach
    public void setUp() {
        os.reset();
    }

    @Test
    public void testCreateAuditLogs()
            throws KeeperException, InterruptedException, IOException {
        String path = "/createPath";
        zk.create(path, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        // success log
        String createMode = CreateMode.PERSISTENT.toString().toLowerCase();
        verifyLog(
                getAuditLog(AuditConstants.OP_CREATE, path, Result.SUCCESS,
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
                getAuditLog(AuditConstants.OP_CREATE, path, Result.FAILURE,
                        null, createMode), readAuditLog(os));
    }

    @Test
    public void testDeleteAuditLogs()
            throws InterruptedException, IOException, KeeperException {
        String path = "/deletePath";
        zk.create(path, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        os.reset();
        try {
            zk.delete(path, -100);
        } catch (KeeperException exception) {
            Code code = exception.code();
            assertEquals(Code.BADVERSION, code);
        }
        verifyLog(getAuditLog(AuditConstants.OP_DELETE, path,
                Result.FAILURE),
                readAuditLog(os));
        zk.delete(path, -1);
        verifyLog(getAuditLog(AuditConstants.OP_DELETE, path),
                readAuditLog(os));
    }

    @Test
    public void testSetDataAuditLogs()
            throws InterruptedException, IOException, KeeperException {
        String path = "/setDataPath";
        zk.create(path, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        os.reset();
        try {
            zk.setData(path, "newData".getBytes(), -100);
        } catch (KeeperException exception) {
            Code code = exception.code();
            assertEquals(Code.BADVERSION, code);
        }
        verifyLog(getAuditLog(AuditConstants.OP_SETDATA, path,
                Result.FAILURE),
                readAuditLog(os));
        zk.setData(path, "newdata".getBytes(), -1);
        verifyLog(getAuditLog(AuditConstants.OP_SETDATA, path),
                readAuditLog(os));
    }

    @Test
    public void testSetACLAuditLogs()
            throws InterruptedException, IOException, KeeperException {
        ArrayList<ACL> openAclUnsafe = ZooDefs.Ids.OPEN_ACL_UNSAFE;
        String path = "/aclPath";
        zk.create(path, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        os.reset();
        try {
            zk.setACL(path, openAclUnsafe, -100);
        } catch (KeeperException exception) {
            Code code = exception.code();
            assertEquals(Code.BADVERSION, code);
        }
        verifyLog(
                getAuditLog(AuditConstants.OP_SETACL, path, Result.FAILURE,
                        ZKUtil.aclToString(openAclUnsafe), null), readAuditLog(os));
        zk.setACL(path, openAclUnsafe, -1);
        verifyLog(
                getAuditLog(AuditConstants.OP_SETACL, path, Result.SUCCESS,
                        ZKUtil.aclToString(openAclUnsafe), null), readAuditLog(os));
    }

    @Test
    public void testMultiOperationAuditLogs()
            throws InterruptedException, KeeperException, IOException {
        List<Op> ops = new ArrayList<>();

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
                Result.SUCCESS, null, createMode),
                multiOpLogs.get(0));
        verifyLog(getAuditLog(AuditConstants.OP_SETDATA, multiop),
                multiOpLogs.get(1));
        verifyLog(getAuditLog(AuditConstants.OP_DELETE, multiop),
                multiOpLogs.get(2));

        ops = new ArrayList<>();
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
                Result.FAILURE),
                readAuditLog(os));
    }

    @Test
    public void testEphemralZNodeAuditLogs()
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
                Result.SUCCESS, null,
                CreateMode.EPHEMERAL.toString().toLowerCase(),
                session2), readAuditLog(os));
        zk2.close();
        waitForDeletion(zk, ephemralPath);
        // verify that ephemeral node deletion on session close are captured
        // in audit log
        // Because these operations are done by ZooKeeper server itself,
        // there are no IP user is zkServer user, not any client user
        verifyLogs(getAuditLog(AuditConstants.OP_DEL_EZNODE_EXP, ephemralPath,
                Result.SUCCESS, null, null, session2,
                ZKAuditProvider.getZKUser(), null), readAuditLog(os, SERVER_COUNT));
    }


    private static String getStartLog() {
        // user=userName operation=ZooKeeperServer start  result=success
        AuditEvent logEvent = ZKAuditProvider.createLogEvent(ZKAuditProvider.getZKUser(),
                AuditConstants.OP_START, Result.SUCCESS);
        return logEvent.toString();
    }

    private String getAuditLog(String operation, String znode) {
        return getAuditLog(operation, znode, Result.SUCCESS);
    }

    private String getAuditLog(String operation, String znode, Result result) {
        return getAuditLog(operation, znode, result, null, null);
    }

    private String getAuditLog(String operation, String znode, Result result,
                               String acl, String createMode) {
        String session = getSession();
        return getAuditLog(operation, znode, result, acl, createMode, session);
    }

    private String getAuditLog(String operation, String znode, Result result,
                               String acl, String createMode, String session) {
        String user = getUser();
        String ip = getIp();
        return getAuditLog(operation, znode, result, acl, createMode, session,
                user, ip);
    }

    private String getAuditLog(String operation, String znode, Result result,
                               String acl, String createMode, String session, String user, String ip) {
        AuditEvent logEvent = ZKAuditProvider.createLogEvent(user, operation, znode, acl, createMode, session, ip,
                result);
        String auditLog = logEvent.toString();
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
        return connections.iterator().next();
    }

    private static void verifyLog(String expectedLog, String log) {
        String searchString = " - ";
        int logStartIndex = log.indexOf(searchString);
        String auditLog = log.substring(logStartIndex + searchString.length());
        assertEquals(expectedLog, auditLog);

    }

    private static void verifyLogs(String expectedLog, List<String> logs) {
        for (String log : logs) {
            verifyLog(expectedLog, log);
        }
    }

    private String readAuditLog(ByteArrayOutputStream os) throws IOException {
        return readAuditLog(os, 1).get(0);
    }

    private static List<String> readAuditLog(ByteArrayOutputStream os,
                                             int numberOfLogEntry)
            throws IOException {
        return readAuditLog(os, numberOfLogEntry, false);
    }

    private static List<String> readAuditLog(ByteArrayOutputStream os,
                                             int numberOfLogEntry,
                                             boolean skipEphemralDeletion) throws IOException {
        List<String> logs = new ArrayList<>();
        LineNumberReader r = new LineNumberReader(
                new StringReader(os.toString()));
        String line;
        while ((line = r.readLine()) != null) {
            if (skipEphemralDeletion
                    && line.contains(AuditConstants.OP_DEL_EZNODE_EXP)) {
                continue;
            }
            logs.add(line);
        }
        os.reset();
        assertEquals(numberOfLogEntry, logs.size(),
                "Expected number of log entries are not generated. Logs are "
                        + logs);
        return logs;

    }

    private static MainThread[] startQuorum() throws IOException {
        final int[] clientPorts = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        sb.append("4lw.commands.whitelist=*");
        sb.append("\n");
        String server;

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique()
                    + ":"
                    + PortAssignment.unique() + ":participant;127.0.0.1:"
                    + clientPorts[i];
            sb.append(server);
            sb.append("\n");
        }
        String currentQuorumCfgSection = sb.toString();
        MainThread[] mt = new MainThread[SERVER_COUNT];

        // start all the servers
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection,
                    false);
            mt[i].start();
        }

        // ensure all servers started
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assertions.assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                    CONNECTION_TIMEOUT), "waiting for server " + i + " being up");
        }
        return mt;
    }

    private void waitForDeletion(ZooKeeper zooKeeper, String path)
            throws Exception {
        long elapsedTime = 0;
        long waitInterval = 10;
        int timeout = 100;
        Stat exists = zooKeeper.exists(path, false);
        while (exists != null && elapsedTime < timeout) {
            try {
                Thread.sleep(waitInterval);
            } catch (InterruptedException e) {
                Assertions.fail("CurrentEpoch update failed");
            }
            elapsedTime = elapsedTime + waitInterval;
            exists = zooKeeper.exists(path, false);
        }
        Assertions.assertNull(exists, "Node " + path + " not deleted in " + timeout + " ms");
    }

    @AfterAll
    public static void tearDownAfterClass() {
        System.clearProperty(ZKAuditProvider.AUDIT_ENABLE);
        for (int i = 0; i < SERVER_COUNT; i++) {
            try {
                if (mt[i] != null) {
                    mt[i].shutdown();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        try {
            zlogger.removeAppender(appender);
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
