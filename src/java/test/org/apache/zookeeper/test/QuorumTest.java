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

import org.apache.log4j.Logger;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.LearnerHandler;
import org.junit.Before;
import org.junit.Test;

public class QuorumTest extends QuorumBase {
    private static final Logger LOG = Logger.getLogger(QuorumTest.class);
    public static final long CONNECTION_TIMEOUT = ClientTest.CONNECTION_TIMEOUT;

    private final QuorumBase qb = new QuorumBase();
    private final ClientTest ct = new ClientTest();

    @Before
    @Override
    protected void setUp() throws Exception {
        qb.setUp();
        ct.hostPort = qb.hostPort;
        ct.setUpAll();
    }

    protected void tearDown() throws Exception {
        ct.tearDownAll();
        qb.tearDown();
    }
    
    @Test
    public void testDeleteWithChildren() throws Exception {
        ct.testDeleteWithChildren();
    }

    @Test
    public void testPing() throws Exception {
        ct.testPing();
    }

    @Test
    public void testSequentialNodeNames()
        throws IOException, InterruptedException, KeeperException
    {
        ct.testSequentialNodeNames();
    }

    @Test
    public void testACLs() throws Exception {
        ct.testACLs();
    }

    @Test
    public void testClientwithoutWatcherObj() throws IOException,
            InterruptedException, KeeperException
    {
        ct.testClientwithoutWatcherObj();
    }

    @Test
    public void testClientWithWatcherObj() throws IOException,
            InterruptedException, KeeperException
    {
        ct.testClientWithWatcherObj();
    }
    
    @Test
    public void testGetView() {                
        ct.assertEquals(5,qb.s1.getView().size());        
        ct.assertEquals(5,qb.s2.getView().size());        
        ct.assertEquals(5,qb.s3.getView().size());        
        ct.assertEquals(5,qb.s4.getView().size());
        ct.assertEquals(5,qb.s5.getView().size());
    }
    
    @Test
    public void testViewContains() {
        // Test view contains self
        ct.assertTrue(qb.s1.viewContains(qb.s1.getId()));
        
        // Test view contains other servers
        ct.assertTrue(qb.s1.viewContains(qb.s2.getId()));
        
        // Test view does not contain non-existant servers
        ct.assertFalse(qb.s1.viewContains(-1L));
    }
    
    volatile int counter = 0;
    volatile int errors = 0;
    @Test
    public void testLeaderShutdown() throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = new DisconnectableZooKeeper(qb.hostPort, ClientBase.CONNECTION_TIMEOUT, new Watcher() {
            public void process(WatchedEvent event) {
        }});
        zk.create("/blah", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/blah/blah", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        Leader leader = qb.s1.leader;
        if (leader == null) leader = qb.s2.leader;
        if (leader == null) leader = qb.s3.leader;
        if (leader == null) leader = qb.s4.leader;
        if (leader == null) leader = qb.s5.leader;
        assertNotNull(leader);
        for(int i = 0; i < 5000; i++) {
            zk.setData("/blah/blah", new byte[0], -1, new AsyncCallback.StatCallback() {
                public void processResult(int rc, String path, Object ctx,
                        Stat stat) {
                    counter++;
                    if (rc != 0) {
                        errors++;
                    }
                }
            }, null);
        }
        ArrayList<LearnerHandler> fhs = new ArrayList<LearnerHandler>(leader.forwardingFollowers);
        for(LearnerHandler f: fhs) {
            f.getSocket().shutdownInput();
        }
        for(int i = 0; i < 5000; i++) {
            zk.setData("/blah/blah", new byte[0], -1, new AsyncCallback.StatCallback() {
                public void processResult(int rc, String path, Object ctx,
                        Stat stat) {
                    counter++;
                    if (rc != 0) {
                        errors++;
                    }
                }
            }, null);
        }
        // check if all the followers are alive
        assertTrue(qb.s1.isAlive());
        assertTrue(qb.s2.isAlive());
        assertTrue(qb.s3.isAlive());
        assertTrue(qb.s4.isAlive());
        assertTrue(qb.s5.isAlive());
        zk.close();
    }
    
    @Test
    public void testMultipleWatcherObjs() throws IOException,
            InterruptedException, KeeperException
    {
        ct.testMutipleWatcherObjs();
    }
	
    /**
     * Make sure that we can change sessions 
     *  from follower to leader.
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    @Test
    public void testSessionMoved() throws IOException, InterruptedException, KeeperException {
        String hostPorts[] = qb.hostPort.split(",");
        DisconnectableZooKeeper zk = new DisconnectableZooKeeper(hostPorts[0], ClientBase.CONNECTION_TIMEOUT, new Watcher() {
            public void process(WatchedEvent event) {
            }});
        zk.create("/sessionMoveTest", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        // we want to loop through the list twice
        for(int i = 0; i < hostPorts.length*2; i++) {
            // This should stomp the zk handle
            DisconnectableZooKeeper zknew = new DisconnectableZooKeeper(hostPorts[(i+1)%hostPorts.length], ClientBase.CONNECTION_TIMEOUT, 
                    new Watcher() {public void process(WatchedEvent event) {
                    }},
                    zk.getSessionId(),
                    zk.getSessionPasswd());
            zknew.setData("/", new byte[1], -1);
            try {
                zk.setData("/", new byte[1], -1);
                fail("Should have lost the connection");
            } catch(KeeperException.ConnectionLossException e) {
            }
            zk.disconnect(); // close w/o closing session
            zk = zknew;
        }
        zk.close();
    }

    private static class DiscoWatcher implements Watcher {
        volatile boolean zkDisco = false;
        public void process(WatchedEvent event) {
            if (event.getState() == KeeperState.Disconnected) {
                zkDisco = true;
            }
        }
    }

    @Test
    /**
     * Connect to two different servers with two different handles using the same session and
     * make sure we cannot do any changes.
     */
    public void testSessionMove() throws IOException, InterruptedException, KeeperException {
        String hps[] = qb.hostPort.split(",");
        DiscoWatcher oldWatcher = new DiscoWatcher();
        ZooKeeper zk = new DisconnectableZooKeeper(hps[0],
                ClientBase.CONNECTION_TIMEOUT, oldWatcher);
        zk.create("/t1", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        // This should stomp the zk handle
        DiscoWatcher watcher = new DiscoWatcher();
        ZooKeeper zknew = new DisconnectableZooKeeper(hps[1],
                ClientBase.CONNECTION_TIMEOUT, watcher, zk.getSessionId(),
                zk.getSessionPasswd());
        zknew.create("/t2", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        try {
            zk.create("/t3", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            fail("Should have lost the connection");
        } catch(KeeperException.ConnectionLossException e) {
            // wait up to 30 seconds for the disco to be delivered
            for (int i = 0; i < 30; i++) {
                if (oldWatcher.zkDisco) {
                    break;
                }
                Thread.sleep(1000);
            }
            assertTrue(oldWatcher.zkDisco);
        }

        ArrayList<ZooKeeper> toClose = new ArrayList<ZooKeeper>();
        toClose.add(zknew);
        // Let's just make sure it can still move
        for(int i = 0; i < 10; i++) {
            zknew = new DisconnectableZooKeeper(hps[1],
                    ClientBase.CONNECTION_TIMEOUT, new DiscoWatcher(),
                    zk.getSessionId(), zk.getSessionPasswd());
            toClose.add(zknew);
            zknew.create("/t-"+i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        }
        for (ZooKeeper z: toClose) {
            z.close();
        }
        zk.close();
    }
	// skip superhammer and clientcleanup as they are too expensive for quorum
}
