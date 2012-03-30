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

import junit.framework.AssertionFailedError;
import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.apache.zookeeper.test.ClientTest;
import org.apache.zookeeper.test.QuorumUtil;
import org.apache.zookeeper.test.QuorumUtil.PeerStruct;
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
            PeerStruct peer = qu.getPeer(i + 1);
            zkClients[i] = new ZooKeeper(
                    "127.0.0.1:" + peer.clientPort,
                    ClientTest.CONNECTION_TIMEOUT, zkClientWatchers[i]);
        }
        waitForClientsConnected();
    }
    
    private void waitForClientsConnected() throws Exception {
        for (int i = 0; i < zkClients.length; i++) {
            zkClientWatchers[i].waitForConnected(ClientTest.CONNECTION_TIMEOUT);
            zkClientWatchers[i].reset();
        }
    }

    /**
     * Ensure all clients are able to talk to the service.
     */
    private void checkClientsConnected() throws Exception {
        for (int i = 0; i < zkClients.length; i++) {
            checkClientConnected(i + 1);
        }
    }

    /**
     * Ensure the client is able to talk to the server.
     * 
     * @param idx the idx of the server the client is talking to
     */
    private void checkClientConnected(int idx) throws Exception {
        ZooKeeper zk = getClient(idx);
        if (zk == null) {
            return;
        }
        try {
            assertNull(zk.exists("/foofoofoo-connected", false));
        } catch (ConnectionLossException e) {
            // second chance...
            // in some cases, leader change in particular, the timing is
            // very tricky to get right in order to assure that the client has
            // disconnected and reconnected. In some cases the client will
            // disconnect, then attempt to reconnect before the server is
            // back, in which case we'll see another connloss on the operation
            // in the try, this catches that case and waits for the server
            // to come back
            PeerStruct peer = qu.getPeer(idx);
            Assert.assertTrue("Waiting for server down", ClientBase.waitForServerUp(
                    "127.0.0.1:" + peer.clientPort, ClientBase.CONNECTION_TIMEOUT));

            assertNull(zk.exists("/foofoofoo-connected", false));
        }
    }

    /**
     * Ensure all clients are disconnected from the service.
     */
    private void checkClientsDisconnected() throws Exception {
        for (int i = 0; i < zkClients.length; i++) {
            checkClientDisconnected(i + 1);
        }
    }

    /**
     * Ensure the client is able to talk to the server
     * 
     * @param idx the idx of the server the client is talking to
     */
    private void checkClientDisconnected(int idx) throws Exception {
        ZooKeeper zk = getClient(idx);
        if (zk == null) {
            return;
        }
        try {
            assertNull(zk.exists("/foofoofoo-disconnected", false));
            fail("expected client to be disconnected");
        } catch (KeeperException e) {
            // success
        }
    }

    private void startAll() throws Exception {
        qu.startAll();
        checkLeader();
        // all clients should be connected
        checkClientsConnected();
    }
    private void start(int idx) throws Exception {
        qu.start(idx);
        for (String hp : qu.getConnString().split(",")) {
            Assert.assertTrue("waiting for server up", ClientBase.waitForServerUp(hp,
                    ClientTest.CONNECTION_TIMEOUT));
        }

        checkLeader();
        // all clients should be connected
        checkClientsConnected();
    }

    private void checkLeader() {
        idxLeader = 1;
        while(qu.getPeer(idxLeader).peer.leader == null) {
            idxLeader++;
        }
        idxFollower = (idxLeader == 1 ? 2 : 1);

        zksLeader = qu.getPeer(idxLeader).peer.getActiveServer();
    }

    private void shutdownAll() throws Exception {
        qu.shutdownAll();
        // all clients should be disconnected
        checkClientsDisconnected();
    }
    
    private void shutdown(int idx) throws Exception {
        qu.shutdown(idx);

        // leader will shutdown, remaining followers will elect a new leader
        PeerStruct peer = qu.getPeer(idx);
        Assert.assertTrue("Waiting for server down", ClientBase.waitForServerDown(
                "127.0.0.1:" + peer.clientPort, ClientBase.CONNECTION_TIMEOUT));

        // if idx is the the leader then everyone will get disconnected,
        // otherwise if idx is a follower then just that client will get
        // disconnected
        if (idx == idxLeader) {
            checkClientDisconnected(idx);
            try {
                checkClientsDisconnected();
            } catch (AssertionFailedError e) {
                // the clients may or may not have already reconnected
                // to the recovered cluster, force a check, but ignore
            }
        } else {
            checkClientDisconnected(idx);
        }
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
            waitForClientsConnected();
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
