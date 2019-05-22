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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FollowerResyncConcurrencyTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(FollowerResyncConcurrencyTest.class);
    public static final long CONNECTION_TIMEOUT = ClientTest.CONNECTION_TIMEOUT;

    private AtomicInteger counter = new AtomicInteger(0);
    private AtomicInteger errors = new AtomicInteger(0);
    /**
     * Keep track of pending async operations, we shouldn't start verifying
     * the state until pending operation is 0
     */
    private AtomicInteger pending = new AtomicInteger(0);

    @Before
    public void setUp() throws Exception {
        pending.set(0);
        errors.set(0);
        counter.set(0);
    }

    @After
    public void tearDown() throws Exception {
        LOG.info("Error count {}" , errors.get());
    }

    /**
     * See ZOOKEEPER-1319 - verify that a lagging follwer resyncs correctly
     * 
     * 1) start with down quorum
     * 2) start leader/follower1, add some data
     * 3) restart leader/follower1
     * 4) start follower2
     * 5) verify data consistency across the ensemble
     * 
     * @throws Exception
     */
    @Test
    public void testLaggingFollowerResyncsUnderNewEpoch() throws Exception {
        CountdownWatcher watcher1 = new CountdownWatcher();
        CountdownWatcher watcher2 = new CountdownWatcher();
        CountdownWatcher watcher3 = new CountdownWatcher();

        QuorumUtil qu = new QuorumUtil(1);
        qu.shutdownAll();

        qu.start(1);
        qu.start(2);
        Assert.assertTrue("Waiting for server up", ClientBase.waitForServerUp("127.0.0.1:"
                + qu.getPeer(1).clientPort, ClientBase.CONNECTION_TIMEOUT));
        Assert.assertTrue("Waiting for server up", ClientBase.waitForServerUp("127.0.0.1:"
                + qu.getPeer(2).clientPort, ClientBase.CONNECTION_TIMEOUT));

        ZooKeeper zk1 =
                createClient(qu.getPeer(1).peer.getClientPort(), watcher1);
        LOG.info("zk1 has session id 0x{}", Long.toHexString(zk1.getSessionId()));

        final String resyncPath = "/resyncundernewepoch";
        zk1.create(resyncPath, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk1.close();

        qu.shutdown(1);
        qu.shutdown(2);
        Assert.assertTrue("Waiting for server down", ClientBase.waitForServerDown("127.0.0.1:"
                + qu.getPeer(1).clientPort, ClientBase.CONNECTION_TIMEOUT));
        Assert.assertTrue("Waiting for server down", ClientBase.waitForServerDown("127.0.0.1:"
                + qu.getPeer(2).clientPort, ClientBase.CONNECTION_TIMEOUT));
        
        qu.start(1);
        qu.start(2);
        Assert.assertTrue("Waiting for server up", ClientBase.waitForServerUp("127.0.0.1:"
                + qu.getPeer(1).clientPort, ClientBase.CONNECTION_TIMEOUT));
        Assert.assertTrue("Waiting for server up", ClientBase.waitForServerUp("127.0.0.1:"
                + qu.getPeer(2).clientPort, ClientBase.CONNECTION_TIMEOUT));

        qu.start(3);
        Assert.assertTrue("Waiting for server up", ClientBase.waitForServerUp("127.0.0.1:"
                + qu.getPeer(3).clientPort, ClientBase.CONNECTION_TIMEOUT));

        zk1 = createClient(qu.getPeer(1).peer.getClientPort(), watcher1);
        LOG.info("zk1 has session id 0x{}", Long.toHexString(zk1.getSessionId()));
        
        assertNotNull("zk1 has data", zk1.exists(resyncPath, false));

        final ZooKeeper zk2 =
                createClient(qu.getPeer(2).peer.getClientPort(), watcher2);
        LOG.info("zk2 has session id 0x{}", Long.toHexString(zk2.getSessionId()));

        assertNotNull("zk2 has data", zk2.exists(resyncPath, false));

        final ZooKeeper zk3 =
            createClient(qu.getPeer(3).peer.getClientPort(), watcher3);
        LOG.info("zk3 has session id 0x{}", Long.toHexString(zk3.getSessionId()));

        assertNotNull("zk3 has data", zk3.exists(resyncPath, false));

        zk1.close();
        zk2.close();
        zk3.close();
        
        qu.shutdownAll();
    }      

    /**
     * See ZOOKEEPER-962. This tests for one of the bugs hit while fixing this,
     * setting the ZXID of the SNAP packet
     * Starts up 3 ZKs. Shut down F1, write a node, restart the one that was shut down
     * The non-leader ZKs are writing to cluster
     * Shut down F1 again
     * Restart after sessions are expired, expect to get a snap file
     * Shut down, run some transactions through.
     * Restart to a diff while transactions are running in leader
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    @Test
    public void testResyncBySnapThenDiffAfterFollowerCrashes()
            throws IOException, InterruptedException, KeeperException,  Throwable
    {
        followerResyncCrashTest(false);
    }
    
    /**
     * Same as testResyncBySnapThenDiffAfterFollowerCrashes() but we resync
     * follower using txnlog
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    @Test
    public void testResyncByTxnlogThenDiffAfterFollowerCrashes()
        throws IOException, InterruptedException, KeeperException,  Throwable
    {
        followerResyncCrashTest(true);
    }
    
    public void followerResyncCrashTest(boolean useTxnLogResync)
            throws IOException, InterruptedException, KeeperException,  Throwable
    {
        final Semaphore sem = new Semaphore(0);

        QuorumUtil qu = new QuorumUtil(1);
        qu.startAll();
        CountdownWatcher watcher1 = new CountdownWatcher();
        CountdownWatcher watcher2 = new CountdownWatcher();
        CountdownWatcher watcher3 = new CountdownWatcher();

        int index = 1;
        while(qu.getPeer(index).peer.leader == null) {
            index++;
        }

        Leader leader = qu.getPeer(index).peer.leader;
        assertNotNull(leader);
        
        if (useTxnLogResync) {
            // Set the factor to high value so that this test case always
            // resync using txnlog
            qu.getPeer(index).peer.getActiveServer().getZKDatabase()
                    .setSnapshotSizeFactor(1000);
        } else {
            // Disable sending DIFF using txnlog, so that this test still
            // testing the ZOOKEEPER-962 bug
            qu.getPeer(index).peer.getActiveServer().getZKDatabase()
            .setSnapshotSizeFactor(-1);
        }

        /* Reusing the index variable to select a follower to connect to */
        index = (index == 1) ? 2 : 1;
        LOG.info("Connecting to follower: {}", index);

        qu.shutdown(index);

        final ZooKeeper zk3 =
            createClient(qu.getPeer(3).peer.getClientPort(), watcher3);
        LOG.info("zk3 has session id 0x{}", Long.toHexString(zk3.getSessionId()));

        zk3.create("/mybar", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);

        qu.restart(index);

        final ZooKeeper zk1 =
            createClient(qu.getPeer(index).peer.getClientPort(), watcher1);
        LOG.info("zk1 has session id 0x{}", Long.toHexString(zk1.getSessionId()));

        final ZooKeeper zk2 =
            createClient(qu.getPeer(index).peer.getClientPort(), watcher2);
        LOG.info("zk2 has session id 0x{}", Long.toHexString(zk2.getSessionId()));

        zk1.create("/first", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        
        // Prepare a thread that will create znodes.
        Thread mytestfooThread = new Thread(new Runnable() {
            @Override
            public void run() {
                for(int i = 0; i < 3000; i++) {
                    // Here we create 3000 znodes
                    zk3.create("/mytestfoo", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {

                        @Override
                        public void processResult(int rc, String path, Object ctx, String name) {
                            pending.decrementAndGet();
                            counter.incrementAndGet();
                            if (rc != 0) {
                                errors.incrementAndGet();
                            }
                            if(counter.get() == 16200){
                                sem.release();
                            }
                        }
                    }, null);
                    pending.incrementAndGet();
                    if(i%10==0){
                        try {
                            Thread.sleep(100);
                        } catch (Exception e) {

                        }
                    }
                }

            }
        });

        // Here we start populating the server and shutdown the follower after
        // initial data is written.
        for(int i = 0; i < 13000; i++) {
            // Here we create 13000 znodes
            zk3.create("/mybar", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {

                @Override
                public void processResult(int rc, String path, Object ctx, String name) {
                    pending.decrementAndGet();
                    counter.incrementAndGet();
                    if (rc != 0) {
                        errors.incrementAndGet();
                    }
                    if(counter.get() == 16200){
                        sem.release();
                    }
                }
            }, null);
            pending.incrementAndGet();

            if(i == 5000){
                qu.shutdown(index);
                LOG.info("Shutting down s1");
            }
            if(i == 12000){
                // Start the prepared thread so that it is writing znodes while
                // the follower is restarting. On the first restart, the follow
                // should use txnlog to catchup. For subsequent restart, the
                // follower should use a diff to catchup.
                mytestfooThread.start();
                LOG.info("Restarting follower: {}", index);
                qu.restart(index);
                Thread.sleep(300);
                LOG.info("Shutdown follower: {}", index);
                qu.shutdown(index);
                Thread.sleep(300);
                LOG.info("Restarting follower: {}", index);
                qu.restart(index);
                LOG.info("Setting up server: {}", index);
            }
            if((i % 1000) == 0){
                Thread.sleep(1000);
            }

            if(i%50 == 0) {
                zk2.create("/newbaz", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {
                    @Override
                    public void processResult(int rc, String path, Object ctx, String name) {
                        pending.decrementAndGet();
                        counter.incrementAndGet();
                        if (rc != 0) {
                            errors.incrementAndGet();
                        }
                        if(counter.get() == 16200){
                            sem.release();
                        }
                    }
                }, null);
                pending.incrementAndGet();
            }
        }

        // Wait until all updates return
        if(!sem.tryAcquire(ClientBase.CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)) {
            LOG.warn("Did not aquire semaphore fast enough");
        }
        mytestfooThread.join(ClientBase.CONNECTION_TIMEOUT);
        if (mytestfooThread.isAlive()) {
            LOG.error("mytestfooThread is still alive");
        }
        assertTrue(waitForPendingRequests(60));
        assertTrue(waitForSync(qu, index, 10));

        verifyState(qu, index, leader);
        
        zk1.close();
        zk2.close();
        zk3.close();
        
        qu.shutdownAll();
    }

    /**
     * This test:
     * Starts up 3 ZKs. The non-leader ZKs are writing to cluster
     * Shut down one of the non-leader ZKs.
     * Restart after sessions have expired but <500 txns have taken place (get a diff)
     * Shut down immediately after restarting, start running separate thread with other transactions
     * Restart to a diff while transactions are running in leader
     *
     *
     * Before fixes for ZOOKEEPER-962, restarting off of diff could get an inconsistent view of data missing transactions that
     * completed during diff syncing. Follower would also be considered "restarted" before all forwarded transactions
     * were completely processed, so restarting would cause a snap file with a too-high zxid to be written, and transactions
     * would be missed
     *
     * This test should pretty reliably catch the failure of restarting the server before all diff messages have been processed,
     * however, due to the transient nature of the system it may not catch failures due to concurrent processing of transactions
     * during the leader's diff forwarding.
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     * @throws Throwable
     */

    @Test
    public void testResyncByDiffAfterFollowerCrashes()
        throws IOException, InterruptedException, KeeperException, Throwable
    {
        final Semaphore sem = new Semaphore(0);

        QuorumUtil qu = new QuorumUtil(1);
        qu.startAll();
        CountdownWatcher watcher1 = new CountdownWatcher();
        CountdownWatcher watcher2 = new CountdownWatcher();
        CountdownWatcher watcher3 = new CountdownWatcher();

        int index = 1;
        while(qu.getPeer(index).peer.leader == null) {
            index++;
        }

        Leader leader = qu.getPeer(index).peer.leader;
        assertNotNull(leader);

        /* Reusing the index variable to select a follower to connect to */
        index = (index == 1) ? 2 : 1;
        LOG.info("Connecting to follower: {}", index);

        final ZooKeeper zk1 =
            createClient(qu.getPeer(index).peer.getClientPort(), watcher1);
        LOG.info("zk1 has session id 0x{}", Long.toHexString(zk1.getSessionId()));

        final ZooKeeper zk2 =
            createClient(qu.getPeer(index).peer.getClientPort(), watcher2);
        LOG.info("zk2 has session id 0x{}", Long.toHexString(zk2.getSessionId()));

        final ZooKeeper zk3 =
            createClient(qu.getPeer(3).peer.getClientPort(), watcher3);
        LOG.info("zk3 has session id 0x{}", Long.toHexString(zk3.getSessionId()));

        zk1.create("/first", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk2.create("/mybar", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);

        final AtomicBoolean runNow = new AtomicBoolean(false);
        Thread mytestfooThread = new Thread(new Runnable() {

            @Override
            public void run() {
                int inSyncCounter = 0;
                while(inSyncCounter < 400) {
                    if(runNow.get()) {
                        zk3.create("/mytestfoo", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {

                            @Override
                            public void processResult(int rc, String path, Object ctx, String name) {
                                pending.decrementAndGet();
                                counter.incrementAndGet();
                                if (rc != 0) {
                                    errors.incrementAndGet();;
                                }
                                if(counter.get() > 7300){
                                    sem.release();
                                }
                            }
                        }, null);
                        pending.incrementAndGet();
                        try {
                            Thread.sleep(10);
                        } catch (Exception e) {
                        }
                        inSyncCounter++;
                    } else {
                        Thread.yield();
                    }
                }

            }
        });

        mytestfooThread.start();
        for(int i = 0; i < 5000; i++) {
            zk2.create("/mybar", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {

                @Override
                public void processResult(int rc, String path, Object ctx, String name) {
                    pending.decrementAndGet();
                    counter.incrementAndGet();
                    if (rc != 0) {
                        errors.incrementAndGet();;
                    }
                    if(counter.get() > 7300){
                        sem.release();
                    }
                }
            }, null);
            pending.incrementAndGet();
            if(i == 1000){
                qu.shutdown(index);
                Thread.sleep(1100);
                LOG.info("Shutting down s1");
            }
            if(i == 1100 || i == 1150 || i == 1200) {
                Thread.sleep(1000);
            }

            if(i == 1200){
                qu.startThenShutdown(index);
                runNow.set(true);
                qu.restart(index);
                LOG.info("Setting up server: {}", index);
            }

            if(i>=1000 &&  i%2== 0) {
                zk3.create("/newbaz", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {

                    @Override
                    public void processResult(int rc, String path, Object ctx, String name) {
                        pending.decrementAndGet();
                        counter.incrementAndGet();
                        if (rc != 0) {
                            errors.incrementAndGet();
                        }
                        if(counter.get() > 7300){
                            sem.release();
                        }
                    }
                }, null);
                pending.incrementAndGet();
            }
            if(i == 1050 || i == 1100 || i == 1150) {
                Thread.sleep(1000);
            }
        }

        // Wait until all updates return
        if(!sem.tryAcquire(ClientBase.CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)) {
            LOG.warn("Did not aquire semaphore fast enough");
        }
        mytestfooThread.join(ClientBase.CONNECTION_TIMEOUT);
        if (mytestfooThread.isAlive()) {
            LOG.error("mytestfooThread is still alive");
        }

        assertTrue(waitForPendingRequests(60));
        assertTrue(waitForSync(qu, index, 10));
        // Verify that server is following and has the same epoch as the leader

        verifyState(qu, index, leader);

        zk1.close();
        zk2.close();
        zk3.close();
        
        qu.shutdownAll();
    }

    private static DisconnectableZooKeeper createClient(int port,
            CountdownWatcher watcher)
        throws IOException, TimeoutException, InterruptedException
    {
        DisconnectableZooKeeper zk = new DisconnectableZooKeeper(
                "127.0.0.1:" + port, ClientBase.CONNECTION_TIMEOUT, watcher);

        watcher.waitForConnected(CONNECTION_TIMEOUT);
        return zk;
    }
    
    /**
     * Wait for all async operation to return. So we know that we can start
     * verifying the state
     */
    private boolean waitForPendingRequests(int timeout) throws InterruptedException {
        LOG.info("Wait for pending requests: {}", pending.get());
        for (int i = 0; i < timeout; ++i) {
            Thread.sleep(1000);
            if (pending.get() == 0) {
                return true;
            }
        }
        LOG.info("Timeout waiting for pending requests: {}", pending.get());
        return false;
    }

    /**
     * Wait for all server to have the same lastProccessedZxid. Timeout in seconds
     */
    private boolean waitForSync(QuorumUtil qu, int index, int timeout) throws InterruptedException{
        LOG.info("Wait for server to sync");
        int leaderIndex = (index == 1) ? 2 : 1;
        ZKDatabase restartedDb = qu.getPeer(index).peer.getActiveServer().getZKDatabase();
        ZKDatabase cleanDb =  qu.getPeer(3).peer.getActiveServer().getZKDatabase();
        ZKDatabase leadDb = qu.getPeer(leaderIndex).peer.getActiveServer().getZKDatabase();
        long leadZxid = 0;
        long cleanZxid = 0;
        long restartedZxid = 0;
        for (int i = 0; i < timeout; ++i) {
            leadZxid = leadDb.getDataTreeLastProcessedZxid();
            cleanZxid = cleanDb.getDataTreeLastProcessedZxid();
            restartedZxid = restartedDb.getDataTreeLastProcessedZxid();
            if (leadZxid == cleanZxid && leadZxid == restartedZxid) {
                return true;
            }
            Thread.sleep(1000);
        }
        LOG.info("Timeout waiting for zxid to sync: leader 0x{}" +
                 "clean 0x{}" +
                 "restarted 0x{}", Long.toHexString(leadZxid), Long.toHexString(cleanZxid),
                Long.toHexString(restartedZxid));
        return false;
    }

    private static TestableZooKeeper createTestableClient(String hp)
        throws IOException, TimeoutException, InterruptedException
    {
        CountdownWatcher watcher = new CountdownWatcher();
        return createTestableClient(watcher, hp);
    }

    private static TestableZooKeeper createTestableClient(
            CountdownWatcher watcher, String hp)
            throws IOException, TimeoutException, InterruptedException
        {
            TestableZooKeeper zk = new TestableZooKeeper(
                    hp, ClientBase.CONNECTION_TIMEOUT, watcher);

            watcher.waitForConnected(CONNECTION_TIMEOUT);
            return zk;
        }

    private void verifyState(QuorumUtil qu, int index, Leader leader) {
        LOG.info("Verifying state");
        assertTrue("Not following", qu.getPeer(index).peer.follower != null);
        long epochF = (qu.getPeer(index).peer.getActiveServer().getZxid() >> 32L);
        long epochL = (leader.getEpoch() >> 32L);
        assertTrue("Zxid: " + qu.getPeer(index).peer.getActiveServer().getZKDatabase().getDataTreeLastProcessedZxid() +
                "Current epoch: " + epochF, epochF == epochL);
        int leaderIndex = (index == 1) ? 2 : 1;
        Collection<Long> sessionsRestarted = qu.getPeer(index).peer.getActiveServer().getZKDatabase().getSessions();
        Collection<Long> sessionsNotRestarted = qu.getPeer(leaderIndex).peer.getActiveServer().getZKDatabase().getSessions();

        for(Long l : sessionsRestarted) {
            assertTrue("Should have same set of sessions in both servers, did not expect: " + l, sessionsNotRestarted.contains(l));
        }
        assertEquals("Should have same number of sessions", sessionsNotRestarted.size(), sessionsRestarted.size());
        ZKDatabase restarted = qu.getPeer(index).peer.getActiveServer().getZKDatabase();
        ZKDatabase clean =  qu.getPeer(3).peer.getActiveServer().getZKDatabase();
        ZKDatabase lead = qu.getPeer(leaderIndex).peer.getActiveServer().getZKDatabase();
        for(Long l : sessionsRestarted) {
            LOG.info("Validating ephemeral for session id 0x{}", Long.toHexString(l));
            assertTrue("Should have same set of sessions in both servers, did not expect: " + l, sessionsNotRestarted.contains(l));
            Set<String> ephemerals = restarted.getEphemerals(l);
            Set<String> cleanEphemerals = clean.getEphemerals(l);
            for(String o : cleanEphemerals) {
                if(!ephemerals.contains(o)) {
                    LOG.info("Restarted follower doesn't contain ephemeral {} zxid 0x{}",
                            o, Long.toHexString(clean.getDataTree().getNode(o).stat.getMzxid()));
                }
            }
            for(String o : ephemerals) {
                if(!cleanEphemerals.contains(o)) {
                    LOG.info("Restarted follower has extra ephemeral {} zxid 0x{}",
                            o, Long.toHexString(restarted.getDataTree().getNode(o).stat.getMzxid()));
                }
            }
            Set<String> leadEphemerals = lead.getEphemerals(l);
            for(String o : leadEphemerals) {
                if(!cleanEphemerals.contains(o)) {
                    LOG.info("Follower doesn't contain ephemeral from leader {} zxid 0x{}",
                            o, Long.toHexString(lead.getDataTree().getNode(o).stat.getMzxid()));
                }
            }
            for(String o : cleanEphemerals) {
                if(!leadEphemerals.contains(o)) {
                    LOG.info("Leader doesn't contain ephemeral from follower {} zxid 0x{}",
                            o, Long.toHexString(clean.getDataTree().getNode(o).stat.getMzxid()));
                }
            }
            assertEquals("Should have same number of ephemerals in both followers", ephemerals.size(), cleanEphemerals.size());
            assertEquals("Leader should equal follower", lead.getEphemerals(l).size(), cleanEphemerals.size());
        }
    }      

    /**
     * Verify that the server is sending the proper zxid. See ZOOKEEPER-1412.
     */
    @Test
    public void testFollowerSendsLastZxid() throws Exception {
        QuorumUtil qu = new QuorumUtil(1);
        qu.startAll();

        int index = 1;
        while(qu.getPeer(index).peer.follower == null) {
            index++;
        }
        LOG.info("Connecting to follower: {}", index);

        TestableZooKeeper zk =
                createTestableClient("localhost:" + qu.getPeer(index).peer.getClientPort());

        assertEquals(0L, zk.testableLastZxid());
        zk.exists("/", false);
        long lzxid = zk.testableLastZxid();
        assertTrue("lzxid:" + lzxid + " > 0", lzxid > 0);
        zk.close();
        qu.shutdownAll();
    }

    private class MyWatcher extends CountdownWatcher {
        LinkedBlockingQueue<WatchedEvent> events =
            new LinkedBlockingQueue<WatchedEvent>();

        public void process(WatchedEvent event) {
            super.process(event);
            if (event.getType() != Event.EventType.None) {
                try {
                    events.put(event);
                } catch (InterruptedException e) {
                    LOG.warn("ignoring interrupt during event.put");
                }
            }
        }
    }

    /**
     * Verify that the server is sending the proper zxid, and as a result
     * the watch doesn't fire. See ZOOKEEPER-1412.
     */
    @Test
    public void testFollowerWatcherResync() throws Exception {
        QuorumUtil qu = new QuorumUtil(1);
        qu.startAll();

        int index = 1;
        while(qu.getPeer(index).peer.follower == null) {
            index++;
        }
        LOG.info("Connecting to follower: {}", index);

        TestableZooKeeper zk1 = createTestableClient(
                "localhost:" + qu.getPeer(index).peer.getClientPort());
        zk1.create("/foo", "foo".getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);

        MyWatcher watcher = new MyWatcher();
        TestableZooKeeper zk2 = createTestableClient(watcher,
                "localhost:" + qu.getPeer(index).peer.getClientPort());

        zk2.exists("/foo", true);

        watcher.reset();
        zk2.testableConnloss();
        if (!watcher.clientConnected.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS))
        {
            fail("Unable to connect to server");
        }
        assertArrayEquals("foo".getBytes(), zk2.getData("/foo", false, null));

        assertNull(watcher.events.poll(5, TimeUnit.SECONDS));

        zk1.close();
        zk2.close();
        qu.shutdownAll();
    }

}
