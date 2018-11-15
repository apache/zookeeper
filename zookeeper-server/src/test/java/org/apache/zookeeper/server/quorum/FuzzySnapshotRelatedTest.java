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

package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.security.sasl.SaslException;

import org.apache.jute.OutputArchive;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Op;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;

import org.junit.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test cases used to catch corner cases due to fuzzy snapshot.
 */
public class FuzzySnapshotRelatedTest extends QuorumPeerTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(FuzzySnapshotRelatedTest.class);

    MainThread[] mt = null;
    ZooKeeper[] zk = null;
    int leaderId;
    int followerA;

    @Before
    public void setup() throws Exception {
        LOG.info("Start up a 3 server quorum");
        final int ENSEMBLE_SERVERS = 3;
        final int clientPorts[] = new int[ENSEMBLE_SERVERS];
        StringBuilder sb = new StringBuilder();
        String server;

        for (int i = 0; i < ENSEMBLE_SERVERS; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique()
                    + ":" + PortAssignment.unique() + ":participant;127.0.0.1:"
                    + clientPorts[i];
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();

        // start servers
        mt = new MainThread[ENSEMBLE_SERVERS];
        zk = new ZooKeeper[ENSEMBLE_SERVERS];
        for (int i = 0; i < ENSEMBLE_SERVERS; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection,
                    false) {
                @Override
                public TestQPMain getTestQPMain() {
                    return new CustomizedQPMain();
                }
            };
            mt[i].start();
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i],
                    ClientBase.CONNECTION_TIMEOUT, this);
        }
        QuorumPeerMainTest.waitForAll(zk, States.CONNECTED);
        LOG.info("all servers started");

        leaderId = -1;
        followerA = -1;
        for (int i = 0; i < ENSEMBLE_SERVERS; i++) {
            if (mt[i].main.quorumPeer.leader != null) {
                leaderId = i;
            } else if (followerA == -1) {
                followerA = i;
            }
        }
    }

    @After
    public void tearDown() throws Exception {
        if (mt != null) {
            for (MainThread t: mt) {
                t.shutdown();
            }
        }

        if (zk != null) {
            for (ZooKeeper z: zk) {
                z.close();
            }
        }
    }

    @Test
    public void testMultiOpConsistency() throws Exception {
        LOG.info("Create a parent node");
        final String path = "/testMultiOpConsistency";
        createEmptyNode(zk[followerA], path);

        LOG.info("Hook to catch the 2nd sub create node txn in multi-op");
        CustomDataTree dt =
                (CustomDataTree) mt[followerA].main.quorumPeer.getZkDb().getDataTree();

        final ZooKeeperServer zkServer = mt[followerA].main.quorumPeer.getActiveServer();

        String node1 = path + "/1";
        String node2 = path + "/2";

        dt.addNodeCreateListener(node2, new NodeCreateListener() {
            @Override
            public void process(String path) {
                LOG.info("Take a snapshot");
                zkServer.takeSnapshot();
            }
        });

        LOG.info("Issue a multi op to create 2 nodes");
        zk[followerA].multi(Arrays.asList(
            Op.create(node1, node1.getBytes(),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
            Op.create(node2, node2.getBytes(),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT))
        );

        LOG.info("Restart the server");
        mt[followerA].shutdown();
        QuorumPeerMainTest.waitForOne(zk[followerA], States.CONNECTING);

        mt[followerA].start();
        QuorumPeerMainTest.waitForOne(zk[followerA], States.CONNECTED);

        LOG.info("Make sure the node consistent with leader");
        Assert.assertEquals(new String(zk[leaderId].getData(node2, null, null)),
                new String(zk[followerA].getData(node2, null, null)));
    }

    /**
     * It's possibel during SNAP sync, the parent is serialized before the
     * child get deleted during sending the snapshot over.
     *
     * In which case, we need to make sure the pzxid get correctly updated
     * when applying the txns received.
     */
    @Test
    public void testPZxidUpdatedDuringSnapSyncing() throws Exception {
        LOG.info("Enable force snapshot sync");
        System.setProperty(LearnerHandler.FORCE_SNAP_SYNC, "true");

        final String parent = "/testPZxidUpdatedWhenDeletingNonExistNode";
        final String child = parent + "/child";
        createEmptyNode(zk[leaderId], parent);
        createEmptyNode(zk[leaderId], child);

        LOG.info("shutdown follower {}", followerA);
        mt[followerA].shutdown();
        QuorumPeerMainTest.waitForOne(zk[followerA], States.CONNECTING);

        LOG.info("Set up ZKDatabase to catch the node serializing in DataTree");
        addSerializeListener(leaderId, parent, child);

        LOG.info("Restart follower A to trigger a SNAP sync with leader");
        mt[followerA].start();
        QuorumPeerMainTest.waitForOne(zk[followerA], States.CONNECTED);

        LOG.info("Check and make sure the pzxid of the parent is the same " +
                "on leader and follower A");
        compareStat(parent, leaderId, followerA);
    }

    /**
     * It's possible during taking fuzzy snapshot, the parent is serialized
     * before the child get deleted in the fuzzy range.
     *
     * In which case, we need to make sure the pzxid get correctly updated
     * when replaying the txns.
     */
    @Test
    public void testPZxidUpdatedWhenLoadingSnapshot() throws Exception {

        final String parent = "/testPZxidUpdatedDuringTakingSnapshot";
        final String child = parent + "/child";
        createEmptyNode(zk[followerA], parent);
        createEmptyNode(zk[followerA], child);

        LOG.info("Set up ZKDatabase to catch the node serializing in DataTree");
        addSerializeListener(followerA, parent, child);

        LOG.info("Take snapshot on follower A");
        ZooKeeperServer zkServer = mt[followerA].main.quorumPeer.getActiveServer();
        zkServer.takeSnapshot();

        LOG.info("Restarting follower A to load snapshot");
        mt[followerA].shutdown();
        QuorumPeerMainTest.waitForOne(zk[followerA], States.CONNECTING);

        mt[followerA].start();
        QuorumPeerMainTest.waitForOne(zk[followerA], States.CONNECTED);

        LOG.info("Check and make sure the pzxid of the parent is the same " +
                "on leader and follower A");
        compareStat(parent, leaderId, followerA);
    }

    private void addSerializeListener(int sid, String parent, String child) {
        final ZooKeeper zkClient = zk[followerA];
        CustomDataTree dt =
                (CustomDataTree) mt[sid].main.quorumPeer.getZkDb().getDataTree();
        dt.addListener(parent, new NodeSerializeListener() {
            @Override
            public void nodeSerialized(String path) {
                try {
                    zkClient.delete(child, -1);
                    LOG.info("Deleted the child node after the parent is serialized");
                } catch (Exception e) {
                    LOG.error("Error when deleting node {}", e);
                }
            }
        });
    }

    private void compareStat(String path, int sid, int compareWithSid) throws Exception {
        Stat stat1 = new Stat();
        zk[sid].getData(path, null, stat1);

        Stat stat2 = new Stat();
        zk[compareWithSid].getData(path, null, stat2);

        Assert.assertEquals(stat1, stat2);
    }

    private void createEmptyNode(ZooKeeper zk, String path) throws Exception {
        zk.create(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    static interface NodeCreateListener {
        public void process(String path);

    }

    static class CustomDataTree extends DataTree {
        Map<String, NodeCreateListener> nodeCreateListeners =
                new HashMap<String, NodeCreateListener>();
        Map<String, NodeSerializeListener> listeners =
                new HashMap<String, NodeSerializeListener>();

        @Override
        public void serializeNodeData(OutputArchive oa, String path,
                DataNode node) throws IOException {
            super.serializeNodeData(oa, path, node);
            NodeSerializeListener listener = listeners.get(path);
            if (listener != null) {
                listener.nodeSerialized(path);
            }
        }

        public void addListener(String path, NodeSerializeListener listener) {
            listeners.put(path, listener);
        }

        @Override
        public void createNode(final String path, byte data[], List<ACL> acl,
                           long ephemeralOwner, int parentCVersion, long zxid,
                           long time, Stat outputStat)
                throws NoNodeException, NodeExistsException {
            NodeCreateListener listener = nodeCreateListeners.get(path);
            if (listener != null) {
                listener.process(path);
            }
            super.createNode(path, data, acl, ephemeralOwner, parentCVersion,
                    zxid, time, outputStat);
        }

        public void addNodeCreateListener(String path, NodeCreateListener listener) {
            nodeCreateListeners.put(path, listener);
        }
    }

    static interface NodeSerializeListener {
        public void nodeSerialized(String path);
    }

    static class CustomizedQPMain extends TestQPMain {
        @Override
        protected QuorumPeer getQuorumPeer() throws SaslException {
            return new QuorumPeer() {
                @Override
                public void setZKDatabase(ZKDatabase database) {
                    super.setZKDatabase(new ZKDatabase(this.getTxnFactory()) {
                        @Override
                        public DataTree createDataTree() {
                            return new CustomDataTree();
                        }
                    });
                }
            };
        }
    }
}
