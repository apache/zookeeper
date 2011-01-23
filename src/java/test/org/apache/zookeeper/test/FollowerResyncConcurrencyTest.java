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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.Test;


public class FollowerResyncConcurrencyTest extends QuorumBase {
    volatile int counter = 0;
    volatile int errors = 0; 

    private static final Logger LOG = Logger.getLogger(FollowerResyncConcurrencyTest.class);
    public static final long CONNECTION_TIMEOUT = ClientTest.CONNECTION_TIMEOUT;


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
    public void testResyncBySnapThenDiffAfterFollowerCrashes () 
    throws IOException, InterruptedException, KeeperException,  Throwable{
        final Semaphore sem = new Semaphore(0);

        QuorumUtil qu = new QuorumUtil(1);
        qu.startAll();
        CountdownWatcher watcher1 = new CountdownWatcher();
        CountdownWatcher watcher2 = new CountdownWatcher();
        CountdownWatcher watcher3 = new CountdownWatcher();

        int index = 1;
        while(qu.getPeer(index).peer.leader == null)
            index++;

        Leader leader = qu.getPeer(index).peer.leader;

        assertNotNull(leader);    
        /*
         * Reusing the index variable to select a follower to connect to
         */
        index = (index == 1) ? 2 : 1;
        qu.shutdown(index);
        final ZooKeeper zk3 = new DisconnectableZooKeeper("127.0.0.1:" + qu.getPeer(3).peer.getClientPort(), 1000,watcher3);
        watcher3.waitForConnected(CONNECTION_TIMEOUT);
        zk3.create("/mybar", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);

        qu.restart(index);
        ZooKeeper zk = new DisconnectableZooKeeper("127.0.0.1:" + qu.getPeer(index).peer.getClientPort(), 1000, watcher1);

        ZooKeeper zk2 = new DisconnectableZooKeeper("127.0.0.1:" + qu.getPeer(index).peer.getClientPort(), 1000, watcher2);
    
        watcher1.waitForConnected(CONNECTION_TIMEOUT);
        watcher2.waitForConnected(CONNECTION_TIMEOUT);
        
        zk.create("/first", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);      
        Thread t = new Thread(new Runnable() {

            @Override
            public void run() {
                for(int i = 0; i < 1000; i++) {
                    zk3.create("/mytestfoo", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {

                        @Override
                        public void processResult(int rc, String path, Object ctx, String name) {
                            counter++;
                            if (rc != 0) {
                                errors++;
                            }
                            if(counter == 14200){
                                sem.release();
                            }


                        }
                    }, null);
                    if(i%10==0){
                        try {
                            Thread.sleep(100);
                        } catch (Exception e) {

                        }
                    }
                }

            }
        });

        
        for(int i = 0; i < 13000; i++) {
            zk3.create("/mybar", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {

                @Override
                public void processResult(int rc, String path, Object ctx, String name) {
                    counter++;
                    if (rc != 0) {
                        errors++;
                    }
                    if(counter == 14200){
                        sem.release();
                    }


                }
            }, null);            

            if(i == 5000){
                qu.shutdown(index);               
                LOG.info("Shutting down s1");
            }
            if(i == 12000){
                //Restart off of snap, then get some txns for a log, then shut down
                qu.restart(index);       
                Thread.sleep(300);
                qu.shutdown(index);
                t.start();
                Thread.sleep(300);                
                qu.restart(index);
                LOG.info("Setting up server: " + index);
            }
            if((i % 1000) == 0){
                Thread.sleep(1000);
            }

            if(i%50 == 0) {
                zk2.create("/newbaz", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {

                    @Override
                    public void processResult(int rc, String path, Object ctx, String name) {
                        counter++;
                        if (rc != 0) {
                            errors++;
                        }
                        if(counter == 14200){
                            sem.release();
                        }


                    }
                }, null);
            }
        }

        // Wait until all updates return
        if(!sem.tryAcquire(20000, TimeUnit.MILLISECONDS)) {
            LOG.warn("Did not aquire semaphore fast enough");
        }
        t.join(10000);
        Thread.sleep(1000);
        
            verifyState(qu, index, leader);
        
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
    public void testResyncByDiffAfterFollowerCrashes () 
    throws IOException, InterruptedException, KeeperException, Throwable{
        final Semaphore sem = new Semaphore(0);

        QuorumUtil qu = new QuorumUtil(1);
        qu.startAll();
        CountdownWatcher watcher1 = new CountdownWatcher();
        CountdownWatcher watcher2 = new CountdownWatcher();
        CountdownWatcher watcher3 = new CountdownWatcher();


        int index = 1;
        while(qu.getPeer(index).peer.leader == null)
            index++;

        Leader leader = qu.getPeer(index).peer.leader;

        assertNotNull(leader);

        /*
         * Reusing the index variable to select a follower to connect to
         */
        index = (index == 1) ? 2 : 1;

        ZooKeeper zk = new DisconnectableZooKeeper("127.0.0.1:" + qu.getPeer(index).peer.getClientPort(), 1000, watcher1);

        ZooKeeper zk2 = new DisconnectableZooKeeper("127.0.0.1:" + qu.getPeer(index).peer.getClientPort(), 1000,watcher2);
        final ZooKeeper zk3 = new DisconnectableZooKeeper("127.0.0.1:" + qu.getPeer(3).peer.getClientPort(), 1000, watcher3);
        watcher1.waitForConnected(CONNECTION_TIMEOUT);
        watcher2.waitForConnected(CONNECTION_TIMEOUT);
        watcher3.waitForConnected(CONNECTION_TIMEOUT);
        zk.create("/first", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);      
        zk2.create("/mybar", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);

        
        final AtomicBoolean runNow = new AtomicBoolean(false);
        Thread t = new Thread(new Runnable() {

            @Override
            public void run() {                                
                int inSyncCounter = 0;
                while(inSyncCounter < 400) {    
                    if(runNow.get()) {
                        zk3.create("/mytestfoo", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {

                            @Override
                            public void processResult(int rc, String path, Object ctx, String name) {
                                counter++;
                                if (rc != 0) {
                                    errors++;
                                }
                                if(counter > 7300){
                                    sem.release();
                                }


                            }
                        }, null);
                        
                        try {
                            Thread.sleep(10);
                        } catch (Exception e) {
                        }
                        inSyncCounter++;
                    }
                    else {
                        Thread.yield();
                    }
                }

            }
        });

        t.start();
        for(int i = 0; i < 5000; i++) {
            zk2.create("/mybar", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {

                @Override
                public void processResult(int rc, String path, Object ctx, String name) {
                    counter++;
                    if (rc != 0) {
                        errors++;
                    }
                    if(counter > 7300){
                        sem.release();
                    }


                }
            }, null);            

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
                LOG.info("Setting up server: " + index);
            }
        

            if(i>=1000 &&  i%2== 0) {
                zk3.create("/newbaz", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL, new AsyncCallback.StringCallback() {

                    @Override
                    public void processResult(int rc, String path, Object ctx, String name) {
                        counter++;
                        if (rc != 0) {
                            errors++;
                        }
                        if(counter > 7300){
                            sem.release();
                        }


                    }
                }, null);
            }
            if(i == 1050 || i == 1100 || i == 1150) {
                Thread.sleep(1000);
            }
        }

        // Wait until all updates return
        if(!sem.tryAcquire(15000, TimeUnit.MILLISECONDS)) {
            LOG.warn("Did not aquire semaphore fast enough");
        }
        t.join(10000);
        Thread.sleep(1000);
        // Verify that server is following and has the same epoch as the leader
        
        verifyState(qu, index, leader);
        
    }

    private void verifyState(QuorumUtil qu, int index, Leader leader) {
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
            assertTrue("Should have same set of sessions in both servers, did not expect: " + l, sessionsNotRestarted.contains(l));
            HashSet ephemerals = restarted.getEphemerals(l);
            HashSet cleanEphemerals = clean.getEphemerals(l);
            for(Object o : cleanEphemerals) {
                if(!ephemerals.contains(o)) {
                    LOG.info("Restarted follower doesn't contain ephemeral " + o);
                }
            }
            HashSet leadEphemerals = lead.getEphemerals(l);
            for(Object o : leadEphemerals) {
                if(!cleanEphemerals.contains(o)) {
                    LOG.info("Follower doesn't contain ephemeral from leader " + o);
                }
            }
            assertEquals("Should have same number of ephemerals in both followers", ephemerals.size(), cleanEphemerals.size());            
            assertEquals("Leader should equal follower", lead.getEphemerals(l).size(), cleanEphemerals.size());
        }
    }      
}
