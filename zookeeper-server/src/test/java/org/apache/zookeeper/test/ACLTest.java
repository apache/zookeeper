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

package org.apache.zookeeper.test;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.InvalidACLException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.auth.IPAuthenticationProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ACLTest extends ZKTestCase implements Watcher {

    private static final Logger LOG = LoggerFactory.getLogger(ACLTest.class);
    private static final String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private volatile CountDownLatch startSignal;

    @Test
    public void testIPAuthenticationIsValidCIDR() throws Exception {
        IPAuthenticationProvider prov = new IPAuthenticationProvider();
        assertTrue(prov.isValid("127.0.0.1"), "testing no netmask");
        assertTrue(prov.isValid("127.0.0.1/32"), "testing single ip netmask");
        assertTrue(prov.isValid("127.0.0.1/0"), "testing lowest netmask possible");
        assertFalse(prov.isValid("127.0.0.1/33"), "testing netmask too high");
        assertFalse(prov.isValid("10.0.0.1/-1"), "testing netmask too low");
    }

    @Test
    public void testNettyIpAuthDefault() throws Exception {
        String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        ClientBase.setupTestEnv();
        File tmpDir = ClientBase.createTmpDir();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(1000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        try {
            LOG.info("starting up the zookeeper server .. waiting");
            assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up");
            ClientBase.createZKClient(HOSTPORT);
            for (ServerCnxn cnxn : f.getConnections()) {
                boolean foundID = false;
                for (Id id : cnxn.getAuthInfo()) {
                    if (id.getScheme().equals("ip")) {
                        foundID = true;
                        break;
                    }
                }
                assertTrue(foundID);
            }
        } finally {
            f.shutdown();
            zks.shutdown();
            assertTrue(ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server down");
            System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        }
    }

    @Test
    public void testDisconnectedAddAuth() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(1000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        try {
            LOG.info("starting up the zookeeper server .. waiting");
            assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up");
            ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);
            try {
                zk.addAuthInfo("digest", "pat:test".getBytes());
                zk.setACL("/", Ids.CREATOR_ALL_ACL, -1);
            } finally {
                zk.close();
            }
        } finally {
            f.shutdown();
            zks.shutdown();
            assertTrue(ClientBase.waitForServerDown(HOSTPORT, ClientBase.CONNECTION_TIMEOUT), "waiting for server down");
        }
    }

    /**
     * Verify that acl optimization of storing just
     * a few acls and there references in the data
     * node is actually working.
     */
    @Test
    public void testAcls() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(1000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        ZooKeeper zk;
        String path;
        try {
            LOG.info("starting up the zookeeper server .. waiting");
            assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up");
            zk = ClientBase.createZKClient(HOSTPORT);
            LOG.info("starting creating acls");
            for (int i = 0; i < 100; i++) {
                path = "/" + i;
                zk.create(path, path.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            int size = zks.getZKDatabase().getAclSize();
            assertTrue((2 == zks.getZKDatabase().getAclSize()), "size of the acl map ");
            for (int j = 100; j < 200; j++) {
                path = "/" + j;
                ACL acl = new ACL();
                acl.setPerms(0);
                Id id = new Id();
                id.setId("1.1.1." + j);
                id.setScheme("ip");
                acl.setId(id);
                List<ACL> list = new ArrayList<ACL>();
                list.add(acl);
                zk.create(path, path.getBytes(), list, CreateMode.PERSISTENT);
            }
            assertTrue((102 == zks.getZKDatabase().getAclSize()), "size of the acl map ");
        } finally {
            // now shutdown the server and restart it
            f.shutdown();
            zks.shutdown();
            assertTrue(ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server down");
        }

        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        f = ServerCnxnFactory.createFactory(PORT, -1);

        f.startup(zks);
        try {
            assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server up");
            zk = ClientBase.createZKClient(HOSTPORT);
            assertTrue((102 == zks.getZKDatabase().getAclSize()), "acl map ");
            for (int j = 200; j < 205; j++) {
                path = "/" + j;
                ACL acl = new ACL();
                acl.setPerms(0);
                Id id = new Id();
                id.setId("1.1.1." + j);
                id.setScheme("ip");
                acl.setId(id);
                ArrayList<ACL> list = new ArrayList<ACL>();
                list.add(acl);
                zk.create(path, path.getBytes(), list, CreateMode.PERSISTENT);
            }
            assertTrue((107 == zks.getZKDatabase().getAclSize()), "acl map ");

            zk.close();
        } finally {
            f.shutdown();
            zks.shutdown();
            assertTrue(ClientBase.waitForServerDown(HOSTPORT, ClientBase.CONNECTION_TIMEOUT), "waiting for server down");
        }

    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.Watcher#process(org.apache.zookeeper.WatcherEvent)
     */
    public void process(WatchedEvent event) {
        LOG.info("Event:{} {} {}", event.getState(), event.getType(), event.getPath());
        if (event.getState() == KeeperState.SyncConnected) {
            if (startSignal != null && startSignal.getCount() > 0) {
                LOG.info("startsignal.countDown()");
                startSignal.countDown();
            } else {
                LOG.warn("startsignal {}", startSignal);
            }
        }
    }

    @Test
    public void testNullACL() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);
        try {
            // case 1 : null ACL with create
            try {
                zk.create("/foo", "foo".getBytes(), null, CreateMode.PERSISTENT);
                fail("Expected InvalidACLException for null ACL parameter");
            } catch (InvalidACLException e) {
                // Expected. Do nothing
            }

            // case 2 : null ACL with other create API
            try {
                zk.create("/foo", "foo".getBytes(), null, CreateMode.PERSISTENT, null);
                fail("Expected InvalidACLException for null ACL parameter");
            } catch (InvalidACLException e) {
                // Expected. Do nothing
            }

            // case 3 : null ACL with setACL
            try {
                zk.setACL("/foo", null, 0);
                fail("Expected InvalidACLException for null ACL parameter");
            } catch (InvalidACLException e) {
                // Expected. Do nothing
            }
        } finally {
            zk.close();
            f.shutdown();
            zks.shutdown();
            assertTrue(ClientBase.waitForServerDown(HOSTPORT, ClientBase.CONNECTION_TIMEOUT), "waiting for server down");
        }
    }

    @Test
    public void testNullValueACL() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);
        try {

            List<ACL> acls = new ArrayList<ACL>();
            acls.add(null);

            // case 1 : null value in ACL list with create
            try {
                zk.create("/foo", "foo".getBytes(), acls, CreateMode.PERSISTENT);
                fail("Expected InvalidACLException for null value in ACL List");
            } catch (InvalidACLException e) {
                // Expected. Do nothing
            }

            // case 2 : null value in ACL list with other create API
            try {
                zk.create("/foo", "foo".getBytes(), acls, CreateMode.PERSISTENT, null);
                fail("Expected InvalidACLException for null value in ACL List");
            } catch (InvalidACLException e) {
                // Expected. Do nothing
            }

            // case 3 : null value in ACL list with setACL
            try {
                zk.setACL("/foo", acls, -1);
                fail("Expected InvalidACLException for null value in ACL List");
            } catch (InvalidACLException e) {
                // Expected. Do nothing
            }
        } finally {
            zk.close();
            f.shutdown();
            zks.shutdown();
            assertTrue(ClientBase.waitForServerDown(HOSTPORT, ClientBase.CONNECTION_TIMEOUT), "waiting for server down");
        }
    }

}
