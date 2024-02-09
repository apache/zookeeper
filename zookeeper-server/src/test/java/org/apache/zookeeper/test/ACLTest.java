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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.InvalidACLException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.Perms;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.auth.IPAuthenticationProvider;
import org.apache.zookeeper.server.embedded.ZooKeeperServerEmbedded;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    public void testIPAuthenticationIsValidIpv6CIDR() throws Exception {
        IPAuthenticationProvider prov = new IPAuthenticationProvider();
        assertTrue(prov.isValid("2001:0db8:85a3:0000:0000:8a2e:0370:7334"), "full address no netmask");
        assertTrue(prov.isValid("2001:db8:85a3::8a2e:370:7334"), "compressed zeros");
        assertTrue(prov.isValid("::1"), "loopback with compression");
        assertTrue(prov.isValid("1::"), "Start with compression");
        assertTrue(prov.isValid("2001:db8::/4"), "end with compression");
        assertTrue(prov.isValid("0:0:0:0:0:0:0::/8"), "all zeros");
        assertTrue(prov.isValid("2001:db8:85a3:0:0:0:0::/32"), "Explicit zeros");
        assertTrue(prov.isValid("1234:5678:9abc:def0:1234:5678:9abc:def0"), "max hex value");
        assertFalse(prov.isValid("2001:db8:85a3:0000:0000:8a2e:0370:7334:extra"), "too many address segments");
        assertFalse(prov.isValid("2001:db8:85a3:0000:0000:8a2e:0370"), "too few address segments");
        assertFalse(prov.isValid("2001:db8:85a3::8a2e::0370:7334"), "multiple '::' not valid");
        assertFalse(prov.isValid("2001:db8:85a3:G::8a2e:0370:7334"), "Invalid hex character");
        assertFalse(prov.isValid(""), "empty string");
        assertFalse(prov.isValid("2001:db8:85a3:0:0:0:0:1:2"), "too many segments post compression");
        assertFalse(prov.isValid("2001:db8:85a3::8a2e:0370:7334:"), "trailing colon");
        assertFalse(prov.isValid(":2001:db8:85a3::8a2e:0370:7334"), "Leading colon");
        assertFalse(prov.isValid("::FFFF:192.168.1.1"), "IPv4-mapped");
        assertTrue(prov.isValid("2001:db8:1234::/64"), "IPv6 address for multiple clients");
    }

    @Test
    public void testIPAuthenticationIsValidIpv6Mask() throws Exception {
        IPAuthenticationProvider prov = new IPAuthenticationProvider();
        assertTrue(prov.matches("2001:db8:1234::", "2001:db8:1234::/64"));
        assertTrue(prov.matches("2001:0db8:85a3:0000:0000:8a2e:0370:7334", "2001:0db8:85a3:0000:0000:8a2e:0370::/2"));
        assertFalse(prov.matches("22001:db8:85a3:0:0:0:0::0", "2001:db8:85a3:0:0:0:0::/32"));
        assertFalse(prov.matches("2001:db8::/4", "2001:db8::/4"));
    }

    @Test
    public void testNettyIpAuthDefault(@TempDir File tmpDir) throws Exception {
        String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        ClientBase.setupTestEnv();
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
    public void testAuthWithIPV6Server(@TempDir File tmpDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("clientPortAddress", "::1");
        properties.setProperty("clientPort", "0");

        ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded.builder()
            .baseDir(tmpDir.toPath())
            .configuration(properties)
            .build();
        server.start();

        String connectionString = server.getConnectionString();
        String port = connectionString.substring(connectionString.lastIndexOf(':') + 1);

        String hostport = String.format("[::1]:%s", port);
        assertTrue(ClientBase.waitForServerUp(hostport, CONNECTION_TIMEOUT), "waiting for server being up");

        // given: ipv6 client
        ZooKeeper zk = ClientBase.createZKClient(hostport);

        // when: invalid ipv6 network acl
        // then: InvalidACL
        assertThrows(KeeperException.InvalidACLException.class, () ->
            zk.create("/invalid-ipv6-network-acl", null, Collections.singletonList(new ACL(Perms.ALL, new Id("ip", "::1/256"))), CreateMode.PERSISTENT));

        // given: ipv4 network acl
        zk.create("/unmatched-ipv4-network-acl", null, Collections.singletonList(new ACL(Perms.ALL, new Id("ip", "127.0.0.1/16"))), CreateMode.PERSISTENT);
        // when: access with v6 ip
        // then: NoAuth
        assertThrows(KeeperException.NoAuthException.class, () -> zk.setData("/unmatched-ipv4-network-acl", null, -1));

        // given: prefix matched ipv4 acl
        zk.create("/prefix-matched-ipv4-acl", null, Collections.singletonList(new ACL(Perms.ALL, new Id("ip", "0.0.0.1/16"))), CreateMode.PERSISTENT);
        // when: access with v6 ip
        // then: NoAuth
        assertThrows(KeeperException.NoAuthException.class, () -> zk.setData("/prefix-matched-ipv4-acl", null, -1));

        // given: ipv6 with network acl
        zk.create("/ipv6-network-acl", null, Collections.singletonList(new ACL(Perms.ALL, new Id("ip", "::1/64"))), CreateMode.PERSISTENT);
        // when: access with valid ip
        // then: ok
        zk.setData("/ipv6-network-acl", null, -1);

        // given: ipv6 acl
        zk.create("/ipv6-acl", null, Collections.singletonList(new ACL(Perms.ALL, new Id("ip", "::1"))), CreateMode.PERSISTENT);
        // when: access with valid ip
        // then: ok
        zk.setData("/ipv6-acl", null, -1);

        // given: mismatched ipv6 with network acl
        zk.create("/mismatched-ipv6-network-acl", null, Collections.singletonList(new ACL(Perms.ALL, new Id("ip", "0000:0001::/32"))), CreateMode.PERSISTENT);
        // when: access with invalid ip
        // then: NoAuth
        assertThrows(KeeperException.NoAuthException.class, () -> zk.setData("/mismatched-ipv6-network-acl", null, -1));

        // given: mismatched ipv6 acl
        zk.create("/mismatched-ipv6-acl", null, Collections.singletonList(new ACL(Perms.ALL, new Id("ip", "::2"))), CreateMode.PERSISTENT);
        // when: access with invalid ip
        // then: NoAuth
        assertThrows(KeeperException.NoAuthException.class, () -> zk.setData("/mismatched-ipv6-acl", null, -1));

        server.close();
    }

    @Test
    public void testAuthWithIPV4Server(@TempDir File tmpDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("clientPortAddress", "127.0.0.1");
        properties.setProperty("clientPort", "0");

        ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded.builder()
            .baseDir(tmpDir.toPath())
            .configuration(properties)
            .build();
        server.start();

        assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), CONNECTION_TIMEOUT), "waiting for server being up");

        // given: ipv4 client
        ZooKeeper zk = ClientBase.createZKClient(server.getConnectionString());

        // when: invalid ipv4 network acl
        // then: InvalidACL
        assertThrows(KeeperException.InvalidACLException.class, () ->
            zk.create("/invalid-ipv4-network-acl", new byte[]{}, Arrays.asList(new ACL(Perms.ALL, new Id("ip", "127.0.0.1/64"))), CreateMode.PERSISTENT));

        // given: ipv6 acl
        zk.create("/mismatched-ipv6-acl", new byte[]{}, Arrays.asList(new ACL(Perms.ALL, new Id("ip", "::1"))), CreateMode.PERSISTENT);
        // when: access with v4 ip
        // then: NoAuth
        assertThrows(KeeperException.NoAuthException.class, () -> zk.setData("/mismatched-ipv6-acl", null, -1));

        // given: prefix matched ipv6 network acl
        zk.create("/prefix-matched-ipv6-network-acl", new byte[]{}, Arrays.asList(new ACL(Perms.ALL, new Id("ip", "7f::/16"))), CreateMode.PERSISTENT);
        // when: access with v4 ip
        // then: NoAuth
        assertThrows(KeeperException.NoAuthException.class, () -> zk.setData("/prefix-matched-ipv6-network-acl", null, -1));

        // given: ipv4 with network acl
        zk.create("/matched-ipv4-network-acl", new byte[]{}, Arrays.asList(new ACL(Perms.ALL, new Id("ip", "127.0.0.1/16"))), CreateMode.PERSISTENT);
        // when: access with valid ip
        // then: ok
        zk.setData("/matched-ipv4-network-acl", null, -1);

        // given: matched ipv4 acl
        zk.create("/matched-ipv4-acl", new byte[]{}, Arrays.asList(new ACL(Perms.ALL, new Id("ip", "127.0.0.1"))), CreateMode.PERSISTENT);
        // when: access with valid ip
        // then: ok
        zk.setData("/matched-ipv4-acl", null, -1);

        // given: mismatched ipv4 network acl
        zk.create("/mismatched-ipv4-network-acl", new byte[]{}, Arrays.asList(new ACL(Perms.ALL, new Id("ip", "192.168.0.2/16"))), CreateMode.PERSISTENT);
        // when: access with invalid ip
        // then: NoAuth
        assertThrows(KeeperException.NoAuthException.class, () -> zk.setData("/mismatched-ipv4-network-acl", null, -1));

        // given: mismatched ipv4 acl
        zk.create("/mismatched-ipv4-acl", new byte[]{}, Arrays.asList(new ACL(Perms.ALL, new Id("ip", "127.0.0.2"))), CreateMode.PERSISTENT);
        // when: access with invalid ip
        // then: NoAuth
        assertThrows(KeeperException.NoAuthException.class, () -> zk.setData("/mismatched-ipv4-acl", null, -1));

        server.close();
    }

    @Test
    public void testDisconnectedAddAuth(@TempDir File tmpDir) throws Exception {
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
    public void testAcls(@TempDir File tmpDir) throws Exception {
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
                List<ACL> list = new ArrayList<>();
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
                ArrayList<ACL> list = new ArrayList<>();
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
    public void testNullACL(@TempDir File tmpDir) throws Exception {
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
    public void testNullValueACL(@TempDir File tmpDir) throws Exception {
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);
        try {

            List<ACL> acls = new ArrayList<>();
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

    @Test
    public void testExistACLCheck(@TempDir File tmpDir) throws Exception {
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        String path = "/testExistACLCheck";
        String data = "/testExistACLCheck-data";
        try {
            LOG.info("starting up the zookeeper server .. waiting");
            assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up");
            ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);
            try {
                Stat stat = zk.exists(path, false);
                assertNull(stat);
                zk.create(path, data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                stat = zk.exists(path, false);
                assertNotNull(stat);
                assertEquals(data.length(), stat.getDataLength());

                zk.delete(path, -1);
                ArrayList<ACL> acls = new ArrayList<>();
                acls.add(new ACL(ZooDefs.Perms.WRITE, Ids.ANYONE_ID_UNSAFE));
                zk.create(path, data.getBytes(), acls, CreateMode.PERSISTENT);
                try {
                    stat = zk.exists(path, false);
                    fail("exists should throw NoAuthException when don't have read permission");
                } catch (KeeperException.NoAuthException e) {
                    //expected
                }

                zk.delete(path, -1);
                acls = new ArrayList<>();
                acls.add(new ACL(ZooDefs.Perms.READ, Ids.ANYONE_ID_UNSAFE));
                zk.create(path, data.getBytes(), acls, CreateMode.PERSISTENT);
                stat = zk.exists(path, false);
                assertNotNull(stat);
                assertEquals(data.length(), stat.getDataLength());
            } finally {
                zk.close();
            }
        } finally {
            f.shutdown();
            zks.shutdown();
            assertTrue(ClientBase.waitForServerDown(HOSTPORT, ClientBase.CONNECTION_TIMEOUT), "waiting for server down");
        }
    }

    @Test
    public void testExistACLCheckAtRootPath(@TempDir File tmpDir) throws Exception {
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        try {
            LOG.info("starting up the zookeeper server .. waiting");
            assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up");
            ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);
            try {
                String data = "/testExistACLCheckAtRootPath-data";
                zk.create("/a", data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                ArrayList<ACL> acls = new ArrayList<>();
                acls.add(new ACL(0, Ids.ANYONE_ID_UNSAFE));
                zk.setACL("/", acls, -1);

                Stat stat = zk.exists("/a", false);
                assertNotNull(stat);
                assertEquals(data.length(), stat.getDataLength());
                try {
                    stat = zk.exists("/", false);
                    fail("exists should throw NoAuthException when removing root path's ACL permission");
                } catch (KeeperException.NoAuthException e) {
                    //expected
                }
            } finally {
                zk.close();
            }
        } finally {
            f.shutdown();
            zks.shutdown();
            assertTrue(ClientBase.waitForServerDown(HOSTPORT, ClientBase.CONNECTION_TIMEOUT), "waiting for server down");
        }
    }
}
