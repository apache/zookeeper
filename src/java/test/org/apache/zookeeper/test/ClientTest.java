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

package org.apache.zookeeper.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.InvalidACLException;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.Perms;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.WatchedEvent;
import org.junit.Test;
public class ClientTest extends ClientBase {
    protected static final Logger LOG = Logger.getLogger(ClientTest.class);

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        LOG.info("FINISHED " + getName());
    }

    @Test
    public void testPing() throws Exception {
        ZooKeeper zkIdle = null;
        ZooKeeper zkWatchCreator = null;
        try {
            zkIdle = createClient();
            zkWatchCreator = createClient();
            for (int i = 0; i < 30; i++) {
                zkWatchCreator.create("/" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            for (int i = 0; i < 30; i++) {
                zkIdle.exists("/" + i, true);
            }
            for (int i = 0; i < 30; i++) {
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
    public void testClientwithoutWatcherObj() throws IOException,
            InterruptedException, KeeperException
    {
        performClientTest(false);
    }

    @Test
    public void testClientWithWatcherObj() throws IOException,
            InterruptedException, KeeperException
    {
        performClientTest(true);
    }

    @Test
    public void testACLs() throws Exception {
        ZooKeeper zk = null;
        try {
            zk = createClient();
            try {
                zk.create("/acltest", new byte[0], Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
                fail("Should have received an invalid acl error");
            } catch(InvalidACLException e) {
                LOG.info("Test successful, invalid acl received : "
                        + e.getMessage());
            }
            try {
                ArrayList<ACL> testACL = new ArrayList<ACL>();
                testACL.add(new ACL(Perms.ALL | Perms.ADMIN, Ids.AUTH_IDS));
                testACL.add(new ACL(Perms.ALL | Perms.ADMIN, new Id("ip", "127.0.0.1/8")));
                zk.create("/acltest", new byte[0], testACL, CreateMode.PERSISTENT);
                fail("Should have received an invalid acl error");
            } catch(InvalidACLException e) {
                LOG.info("Test successful, invalid acl received : "
                        + e.getMessage());
            }
            zk.addAuthInfo("digest", "ben:passwd".getBytes());
            zk.create("/acltest", new byte[0], Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            zk.close();
            zk = createClient();
            zk.addAuthInfo("digest", "ben:passwd2".getBytes());
            try {
                zk.getData("/acltest", false, new Stat());
                fail("Should have received a permission error");
            } catch (KeeperException e) {
                assertEquals(Code.NoAuth, e.getCode());
            }
            zk.addAuthInfo("digest", "ben:passwd".getBytes());
            zk.getData("/acltest", false, new Stat());
            zk.setACL("/acltest", Ids.OPEN_ACL_UNSAFE, -1);
            zk.close();
            zk = createClient();
            zk.getData("/acltest", false, new Stat());
            List<ACL> acls = zk.getACL("/acltest", new Stat());
            assertEquals(1, acls.size());
            assertEquals(Ids.OPEN_ACL_UNSAFE, acls);
            zk.close();
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    private class MyWatcher extends CountdownWatcher {
        LinkedBlockingQueue<WatchedEvent> events =
            new LinkedBlockingQueue<WatchedEvent>();

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
    public void testMutipleWatcherObjs()
        throws IOException, InterruptedException, KeeperException
    {
        ZooKeeper zk = createClient(new CountdownWatcher(), hostPort);
        try {
            MyWatcher watchers[] = new MyWatcher[100];
            MyWatcher watchers2[] = new MyWatcher[watchers.length];
            for (int i = 0; i < watchers.length; i++) {
                watchers[i] = new MyWatcher();
                watchers2[i] = new MyWatcher();
                zk.create("/foo-" + i, ("foodata" + i).getBytes(),
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
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
                WatchedEvent event =
                    watchers[i].events.poll(10, TimeUnit.SECONDS);
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
                WatchedEvent event =
                    watchers[i].events.poll(10, TimeUnit.SECONDS);
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
                WatchedEvent event =
                    watchers[i].events.poll(10, TimeUnit.SECONDS);
                assertEquals("/foo-" + i, event.getPath());
                assertEquals(EventType.NodeDataChanged, event.getType());
                assertEquals(KeeperState.SyncConnected, event.getState());
                
                // small chance that an unexpected message was delivered
                //  after this check, but we would catch that next time
                //  we check events
                assertEquals(0, watchers[i].events.size());

                // watchers2
                WatchedEvent event2 =
                    watchers2[i].events.poll(10, TimeUnit.SECONDS);
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

    private void performClientTest(boolean withWatcherObj)
        throws IOException, InterruptedException, KeeperException
    {
        ZooKeeper zk = null;
        try {
            MyWatcher watcher = new MyWatcher();
            zk = createClient(watcher, hostPort);
            //LOG.info("Created client: " + zk.describeCNXN());
            LOG.info("Before create /benwashere");
            zk.create("/benwashere", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            LOG.info("After create /benwashere");
            try {
                zk.setData("/benwashere", "hi".getBytes(), 57);
                fail("Should have gotten BadVersion exception");
            } catch(KeeperException.BadVersionException e) {
                // expected that
            } catch (KeeperException e) {
                fail("Should have gotten BadVersion exception");
            }
            LOG.info("Before delete /benwashere");
            zk.delete("/benwashere", 0);
            LOG.info("Before delete /benwashere");
            zk.close();
            //LOG.info("Closed client: " + zk.describeCNXN());
            Thread.sleep(2000);
            
            zk = createClient(watcher, hostPort);
            //LOG.info("Created a new client: " + zk.describeCNXN());
            LOG.info("Before delete /");

            try {
                zk.delete("/", -1);
                fail("deleted root!");
            } catch(KeeperException.BadArgumentsException e) {
                // good, expected that
            }
            Stat stat = new Stat();
            // Test basic create, ls, and getData
            LOG.info("Before create /ben");
            zk.create("/ben", "Ben was here".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            LOG.info("Before getChildren /");
            List<String> children = zk.getChildren("/", false);
            assertEquals(1, children.size());
            assertEquals("ben", children.get(0));
            String value = new String(zk.getData("/ben", false, stat));
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
            zk.create("/frog", "hi".getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            // the first poll is just a session delivery
            LOG.info("Comment: checking for events length "
                     + watcher.events.size());
            WatchedEvent event = watcher.events.poll(10, TimeUnit.SECONDS);
            assertEquals("/frog", event.getPath());
            assertEquals(EventType.NodeCreated, event.getType());
            assertEquals(KeeperState.SyncConnected, event.getState());
            // Test child watch and create with sequence
            zk.getChildren("/ben", true);
            for (int i = 0; i < 10; i++) {
                zk.create("/ben/" + i + "-", Integer.toString(i).getBytes(),
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            }
            children = zk.getChildren("/ben", false);
            Collections.sort(children);
            assertEquals(10, children.size());
            for (int i = 0; i < 10; i++) {
                final String name = children.get(i);
                assertTrue("starts with -", name.startsWith(i + "-"));
                byte b[];
                if (withWatcherObj) {
                    b = zk.getData("/ben/" + name, watcher, stat);
                } else {
                    b = zk.getData("/ben/" + name, true, stat);
                }
                assertEquals(Integer.toString(i), new String(b));
                zk.setData("/ben/" + name, "new".getBytes(), stat.getVersion());
                if (withWatcherObj) {
                    stat = zk.exists("/ben/" + name, watcher);
                } else {
                stat = zk.exists("/ben/" + name, true);
                }
                zk.delete("/ben/" + name, stat.getVersion());
            }
            event = watcher.events.poll(10, TimeUnit.SECONDS);
            assertEquals("/ben", event.getPath());
            assertEquals(EventType.NodeChildrenChanged, event.getType());
            assertEquals(KeeperState.SyncConnected, event.getState());
            for (int i = 0; i < 10; i++) {
                event = watcher.events.poll(10, TimeUnit.SECONDS);
                final String name = children.get(i);
                assertEquals("/ben/" + name, event.getPath());
                assertEquals(EventType.NodeDataChanged, event.getType());
                assertEquals(KeeperState.SyncConnected, event.getState());
                event = watcher.events.poll(10, TimeUnit.SECONDS);
                assertEquals("/ben/" + name, event.getPath());
                assertEquals(EventType.NodeDeleted, event.getType());
                assertEquals(KeeperState.SyncConnected, event.getState());
            }
            zk.create("/good\u0001path", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            //try {
            //    zk.create("/bad\u0000path", "".getBytes(), null, CreateMode.PERSISTENT);
            //    fail("created an invalid path");
            //} catch(KeeperException e) {
            //    assertEquals(KeeperException.Code.BadArguments, e.getCode());
            //}

            zk.create("/duplicate", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            try {
                zk.create("/duplicate", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                fail("duplicate create allowed");
            } catch(KeeperException.NodeExistsException e) {
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
    public void testSequentialNodeNames()
        throws IOException, InterruptedException, KeeperException
    {
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
            assertTrue("contains child 1",  children.contains(file + "0000000001"));

            zk.create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            children = zk.getChildren(path, false);
            assertEquals(3, children.size());
            assertTrue("contains child 2",
                       children.contains(file + "0000000002"));

            // The pattern is holding so far.  Let's run the counter a bit
            // to be sure it continues to spit out the correct answer
            for(int i = children.size(); i < 105; i++)
               zk.create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);

            children = zk.getChildren(path, false);
            assertTrue("contains child 104",
                       children.contains(file + "0000000104"));

        }
        finally {
            if(zk != null)
                zk.close();
        }
    }

//    private void notestConnections()
//        throws IOException, InterruptedException, KeeperException
//    {
//        ZooKeeper zk;
//        for(int i = 0; i < 2000; i++) {
//            if (i % 100 == 0) {
//                LOG.info("Testing " + i + " connections");
//            }
//            // We want to make sure socket descriptors are going away
//            zk = new ZooKeeper(hostPort, 30000, this);
//            zk.getData("/", false, new Stat());
//            zk.close();
//        }
//    }

    @Test
    public void testDeleteWithChildren() throws Exception {
        ZooKeeper zk = createClient();
        zk.create("/parent", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/parent/child", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        try {
            zk.delete("/parent", -1);
            fail("Should have received a not equals message");
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NotEmpty, e.getCode());
        }
        zk.delete("/parent/child", -1);
        zk.delete("/parent", -1);
        zk.close();
    }
    
    private static final long HAMMERTHREAD_LATENCY = 5;
    
    private static abstract class HammerThread extends Thread {
        protected final int count;
        protected volatile int current = 0;
        
        HammerThread(String name, int count) {
            super(name);
            this.count = count;
        }
    }

    private static class BasicHammerThread extends HammerThread {
        private final ZooKeeper zk;
        private final String prefix;

        BasicHammerThread(String name, ZooKeeper zk, String prefix, int count) {
            super(name, count);
            this.zk = zk;
            this.prefix = prefix;
        }

        public void run() {
            byte b[] = new byte[256];
            try {
                for (; current < count; current++) {
                    // Simulate a bit of network latency...
                    Thread.sleep(HAMMERTHREAD_LATENCY);
                    zk.create(prefix + current, b, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                }
            } catch (Throwable t) {
                LOG.error("Client create operation failed", t);
            } finally {
                try {
                    zk.close();
                } catch (InterruptedException e) {
                    LOG.warn("Unexpected", e);
                }
            }
        }
    }

    private static class SuperHammerThread extends HammerThread {
        private final ClientTest parent;
        private final String prefix;

        SuperHammerThread(String name, ClientTest parent, String prefix,
                int count)
        {
            super(name, count);
            this.parent = parent;
            this.prefix = prefix;
        }

        public void run() {
            byte b[] = new byte[256];
            try {
                for (; current < count; current++) {
                    ZooKeeper zk = parent.createClient();
                    try {
                        zk.create(prefix + current, b, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    } finally {
                        try {
                            zk.close();
                        } catch (InterruptedException e) {
                            LOG.warn("Unexpected", e);
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.error("Client create operation failed", t);
            }
        }
    }

    /**
     * Separate threads each creating a number of nodes. Each thread
     * is using a non-shared (owned by thread) client for all node creations.
     * @throws Throwable
     */
    @Test
    public void testHammerBasic() throws Throwable {
        try {
            final int threadCount = 10;
            final int childCount = 1000;
            
            HammerThread[] threads = new HammerThread[threadCount];
            long start = System.currentTimeMillis();
            for (int i = 0; i < threads.length; i++) {
                ZooKeeper zk = createClient();
                String prefix = "/test-" + i;
                zk.create(prefix, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                prefix += "/";
                HammerThread thread = 
                    new BasicHammerThread("BasicHammerThread-" + i, zk, prefix,
                            childCount);
                thread.start();
                
                threads[i] = thread;
            }
            
            verifyHammer(start, threads, childCount);
        } catch (Throwable t) {
            LOG.error("test failed", t);
            throw t;
        }
    }
    
    /**
     * Separate threads each creating a number of nodes. Each thread
     * is creating a new client for each node creation.
     * @throws Throwable
     */
    @Test
    public void testHammerSuper() throws Throwable {
        try {
            final int threadCount = 5;
            final int childCount = 10;
            
            HammerThread[] threads = new HammerThread[threadCount];
            long start = System.currentTimeMillis();
            for (int i = 0; i < threads.length; i++) {
                String prefix = "/test-" + i;
                {
                    ZooKeeper zk = createClient();
                    try {
                        zk.create(prefix, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    } finally {
                        zk.close();
                    }
                }
                prefix += "/";
                HammerThread thread = 
                    new SuperHammerThread("SuperHammerThread-" + i, this,
                            prefix, childCount);
                thread.start();
                
                threads[i] = thread;
            }
            
            verifyHammer(start, threads, childCount);
        } catch (Throwable t) {
            LOG.error("test failed", t);
            throw t;
        }
    }
    
    public void verifyHammer(long start, HammerThread[] threads, int childCount) 
        throws IOException, InterruptedException, KeeperException 
    {
        // look for the clients to finish their create operations
        LOG.info("Starting check for completed hammers");
        int workingCount = threads.length;
        for (int i = 0; i < 120; i++) {
            Thread.sleep(10000);
            for (HammerThread h : threads) {
                if (!h.isAlive() || h.current == h.count) {
                    workingCount--;
                }
            }
            if (workingCount == 0) {
                break;
            }
            workingCount = threads.length;
        }
        if (workingCount > 0) {
            for (HammerThread h : threads) {
                LOG.warn(h.getName() + " never finished creation, current:" 
                        + h.current);
            }
        } else {
            LOG.info("Hammer threads completed creation operations");
        }
        
        for (HammerThread h : threads) {
            final int safetyFactor = 3;
            verifyThreadTerminated(h,
                    threads.length * childCount 
                    * HAMMERTHREAD_LATENCY * safetyFactor);
        }
        LOG.info(new Date() + " Total time "
                + (System.currentTimeMillis() - start));
        
        ZooKeeper zk = createClient();
        try {
            
            LOG.info("******************* Connected to ZooKeeper" + new Date());
            for (int i = 0; i < threads.length; i++) {
                LOG.info("Doing thread: " + i + " " + new Date());
                List<String> children =
                    zk.getChildren("/test-" + i, false);
                assertEquals(childCount, children.size());
            }
            for (int i = 0; i < threads.length; i++) {
                List<String> children =
                    zk.getChildren("/test-" + i, false);
                assertEquals(childCount, children.size());
            }
        } finally {
            zk.close();
        }
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
                    ZooKeeper zk = createClient();
                    zk.close();
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
        final int threadCount = 20;
        final int clientCount = 100;
        
        VerifyClientCleanup threads[] = new VerifyClientCleanup[threadCount];
        
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new VerifyClientCleanup("VCC" + i, clientCount);
            threads[i].start();
        }
        
        for (int i = 0; i < threads.length; i++) {
            threads[i].join(600000);
            assertTrue(threads[i].current == threads[i].count);
        }
    }
}
