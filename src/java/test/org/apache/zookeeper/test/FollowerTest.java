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
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.LearnerHandler;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FollowerTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(FollowerTest.class);
    public static final long CONNECTION_TIMEOUT = ClientTest.CONNECTION_TIMEOUT;

    volatile int counter = 0;
    volatile int errors = 0;

    /** 
     * See ZOOKEEPER-790 for details 
     * */
    @Test
    public void testFollowersStartAfterLeader() throws Exception {
        QuorumUtil qu = new QuorumUtil(1);
        CountdownWatcher watcher = new CountdownWatcher();
        qu.startQuorum();
        
        int index = 1;
        while(qu.getPeer(index).peer.leader == null) {
            index++;
        }
        
        ZooKeeper zk = new ZooKeeper(
                "127.0.0.1:" + qu.getPeer((index == 1)?2:1).peer.getClientPort(),
                ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        
        // break the quorum
        qu.shutdown(index);

        // Wait until we disconnect to proceed
        watcher.waitForDisconnected(CONNECTION_TIMEOUT);
        
        // try to reestablish the quorum
        qu.start(index);

        try{
            watcher.waitForConnected(30000);      
        } catch(TimeoutException e) {
            Assert.fail("client could not connect to reestablished quorum: giving up after 30+ seconds.");
        }

        zk.close();

        qu.tearDown();
    }

    /**
     * Tests if closeSession can be logged before a leader gets established, which
     * could lead to a locked-out follower (see ZOOKEEPER-790). 
     * 
     * The test works as follows. It has a client connecting to a follower f and
     * sending batches of 1,000 updates. The goal is that f has a zxid higher than
     * all other servers in the initial leader election. This way we can crash and
     * recover the follower so that the follower believes it is the leader once it
     * recovers (LE optimization: once a server receives a message from all other 
     * servers, it picks a leader.
     * 
     * It also makes the session timeout very short so that we force the false 
     * leader to close the session and write it to the log in the buggy code (before 
     * ZOOKEEPER-790). Once f drops leadership and finds the current leader, its epoch
     * is higher, and it rejects the leader. Now, if we prevent the leader from closing
     * the session by only starting up (see Leader.lead()) once it obtains a quorum of 
     * supporters, then f will find the current leader and support it because it won't
     * have a highe epoch.
     * 
     */
    @Test
    public void testNoLogBeforeLeaderEstablishment () 
    throws Exception {
        final Semaphore sem = new Semaphore(0);
        System.setProperty("zookeeper.cnxTimeout", "50");
                
        QuorumUtil qu = new QuorumUtil(2, 10);
        qu.startQuorum();
                
        
        int index = 1;
        while(qu.getPeer(index).peer.leader == null) {
            index++;
        }

        Leader leader = qu.getPeer(index).peer.leader;
        
        Assert.assertNotNull(leader);
  
        /*
         * Reusing the index variable to select a follower to connect to
         */
        index = (index == 1) ? 2 : 1;
        
        ZooKeeper zk = new DisconnectableZooKeeper(
                "127.0.0.1:" + qu.getPeer(index).peer.getClientPort(),
                ClientBase.CONNECTION_TIMEOUT, new Watcher() {
            public void process(WatchedEvent event) { }
          });

        zk.create("/blah", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);      
        
        for(int i = 0; i < 50000; i++) {
            zk.setData("/blah", new byte[0], -1, new AsyncCallback.StatCallback() {
                public void processResult(int rc, String path, Object ctx,
                        Stat stat) {
                    counter++;
                    if (rc != 0) {
                        errors++;
                    }
                    if(counter == 20000){
                        sem.release();
                    }
                }
            }, null);
            
            if(i == 5000){
                qu.shutdown(index);
                LOG.info("Shutting down s1");
            }
            if(i == 12000){
                qu.start(index);
                LOG.info("Setting up server: " + index);
            }
            if((i % 1000) == 0){
                Thread.sleep(500);
            }
        }

        // Wait until all updates return
        sem.tryAcquire(15, TimeUnit.SECONDS);
        
        // Verify that server is following and has the same epoch as the leader
        Assert.assertTrue("Not following", qu.getPeer(index).peer.follower != null);
        long epochF = (qu.getPeer(index).peer.getActiveServer().getZxid() >> 32L);
        long epochL = (leader.getEpoch() >> 32L);
        Assert.assertTrue("Zxid: " + qu.getPeer(index).peer.getActiveServer().getZxid() + 
                "Current epoch: " + epochF, epochF == epochL);

        qu.tearDown();
        
    }

    // skip superhammer and clientcleanup as they are too expensive for quorum
    
    /**
     * Tests if a multiop submitted to a non-leader propagates to the leader properly
     * (see ZOOKEEPER-1124).
     * 
     * The test works as follows. It has a client connect to a follower and submit a multiop
     * to the follower. It then verifies that the multiop successfully gets committed by the leader.
     *
     * Without the fix in ZOOKEEPER-1124, this fails with a ConnectionLoss KeeperException.
     */
    @Test
    public void testMultiToFollower() throws Exception {
        QuorumUtil qu = new QuorumUtil(1);
        CountdownWatcher watcher = new CountdownWatcher();
        qu.startQuorum();
        
        int index = 1;
        while(qu.getPeer(index).peer.leader == null) {
            index++;
        }
        
        ZooKeeper zk = new ZooKeeper(
                "127.0.0.1:" + qu.getPeer((index == 1)?2:1).peer.getClientPort(),
                ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        
        List<OpResult> results = new ArrayList<OpResult>();

        results = zk.multi(Arrays.asList(
                Op.create("/multi0", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi2", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                ));
        zk.getData("/multi0", false, null);
        zk.getData("/multi1", false, null);
        zk.getData("/multi2", false, null);

        zk.close();

        qu.tearDown();
    }
}
