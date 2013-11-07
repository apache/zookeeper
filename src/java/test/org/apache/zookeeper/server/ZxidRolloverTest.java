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

package org.apache.zookeeper.server;

import java.io.IOException;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.apache.zookeeper.test.ClientTest;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verify ZOOKEEPER-1277 - ensure that we handle epoch rollover correctly.
 */
public class ZxidRolloverTest extends TestCase {
    private static final Logger LOG = Logger.getLogger(ZxidRolloverTest.class);

    private QuorumUtil qu;
    private ZooKeeperServer zksLeader;
    private ZooKeeper[] zkClients = new ZooKeeper[3];
    private CountdownWatcher[] zkClientWatchers = new CountdownWatcher[3];
    private int idxLeader;
    private int idxFollower;
    
    private ZooKeeper getClient(int idx) {
        return zkClients[idx-1];
    }

    @Override
    protected void setUp() throws Exception {
        LOG.info("STARTING " + getName());

        // set the snap count to something low so that we force log rollover
        // and verify that is working as part of the epoch rollover.
        SyncRequestProcessor.setSnapCount(7);

        qu = new QuorumUtil(1);
        startAll();

        for (int i = 0; i < zkClients.length; i++) {
            zkClientWatchers[i] = new CountdownWatcher();
            int followerPort = qu.getPeer(i+1).peer.getClientPort();
            zkClients[i] = new ZooKeeper(
                    "127.0.0.1:" + followerPort,
                    ClientTest.CONNECTION_TIMEOUT, zkClientWatchers[i]);
        }
        waitForClients();
    }
    
    private void waitForClients() throws Exception {
        for (int i = 0; i < zkClients.length; i++) {
            zkClientWatchers[i].waitForConnected(ClientTest.CONNECTION_TIMEOUT);
            zkClientWatchers[i].reset();
        }
    }

    private void startAll() throws IOException {
        qu.startAll();
        checkLeader();
    }
    private void start(int idx) throws IOException {
        qu.start(idx);
        for (String hp : qu.getConnString().split(",")) {
            Assert.assertTrue("waiting for server up", ClientBase.waitForServerUp(hp,
                    ClientTest.CONNECTION_TIMEOUT));
        }

        checkLeader();
    }

    private void checkLeader() {
        idxLeader = 1;
        while(qu.getPeer(idxLeader).peer.leader == null) {
            idxLeader++;
        }
        idxFollower = (idxLeader == 1 ? 2 : 1);

        zksLeader = qu.getPeer(idxLeader).peer.getActiveServer();
    }

    private void shutdownAll() throws IOException {
        qu.shutdownAll();
    }
    
    private void shutdown(int idx) throws IOException {
        qu.shutdown(idx);
    }

    /** Reset the next zxid to be near epoch end */
    private void adjustEpochNearEnd() {
        zksLeader.setZxid((zksLeader.getZxid() & 0xffffffff00000000L) | 0xfffffffcL);
    }

    @Override
    protected void tearDown() throws Exception {
        LOG.info("tearDown starting");
        for (int i = 0; i < zkClients.length; i++) {
            zkClients[i].close();
        }
        qu.shutdownAll();
    }

    /**
     * Create the znodes, this may fail if the lower 32 roll over, if so
     * wait for the clients to be re-connected after the re-election
     */
    private int createNodes(ZooKeeper zk, int start, int count) throws Exception {
        LOG.info("Creating nodes " + start + " thru " + (start + count));
        int j = 0;
        try {
            for (int i = start; i < start + count; i++) {
                zk.create("/foo" + i, new byte[0], Ids.READ_ACL_UNSAFE,
                        CreateMode.EPHEMERAL);
                j++;
            }
        } catch (ConnectionLossException e) {
            // this is ok - the leader has dropped leadership
            waitForClients();
        }
        return j;
    }
    /**
     * Verify the expected znodes were created and that the last znode, which
     * caused the roll-over, did not.
     */
    private void checkNodes(ZooKeeper zk, int start, int count) throws Exception {
        LOG.info("Validating nodes " + start + " thru " + (start + count));
        for (int i = start; i < start + count; i++) {
            assertNotNull(zk.exists("/foo" + i, false));
            LOG.error("Exists zxid:" + Long.toHexString(zk.exists("/foo" + i, false).getCzxid()));
        }
        assertNull(zk.exists("/foo" + (start + count), false));
    }

