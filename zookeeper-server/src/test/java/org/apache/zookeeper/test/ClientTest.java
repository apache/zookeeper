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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.InvalidACLException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.Perms;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.ExistsRequest;
import org.apache.zookeeper.proto.ExistsResponse;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.server.PrepRequestProcessor;
import org.apache.zookeeper.server.util.OSMXBean;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientTest extends ClientBase {

    protected static final Logger LOG = LoggerFactory.getLogger(ClientTest.class);
    private boolean skipACL = System.getProperty("zookeeper.skipACL", "no").equals("yes");

    /** Verify that pings are sent, keeping the "idle" client alive */
    @Test
    public void testPing() throws Exception {
        ZooKeeper zkIdle = null;
        ZooKeeper zkWatchCreator = null;
        try {
            CountdownWatcher watcher = new CountdownWatcher();
            zkIdle = createClient(watcher, hostPort, 10000);

            zkWatchCreator = createClient();

            for (int i = 0; i < 10; i++) {
                zkWatchCreator.create("/" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            for (int i = 0; i < 10; i++) {
                zkIdle.exists("/" + i, true);
            }
            for (int i = 0; i < 10; i++) {
                Thread.sleep(1000);
                zkWatchCreator.delete("/" + i, -1);
            }
            // The bug will manifest itself here because zkIdle will expire
            zkIdle.exists("/0", false);
        } finally {
            if (zkIdle != null) {
                zkIdle.close();
            }
            if (zkWatchCreator != null) {
                zkWatchCreator.close();
            }
        }
    }

    @Test
    public void testClientwithoutWatcherObj() throws IOException, InterruptedException, KeeperException {
        performClientTest(false);
    }

    @Test
    public void testClientWithWatcherObj() throws IOException, InterruptedException, KeeperException {
        performClientTest(true);
    }

    /** Exercise the testable functions, verify tostring, etc... */
    @Test
    public void testTestability() throws Exception {
        TestableZooKeeper zk = createClient();
        try {
            LOG.info("{}", zk.testableLocalSocketAddress());
            LOG.info("{}", zk.testableRemoteSocketAddress());
            LOG.info("{}", zk.toString());
        } finally {
            zk.close(CONNECTION_TIMEOUT);
            LOG.info("{}", zk.testableLocalSocketAddress());
            LOG.info("{}", zk.testableRemoteSocketAddress());
            LOG.info("{}", zk.toString());
        }
    }

    @Test
    public void testACLs() throws Exception {
        ZooKeeper zk = null;
        try {
            zk = createClient();

            try {
                zk.create("/acltest", new byte[0], Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
                fail("Should have received an invalid acl error");
            } catch (InvalidACLException e) {
                LOG.info("Test successful, invalid acl received : {}", e.getMessage());
            }

            try {
                ArrayList<ACL> testACL = new ArrayList<>();
                testACL.add(new ACL(Perms.ALL | Perms.ADMIN, Ids.AUTH_IDS));
                testACL.add(new ACL(Perms.ALL | Perms.ADMIN, new Id("ip", "127.0.0.1/8")));
                zk.create("/acltest", new byte[0], testACL, CreateMode.PERSISTENT);
                fail("Should have received an invalid acl error");
            } catch (InvalidACLException e) {
                LOG.info("Test successful, invalid acl received : {}", e.getMessage());
            }

            try {
                ArrayList<ACL> testACL = new ArrayList<>();
                testACL.add(new ACL(Perms.ALL | Perms.ADMIN, new Id()));
                zk.create("/nullidtest", new byte[0], testACL, CreateMode.PERSISTENT);
                fail("Should have received an invalid acl error");
            } catch (InvalidACLException e) {
                LOG.info("Test successful, invalid acl received : {}", e.getMessage());
            }

            zk.addAuthInfo("digest", "ben:passwd".getBytes());
            ArrayList<ACL> testACL = new ArrayList<>();
            testACL.add(new ACL(Perms.ALL, new Id("auth", "")));
            testACL.add(new ACL(Perms.WRITE, new Id("ip", "127.0.0.1")));
            zk.create("/acltest", new byte[0], testACL, CreateMode.PERSISTENT);
            zk.close();
            zk = createClient();
            zk.addAuthInfo("digest", "ben:passwd2".getBytes());
            if (skipACL) {
                try {
                    zk.getData("/acltest", false, null);
                } catch (KeeperException e) {
                    fail("Badauth reads should succeed with skipACL.");
                }
            } else {
                try {
                    zk.getData("/acltest", false, null);
                    fail("Should have received a permission error");
                } catch (KeeperException e) {
                    assertEquals(Code.NOAUTH, e.code());
                }
            }
            zk.addAuthInfo("digest", "ben:passwd".getBytes());
            zk.getData("/acltest", false, null);
            zk.setACL("/acltest", Ids.OPEN_ACL_UNSAFE, -1);
            zk.close();
            zk = createClient();
            zk.getData("/acltest", false, null);
            List<ACL> acls = zk.getACL("/acltest", new Stat());
            assertEquals(1, acls.size());
            assertEquals(Ids.OPEN_ACL_UNSAFE, acls);

            // The stat parameter should be optional.
            acls = zk.getACL("/acltest", null);
            assertEquals(1, acls.size());
            assertEquals(Ids.OPEN_ACL_UNSAFE, acls);

            zk.close();
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    @Test
    public void testNullAuthId() throws Exception {
        ZooKeeper zk = null;
        try {
            zk = createClient();
            zk.addAuthInfo("digest", "ben:passwd".getBytes());
            ArrayList<ACL> testACL = new ArrayList<>();
            testACL.add(new ACL(Perms.ALL, new Id("auth", null)));
            zk.create("/acltest", new byte[0], testACL, CreateMode.PERSISTENT);
            zk.close();
            zk = createClient();
            zk.addAuthInfo("digest", "ben:passwd2".getBytes());
            if (skipACL) {
                try {
                    zk.getData("/acltest", false, null);
                } catch (KeeperException e) {
                    fail("Badauth reads should succeed with skipACL.");
                }
            } else {
                try {
                    zk.getData("/acltest", false, null);
                    fail("Should have received a permission error");
                } catch (KeeperException e) {
                    assertEquals(Code.NOAUTH, e.code());
                }
            }
            zk.addAuthInfo("digest", "ben:passwd".getBytes());
            zk.getData("/acltest", false, null);
            zk.setACL("/acltest", Ids.OPEN_ACL_UNSAFE, -1);
            zk.close();
            zk = createClient();
            zk.getData("/acltest", false, null);
            List<ACL> acls = zk.getACL("/acltest", new Stat());
            assertEquals(1, acls.size());
            assertEquals(Ids.OPEN_ACL_UNSAFE, acls);
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    private class MyWatcher extends CountdownWatcher {

        LinkedBlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();

        public void process(WatchedEvent event) {
            super.process(event);
            if (event.getType() != EventType.None) {
                try {
                    events.put(event);
                } catch (InterruptedException e) {
                    LOG.warn("ignoring interrupt during event.put");
                }
            }
        }

    }

    /**
     * Register multiple watchers and verify that they all get notified and
     * in the right order.
     */
    @Test
    public void testMultipleWatcherObjs() throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = createClient(new CountdownWatcher(), hostPort);
        try {
            MyWatcher[] watchers = new MyWatcher[100];
            MyWatcher[] watchers2 = new MyWatcher[watchers.length];
            for (int i = 0; i < watchers.length; i++) {
                watchers[i] = new MyWatcher();
                watchers2[i] = new MyWatcher();
                zk.create("/foo-" + i, ("foodata" + i).getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            Stat stat = new Stat();

            //
            // test get/exists with single set of watchers
            //   get all, then exists all
            //
            for (int i = 0; i < watchers.length; i++) {
                assertNotNull(zk.getData("/foo-" + i, watchers[i], stat));
            }
            for (int i = 0; i < watchers.length; i++) {
                assertNotNull(zk.exists("/foo-" + i, watchers[i]));
            }
            // trigger the watches
            for (int i = 0; i < watchers.length; i++) {
                zk.setData("/foo-" + i, ("foodata2-" + i).getBytes(), -1);
                zk.setData("/foo-" + i, ("foodata3-" + i).getBytes(), -1);
            }
            for (int i = 0; i < watchers.length; i++) {
                WatchedEvent event = watchers[i].events.poll(10, TimeUnit.SECONDS);
                assertEquals("/foo-" + i, event.getPath());
                assertEquals(EventType.NodeDataChanged, event.getType());
                assertEquals(KeeperState.SyncConnected, event.getState());

                // small chance that an unexpected message was delivered
                //  after this check, but we would catch that next time
                //  we check events
                assertEquals(0, watchers[i].events.size());
            }

            //
            // test get/exists with single set of watchers
            //  get/exists together
            //
            for (int i = 0; i < watchers.length; i++) {
                assertNotNull(zk.getData("/foo-" + i, watchers[i], stat));
                assertNotNull(zk.exists("/foo-" + i, watchers[i]));
            }
            // trigger the watches
            for (int i = 0; i < watchers.length; i++) {
                zk.setData("/foo-" + i, ("foodata4-" + i).getBytes(), -1);
                zk.setData("/foo-" + i, ("foodata5-" + i).getBytes(), -1);
            }
            for (int i = 0; i < watchers.length; i++) {
                WatchedEvent event = watchers[i].events.poll(10, TimeUnit.SECONDS);
                assertEquals("/foo-" + i, event.getPath());
                assertEquals(EventType.NodeDataChanged, event.getType());
                assertEquals(KeeperState.SyncConnected, event.getState());

                // small chance that an unexpected message was delivered
                //  after this check, but we would catch that next time
                //  we check events
                assertEquals(0, watchers[i].events.size());
            }

            //
            // test get/exists with two sets of watchers
            //
            for (int i = 0; i < watchers.length; i++) {
                assertNotNull(zk.getData("/foo-" + i, watchers[i], stat));
                assertNotNull(zk.exists("/foo-" + i, watchers2[i]));
            }
            // trigger the watches
            for (int i = 0; i < watchers.length; i++) {
                zk.setData("/foo-" + i, ("foodata6-" + i).getBytes(), -1);
                zk.setData("/foo-" + i, ("foodata7-" + i).getBytes(), -1);
            }
            for (int i = 0; i < watchers.length; i++) {
                WatchedEvent event = watchers[i].events.poll(10, TimeUnit.SECONDS);
                assertEquals("/foo-" + i, event.getPath());
                assertEquals(EventType.NodeDataChanged, event.getType());
                assertEquals(KeeperState.SyncConnected, event.getState());

                // small chance that an unexpected message was delivered
                //  after this check, but we would catch that next time
                //  we check events
                assertEquals(0, watchers[i].events.size());

                // watchers2
                WatchedEvent event2 = watchers2[i].events.poll(10, TimeUnit.SECONDS);
                assertEquals("/foo-" + i, event2.getPath());
                assertEquals(EventType.NodeDataChanged, event2.getType());
                assertEquals(KeeperState.SyncConnected, event2.getState());

                // small chance that an unexpected message was delivered
                //  after this check, but we would catch that next time
                //  we check events
                assertEquals(0, watchers2[i].events.size());
            }

        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    private void performClientTest(boolean withWatcherObj) throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = null;
        try {
            MyWatcher watcher = new MyWatcher();
            zk = createClient(watcher, hostPort);
            LOG.info("Before create /benwashere");
            zk.create("/benwashere", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            LOG.info("After create /benwashere");
            try {
                zk.setData("/benwashere", "hi".getBytes(), 57);
                fail("Should have gotten BadVersion exception");
            } catch (KeeperException.BadVersionException e) {
                // expected that
            } catch (KeeperException e) {
                fail("Should have gotten BadVersion exception");
            }
            LOG.info("Before delete /benwashere");
            zk.delete("/benwashere", 0);
            LOG.info("After delete /benwashere");
            zk.close();

            Thread.sleep(2000);

            zk = createClient(watcher, hostPort);

            LOG.info("Before delete /");

            try {
                zk.delete("/", -1);
                fail("deleted root!");
            } catch (KeeperException.BadArgumentsException e) {
                // good, expected that
            }
            Stat stat = new Stat();
            // Test basic create, ls, and getData
            zk.create("/pat", "Pat was here".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            LOG.info("Before create /ben");
            zk.create("/pat/ben", "Ben was here".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            LOG.info("Before getChildren /pat");
            List<String> children = zk.getChildren("/pat", false);
            assertEquals(1, children.size());
            assertEquals("ben", children.get(0));
            List<String> children2 = zk.getChildren("/pat", false, null);
            assertEquals(children, children2);
            String value = new String(zk.getData("/pat/ben", false, stat));
            assertEquals("Ben was here", value);
            // Test stat and watch of non existent node

            try {
                if (withWatcherObj) {
                    assertEquals(null, zk.exists("/frog", watcher));
                } else {
                    assertEquals(null, zk.exists("/frog", true));
                }
                LOG.info("Comment: asseting passed for frog setting /");
            } catch (KeeperException.NoNodeException e) {
                // OK, expected that
            }
            zk.create("/frog", "hi".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            // the first poll is just a session delivery
            LOG.info("Comment: checking for events length {}", watcher.events.size());
            WatchedEvent event = watcher.events.poll(10, TimeUnit.SECONDS);
            assertEquals("/frog", event.getPath());
            assertEquals(EventType.NodeCreated, event.getType());
            assertEquals(KeeperState.SyncConnected, event.getState());
            // Test child watch and create with sequence
            zk.getChildren("/pat/ben", true);
            for (int i = 0; i < 10; i++) {
                zk.create("/pat/ben/"
                                  + i
                                  + "-", Integer.toString(i).getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            }
            children = zk.getChildren("/pat/ben", false);
            Collections.sort(children);
            assertEquals(10, children.size());
            for (int i = 0; i < 10; i++) {
                final String name = children.get(i);
                assertTrue(name.startsWith(i + "-"), "starts with -");
                byte[] b;
                if (withWatcherObj) {
                    b = zk.getData("/pat/ben/" + name, watcher, stat);
                } else {
                    b = zk.getData("/pat/ben/" + name, true, stat);
                }
                assertEquals(Integer.toString(i), new String(b));
                zk.setData("/pat/ben/" + name, "new".getBytes(), stat.getVersion());
                if (withWatcherObj) {
                    stat = zk.exists("/pat/ben/" + name, watcher);
                } else {
                    stat = zk.exists("/pat/ben/" + name, true);
                }
                zk.delete("/pat/ben/" + name, stat.getVersion());
            }
            event = watcher.events.poll(10, TimeUnit.SECONDS);
            assertEquals("/pat/ben", event.getPath());
            assertEquals(EventType.NodeChildrenChanged, event.getType());
            assertEquals(KeeperState.SyncConnected, event.getState());
            for (int i = 0; i < 10; i++) {
                event = watcher.events.poll(10, TimeUnit.SECONDS);
                final String name = children.get(i);
                assertEquals("/pat/ben/" + name, event.getPath());
                assertEquals(EventType.NodeDataChanged, event.getType());
                assertEquals(KeeperState.SyncConnected, event.getState());
                event = watcher.events.poll(10, TimeUnit.SECONDS);
                assertEquals("/pat/ben/" + name, event.getPath());
                assertEquals(EventType.NodeDeleted, event.getType());
                assertEquals(KeeperState.SyncConnected, event.getState());
            }
            zk.create("/good\u0040path", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            zk.create("/duplicate", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            try {
                zk.create("/duplicate", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                fail("duplicate create allowed");
            } catch (KeeperException.NodeExistsException e) {
                // OK, expected that
            }
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    // Test that sequential filenames are being created correctly,
    // with 0-padding in the filename
    @Test
    public void testSequentialNodeNames() throws IOException, InterruptedException, KeeperException {
        String path = "/SEQUENCE";
        String file = "TEST";
        String filepath = path + "/" + file;

        ZooKeeper zk = null;
        try {
            zk = createClient();
            zk.create(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            List<String> children = zk.getChildren(path, false);
            assertEquals(1, children.size());
            assertEquals(file + "0000000000", children.get(0));

            zk.create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            children = zk.getChildren(path, false);
            assertEquals(2, children.size());
            assertTrue(children.contains(file + "0000000001"), "contains child 1");

            zk.create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            children = zk.getChildren(path, false);
            assertEquals(3, children.size());
            assertTrue(children.contains(file + "0000000002"), "contains child 2");

            // The pattern is holding so far.  Let's run the counter a bit
            // to be sure it continues to spit out the correct answer
            for (int i = children.size(); i < 105; i++) {
                zk.create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            }

            children = zk.getChildren(path, false);
            assertTrue(children.contains(file + "0000000104"), "contains child 104");

        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    // Test that data provided when
    // creating sequential nodes is stored properly
    @Test
    public void testSequentialNodeData() throws Exception {
        ZooKeeper zk = null;
        String queue_handle = "/queue";
        try {
            zk = createClient();

            zk.create(queue_handle, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create(queue_handle + "/element", "0".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            zk.create(queue_handle + "/element", "1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            List<String> children = zk.getChildren(queue_handle, true);
            assertEquals(children.size(), 2);
            String child1 = children.get(0);
            String child2 = children.get(1);
            int compareResult = child1.compareTo(child2);
            assertNotSame(compareResult, 0);
            if (compareResult < 0) {
            } else {
                String temp = child1;
                child1 = child2;
                child2 = temp;
            }
            String child1data = new String(zk.getData(queue_handle + "/" + child1, false, null));
            String child2data = new String(zk.getData(queue_handle + "/" + child2, false, null));
            assertEquals(child1data, "0");
            assertEquals(child2data, "1");
        } finally {
            if (zk != null) {
                zk.close();
            }
        }

    }

    @Test
    public void testLargeNodeData() throws Exception {
        ZooKeeper zk = null;
        String queue_handle = "/large";
        try {
            zk = createClient();

            zk.create(queue_handle, new byte[500000], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } finally {
            if (zk != null) {
                zk.close();
            }
        }

    }

    private void verifyCreateFails(String path, ZooKeeper zk) throws Exception {
        try {
            zk.create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (IllegalArgumentException e) {
            // this is good
            return;
        }
        fail("bad path \"" + path + "\" not caught");
    }

    // Test that the path string is validated
    @Test
    public void testPathValidation() throws Exception {
        ZooKeeper zk = createClient();

        verifyCreateFails(null, zk);
        verifyCreateFails("", zk);
        verifyCreateFails("//", zk);
        verifyCreateFails("///", zk);
        verifyCreateFails("////", zk);
        verifyCreateFails("/.", zk);
        verifyCreateFails("/..", zk);
        verifyCreateFails("/./", zk);
        verifyCreateFails("/../", zk);
        verifyCreateFails("/foo/./", zk);
        verifyCreateFails("/foo/../", zk);
        verifyCreateFails("/foo/.", zk);
        verifyCreateFails("/foo/..", zk);
        verifyCreateFails("/./.", zk);
        verifyCreateFails("/../..", zk);
        verifyCreateFails("/\u0001foo", zk);
        verifyCreateFails("/foo/bar/", zk);
        verifyCreateFails("/foo//bar", zk);
        verifyCreateFails("/foo/bar//", zk);

        verifyCreateFails("foo", zk);
        verifyCreateFails("a", zk);

        zk.create("/createseqpar", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        // next two steps - related to sequential processing
        // 1) verify that empty child name fails if not sequential
        try {
            zk.create("/createseqpar/", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            assertTrue(false);
        } catch (IllegalArgumentException be) {
            // catch this.
        }

        // 2) verify that empty child name success if sequential
        zk.create("/createseqpar/", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
        zk.create("/createseqpar/.", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
        zk.create("/createseqpar/..", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
        try {
            zk.create("/createseqpar//", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            assertTrue(false);
        } catch (IllegalArgumentException be) {
            // catch this.
        }
        try {
            zk.create("/createseqpar/./", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            assertTrue(false);
        } catch (IllegalArgumentException be) {
            // catch this.
        }
        try {
            zk.create("/createseqpar/../", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            assertTrue(false);
        } catch (IllegalArgumentException be) {
            // catch this.
        }

        //check for the code path that throws at server
        PrepRequestProcessor.setFailCreate(true);
        try {
            zk.create("/m", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            assertTrue(false);
        } catch (KeeperException.BadArgumentsException be) {
            // catch this.
        }
        PrepRequestProcessor.setFailCreate(false);
        zk.create("/.foo", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/.f.", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/..f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/..f..", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/f.c", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/f\u0040f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/f/.f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/f/f.", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/f/..f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/f/f..", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/f/.f/f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/f/f./f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    @Test
    public void testDeleteWithChildren() throws Exception {
        ZooKeeper zk = createClient();
        zk.create("/parent", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/parent/child", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        try {
            zk.delete("/parent", -1);
            fail("Should have received a not equals message");
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NOTEMPTY, e.code());
        }
        zk.delete("/parent/child", -1);
        zk.delete("/parent", -1);
        zk.close();
    }

    private class VerifyClientCleanup extends Thread {

        int count;
        int current = 0;

        VerifyClientCleanup(String name, int count) {
            super(name);
            this.count = count;
        }

        public void run() {
            try {
                for (; current < count; current++) {
                    TestableZooKeeper zk = createClient();
                    // we've asked to close, wait for it to finish closing
                    // all the sub-threads otw the selector may not be
                    // closed when we check (false positive on test failure
                    zk.close(CONNECTION_TIMEOUT);
                }
            } catch (Throwable t) {
                LOG.error("test failed", t);
            }
        }

    }

    /**
     * Verify that the client is cleaning up properly. Open/close a large
     * number of sessions. Essentially looking to see if sockets/selectors
     * are being cleaned up properly during close.
     *
     * @throws Throwable
     */
    @Test
    public void testClientCleanup() throws Throwable {
        OSMXBean osMbean = new OSMXBean();
        if (!osMbean.getUnix()) {
            LOG.warn("skipping testClientCleanup, only available on Unix");
            return;
        }

        final int threadCount = 3;
        final int clientCount = 10;

        /* Log the number of fds used before and after a test is run. Verifies
         * we are freeing resources correctly. Unfortunately this only works
         * on unix systems (the only place sun has implemented as part of the
         * mgmt bean api).
         */
        long initialFdCount = osMbean.getOpenFileDescriptorCount();

        VerifyClientCleanup[] threads = new VerifyClientCleanup[threadCount];

        for (int i = 0; i < threads.length; i++) {
            threads[i] = new VerifyClientCleanup("VCC" + i, clientCount);
            threads[i].start();
        }

        for (int i = 0; i < threads.length; i++) {
            threads[i].join(CONNECTION_TIMEOUT);
            assertTrue(threads[i].current == threads[i].count);
        }

        // if this fails it means we are not cleaning up after the closed
        // sessions.
        long currentCount = osMbean.getOpenFileDescriptorCount();
        final String logmsg = "open fds after test ({}) are not significantly higher than before ({})";

        if (currentCount > initialFdCount + 10) {
            // consider as error
            LOG.error(logmsg, currentCount, initialFdCount);
        } else {
            LOG.info(logmsg, currentCount, initialFdCount);
        }
    }

    /**
     * We create a perfectly valid 'exists' request, except that the opcode is wrong.
     * @throws Exception
     */
    @Test
    public void testNonExistingOpCode() throws Exception {
        final CountDownLatch clientDisconnected = new CountDownLatch(1);
        Watcher watcher = new Watcher() {
            @Override
            public synchronized void process(WatchedEvent event) {
                if (event.getState() == KeeperState.Disconnected) {
                    clientDisconnected.countDown();
                }
            }
        };
        TestableZooKeeper zk = new TestableZooKeeper(hostPort, CONNECTION_TIMEOUT, watcher);

        final String path = "/m1";

        RequestHeader h = new RequestHeader();
        h.setType(888);  // This code does not exists
        ExistsRequest request = new ExistsRequest();
        request.setPath(path);
        request.setWatch(false);
        ExistsResponse response = new ExistsResponse();

        ReplyHeader r = zk.submitRequest(h, request, response, null);

        assertEquals(r.getErr(), Code.UNIMPLEMENTED.intValue());

        // Sending a nonexisting opcode should cause the server to disconnect
        assertTrue(clientDisconnected.await(5000, TimeUnit.MILLISECONDS), "failed to disconnect");
        zk.close();
    }

    @Test
    public void testTryWithResources() throws Exception {
        ZooKeeper zooKeeper;
        try (ZooKeeper zk = createClient()) {
            zooKeeper = zk;
            assertTrue(zooKeeper.getState().isAlive());
        }

        assertFalse(zooKeeper.getState().isAlive());
    }

    @Test
    public void testCXidRollover() throws Exception {
        TestableZooKeeper zk = null;
        try {
            zk = createClient();
            zk.setXid(Integer.MAX_VALUE - 10);

            zk.create("/testnode", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            for (int i = 0; i < 20; ++i) {
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicInteger rc = new AtomicInteger(0);
                zk.setData("/testnode", "".getBytes(), -1, (retcode, path, ctx, stat) -> {
                    rc.set(retcode);
                    latch.countDown();
                }, null);
                assertTrue(latch.await(zk.getSessionTimeout(), TimeUnit.MILLISECONDS), "setData should complete within 5s");
                assertEquals(Code.OK.intValue(), rc.get(), "setData should have succeeded");
            }
            zk.delete("/testnode", -1);
            assertTrue(zk.checkXid() > 0, "xid should be positive");
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

}
