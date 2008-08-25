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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.InvalidACLException;
import org.apache.zookeeper.ZooDefs.CreateFlags;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.Perms;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.WatcherEvent;
import org.junit.Test;

public class ClientTest extends ClientBase implements Watcher {
    protected static final Logger LOG = Logger.getLogger(ClientTest.class);

    LinkedBlockingQueue<WatcherEvent> events =
        new LinkedBlockingQueue<WatcherEvent>();
    protected volatile CountDownLatch clientConnected;

    protected ZooKeeper createClient(Watcher watcher)
        throws IOException, InterruptedException
    {
        return createClient(watcher, hostPort);
    }

    protected ZooKeeper createClient(Watcher watcher, String hp)
        throws IOException, InterruptedException
    {
        clientConnected = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper(hp, 20000, watcher);
        if (!clientConnected.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)) {
            fail("Unable to connect to server");
        }
        return zk;
    }

    @Override
    protected void tearDown() throws Exception {
        clientConnected = null;
        super.tearDown();
        LOG.info("FINISHED " + getName());
    }

    @Test
    public void testPing() throws Exception {
        ZooKeeper zkIdle = null;
        ZooKeeper zkWatchCreator = null;
        try {
            zkIdle = createClient(this);
            zkWatchCreator = createClient(this);
            for (int i = 0; i < 30; i++) {
                zkWatchCreator.create("/" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, 0);
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
            zk = createClient(this);
            try {
                zk.create("/acltest", new byte[0], Ids.CREATOR_ALL_ACL, 0);
                fail("Should have received an invalid acl error");
            } catch(InvalidACLException e) {
                LOG.error("Invalid acl", e);
            }
            try {
                ArrayList<ACL> testACL = new ArrayList<ACL>();
                testACL.add(new ACL(Perms.ALL | Perms.ADMIN, Ids.AUTH_IDS));
                testACL.add(new ACL(Perms.ALL | Perms.ADMIN, new Id("ip", "127.0.0.1/8")));
                zk.create("/acltest", new byte[0], testACL, 0);
                fail("Should have received an invalid acl error");
            } catch(InvalidACLException e) {
                LOG.error("Invalid acl", e);
            }
            zk.addAuthInfo("digest", "ben:passwd".getBytes());
            zk.create("/acltest", new byte[0], Ids.CREATOR_ALL_ACL, 0);
            zk.close();
            zk = createClient(this);
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
            zk = createClient(this);
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

    private void performClientTest(boolean withWatcherObj) throws IOException,
            InterruptedException, KeeperException {
        ZooKeeper zk = null;
        try {
            zk =createClient(this);
            //LOG.info("Created client: " + zk.describeCNXN());
            LOG.info("Before create /benwashere");
            zk.create("/benwashere", "".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
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
            zk = createClient(this);
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
            zk.create("/ben", "Ben was here".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
            LOG.info("Before getChildren /");
            List<String> children = zk.getChildren("/", false);
            assertEquals(1, children.size());
            assertEquals("ben", children.get(0));
            String value = new String(zk.getData("/ben", false, stat));
            assertEquals("Ben was here", value);
            // Test stat and watch of non existent node
            try {
                if (withWatcherObj) {
                    assertEquals(null, zk.exists("/frog", new MyWatcher()));
                } else {
                assertEquals(null, zk.exists("/frog", true));
                }
                LOG.info("Comment: asseting passed for frog setting /");
            } catch (KeeperException.NoNodeException e) {
                // OK, expected that
            }
            zk.create("/frog", "hi".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
            // the first poll is just a sesssion delivery
            LOG.info("Comment: checking for events length " + events.size());
            WatcherEvent event = events.poll(10, TimeUnit.SECONDS);
            assertEquals("/frog", event.getPath());
            assertEquals(Event.EventNodeCreated, event.getType());
            assertEquals(Event.KeeperStateSyncConnected, event.getState());
            // Test child watch and create with sequence
            zk.getChildren("/ben", true);
            for (int i = 0; i < 10; i++) {
                zk.create("/ben/" + i + "-", Integer.toString(i).getBytes(),
                        Ids.OPEN_ACL_UNSAFE, CreateFlags.SEQUENCE);
            }
            children = zk.getChildren("/ben", false);
            Collections.sort(children);
            assertEquals(10, children.size());
            for (int i = 0; i < 10; i++) {
                final String name = children.get(i);
                assertTrue("starts with -", name.startsWith(i + "-"));
                byte b[];
                if (withWatcherObj) {
                    b = zk.getData("/ben/" + name, new MyWatcher(), stat);
                } else {
                    b = zk.getData("/ben/" + name, true, stat);
                }
                assertEquals(Integer.toString(i), new String(b));
                zk.setData("/ben/" + name, "new".getBytes(), stat.getVersion());
                if (withWatcherObj) {
                    stat = zk.exists("/ben/" + name, new MyWatcher());
                } else {
                stat = zk.exists("/ben/" + name, true);
                }
                zk.delete("/ben/" + name, stat.getVersion());
            }
            event = events.poll(10, TimeUnit.SECONDS);
            assertEquals("/ben", event.getPath());
            assertEquals(Event.EventNodeChildrenChanged, event.getType());
            assertEquals(Event.KeeperStateSyncConnected, event.getState());
            for (int i = 0; i < 10; i++) {
                event = events.poll(10, TimeUnit.SECONDS);
                final String name = children.get(i);
                assertEquals("/ben/" + name, event.getPath());
                assertEquals(Event.EventNodeDataChanged, event.getType());
                assertEquals(Event.KeeperStateSyncConnected, event.getState());
                event = events.poll(10, TimeUnit.SECONDS);
                assertEquals("/ben/" + name, event.getPath());
                assertEquals(Event.EventNodeDeleted, event.getType());
                assertEquals(Event.KeeperStateSyncConnected, event.getState());
            }
            zk.create("/good\u0001path", "".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
            //try {
            //    zk.create("/bad\u0000path", "".getBytes(), null, 0);
            //    fail("created an invalid path");
            //} catch(KeeperException e) {
            //    assertEquals(KeeperException.Code.BadArguments, e.getCode());
            //}

            zk.create("/duplicate", "".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
            try {
                zk.create("/duplicate", "".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
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
    public void testSequentialNodeNames() throws IOException, InterruptedException, KeeperException {
        String path = "/SEQUENCE";
    String file = "TEST";
    String filepath = path + "/" + file;

        ZooKeeper zk = null;
        try {
            zk =createClient(this);
            zk.create(path, new byte[0], Ids.OPEN_ACL_UNSAFE, 0);
            zk.create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateFlags.SEQUENCE);
            List<String> children = zk.getChildren(path, false);
            assertEquals(1, children.size());
            assertEquals(file + "0000000000", children.get(0));

            zk.create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateFlags.SEQUENCE);
            children = zk.getChildren(path, false);
            assertEquals(2, children.size());
            assertTrue("contains child 1",
                       children.contains(file + "0000000001"));

            zk.create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateFlags.SEQUENCE);
            children = zk.getChildren(path, false);
            assertEquals(3, children.size());
            assertTrue("contains child 2",
                       children.contains(file + "0000000002"));

            // The pattern is holding so far.  Let's run the counter a bit
            // to be sure it continues to spit out the correct answer
            for(int i = children.size(); i < 105; i++)
               zk.create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateFlags.SEQUENCE);

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
        ZooKeeper zk = createClient(this);
        zk.create("/parent", new byte[0], Ids.OPEN_ACL_UNSAFE, 0);
        zk.create("/parent/child", new byte[0], Ids.OPEN_ACL_UNSAFE, 0);
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

    private static class HammerThread extends Thread {
        private static final long LATENCY = 5;
        
        private final ZooKeeper zk;
        private final String prefix;
        private final int count;
        private volatile int current = 0;

        HammerThread(String name, ZooKeeper zk, String prefix, int count) {
            super(name);
            this.zk = zk;
            this.prefix = prefix;
            this.count = count;
        }

        public void run() {
            byte b[] = new byte[256];
            try {
                for (; current < count; current++) {
                    // Simulate a bit of network latency...
                    Thread.sleep(LATENCY);
                    zk.create(prefix + current, b, Ids.OPEN_ACL_UNSAFE, 0);
                }
            } catch (Exception e) {
                LOG.error("Client create operation failed", e);
            } finally {
                if (zk != null) {
                    try {
                        zk.close();
                    } catch (InterruptedException e) {
                        LOG.warn("Unexpected", e);
                    }
                }
            }
        }
    }

    /*
     * Verify that all of the servers see the same number of nodes
     * at the root
     */
    void verifyRootOfAllServersMatch(String hostPort)
        throws InterruptedException, KeeperException, IOException
    {
        String parts[] = hostPort.split(",");

        // run through till the counts no longer change on each server
        // max 15 tries, with 2 second sleeps, so approx 30 seconds
        int[] counts = new int[parts.length];
        for (int j = 0; j < 100; j++) {
            int newcounts[] = new int[parts.length];
            int i = 0;
            for (String hp : parts) {
                ZooKeeper zk = createClient(this, hp);
                try {
                    newcounts[i++] = zk.getChildren("/", false).size();
                } finally {
                    zk.close();
                }
            }

            if (Arrays.equals(newcounts, counts)) {
                LOG.info("Found match with array:"
                        + Arrays.toString(newcounts));
                counts = newcounts;
                break;
            } else {
                counts = newcounts;
                Thread.sleep(10000);
            }
        }

        // verify all the servers reporting same number of nodes
        for (int i = 1; i < parts.length; i++) {
            assertEquals("node count not consistent", counts[i-1], counts[i]);
        }
    }


    @Test
    public void testHammer() 
        throws IOException, InterruptedException, KeeperException 
    {
        final int threadCount = 10;
        final int childCount = 1000;
        
        HammerThread[] threads = new HammerThread[threadCount];
        long start = System.currentTimeMillis();
        for (int i = 0; i < threads.length; i++) {
            Thread.sleep(10);
            ZooKeeper zk = createClient(this);
            String prefix = "/test-" + i;
            zk.create(prefix, new byte[0], Ids.OPEN_ACL_UNSAFE, 0);
            prefix += "/";
            HammerThread thread = 
                new HammerThread("HammerThread-" + i, zk, prefix, childCount);
            thread.start();
            
            threads[i] = thread;
        }
        
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
                    threadCount * childCount 
                    * HammerThread.LATENCY * safetyFactor);
        }
        LOG.info(new Date() + " Total time "
                + (System.currentTimeMillis() - start));
        
        ZooKeeper zk = createClient(this);
        
        LOG.info("******************* Connected to ZooKeeper" + new Date());
        for (int i = 0; i < threadCount; i++) {
            LOG.info("Doing thread: " + i + " " + new Date());
            List<String> children =
                zk.getChildren("/test-" + i, false);
            assertEquals(childCount, children.size());
        }
        for (int i = 0; i < threadCount; i++) {
            List<String> children =
                zk.getChildren("/test-" + i, false);
            assertEquals(childCount, children.size());
        }
    }

    public class MyWatcher implements Watcher {
        public void process(WatcherEvent event) {
            ClientTest.this.process(event);
        }
    }

    public void process(WatcherEvent event) {
        if (event.getState() == Event.KeeperStateSyncConnected) {
            clientConnected.countDown();
        }
        if (event.getType() != Event.EventNone) {
            try {
                events.put(event);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