    /**
     * Prior to the fix this test would hang for a while, then fail with
     * connection loss.
     */
    @Test
    public void testSimpleRolloverFollower() throws Exception {
        adjustEpochNearEnd();

        ZooKeeper zk = getClient((idxLeader == 1 ? 2 : 1));
        int countCreated = createNodes(zk, 0, 10);
        
        checkNodes(zk, 0, countCreated);
    }

    /**
     * Similar to testSimpleRollover, but ensure the cluster comes back,
     * has the right data, and is able to serve new requests.
     */
    @Test
    public void testRolloverThenRestart() throws Exception {
        ZooKeeper zk = getClient(idxFollower);
        
        int countCreated = createNodes(zk, 0, 10);

        adjustEpochNearEnd();
        
        countCreated += createNodes(zk, countCreated, 10);

        shutdownAll();
        startAll();
        zk = getClient(idxLeader);

        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        adjustEpochNearEnd();
        
        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        shutdownAll();
        startAll();
        zk = getClient(idxFollower);

        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        shutdownAll();
        startAll();
        zk = getClient(idxLeader);

        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        // sanity check
        assertTrue(countCreated > 0);
        assertTrue(countCreated < 60);
    }

    /**
     * Similar to testRolloverThenRestart, but ensure a follower comes back,
     * has the right data, and is able to serve new requests.
     */
    @Test
    public void testRolloverThenFollowerRestart() throws Exception {
        ZooKeeper zk = getClient(idxFollower);

        int countCreated = createNodes(zk, 0, 10);

        adjustEpochNearEnd();
        
        countCreated += createNodes(zk, countCreated, 10);

        shutdown(idxFollower);
        start(idxFollower);

        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        adjustEpochNearEnd();
        
        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        shutdown(idxFollower);
        start(idxFollower);

        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        shutdown(idxFollower);
        start(idxFollower);

        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        // sanity check
        assertTrue(countCreated > 0);
        assertTrue(countCreated < 60);
    }

    /**
     * Similar to testRolloverThenRestart, but ensure leadership can change,
     * comes back, has the right data, and is able to serve new requests.
     */
    @Test
    public void testRolloverThenLeaderRestart() throws Exception {
        ZooKeeper zk = getClient(idxLeader);

        int countCreated = createNodes(zk, 0, 10);

        adjustEpochNearEnd();
        
        checkNodes(zk, 0, countCreated);

        shutdown(idxLeader);
        start(idxLeader);
        zk = getClient(idxLeader);

        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        adjustEpochNearEnd();
        
        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        shutdown(idxLeader);
        start(idxLeader);
        zk = getClient(idxLeader);

        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        shutdown(idxLeader);
        start(idxLeader);
        zk = getClient(idxFollower);

        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        // sanity check
        assertTrue(countCreated > 0);
        assertTrue(countCreated < 50);
    }

    /**
     * Similar to testRolloverThenRestart, but ensure we can survive multiple
     * epoch rollovers between restarts.
     */
    @Test
    public void testMultipleRollover() throws Exception {
        ZooKeeper zk = getClient(idxFollower);

        int countCreated = createNodes(zk, 0, 10);

        adjustEpochNearEnd();
        
        countCreated += createNodes(zk, countCreated, 10);

        adjustEpochNearEnd();

        countCreated += createNodes(zk, countCreated, 10);

        adjustEpochNearEnd();
        
        countCreated += createNodes(zk, countCreated, 10);

        adjustEpochNearEnd();

        countCreated += createNodes(zk, countCreated, 10);

        shutdownAll();
        startAll();
        zk = getClient(idxFollower);

        adjustEpochNearEnd();

        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        shutdown(idxLeader);
        start(idxLeader);
        zk = getClient(idxFollower);

        checkNodes(zk, 0, countCreated);
        countCreated += createNodes(zk, countCreated, 10);

        // sanity check
        assertTrue(countCreated > 0);
        assertTrue(countCreated < 70);
    }
}
