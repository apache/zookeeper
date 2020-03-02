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

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.quorum.Election;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.util.OSMXBean;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for quorum testing. Setups 2n+1 peers and allows to start/stop all
 * peers, particular peer, n peers etc.
 */
public class QuorumUtil {

    // TODO partitioning of peers and clients

    // TODO refactor QuorumBase to be special case of this

    private static final Logger LOG = LoggerFactory.getLogger(QuorumUtil.class);
    private static final Set<QuorumPeer.ServerState> CONNECTED_STATES = new TreeSet<>(
            Arrays.asList(QuorumPeer.ServerState.LEADING, QuorumPeer.ServerState.FOLLOWING, QuorumPeer.ServerState.OBSERVING));

    public static class PeerStruct {
        public int id;
        public QuorumPeer peer;
        public File dataDir;
        public int clientPort;
    }

    private final Map<Long, QuorumServer> peersView = new HashMap<Long, QuorumServer>();

    private final Map<Integer, PeerStruct> peers = new HashMap<Integer, PeerStruct>();

    public final int N;

    public final int ALL;

    private String hostPort;

    private int tickTime;

    private int initLimit;

    private int syncLimit;

    private int electionAlg;

    private boolean localSessionEnabled;

    /**
     * Initializes 2n+1 quorum peers which will form a ZooKeeper ensemble.
     *
     * @param n
     *            number of peers in the ensemble will be 2n+1
     */
    public QuorumUtil(int n, int syncLimit) throws RuntimeException {
        try {
            ClientBase.setupTestEnv();
            JMXEnv.setUp();

            N = n;
            ALL = 2 * N + 1;
            tickTime = 2000;
            initLimit = 3;
            this.syncLimit = syncLimit;
            electionAlg = 3;
            hostPort = "";

            for (int i = 1; i <= ALL; ++i) {
                PeerStruct ps = new PeerStruct();
                ps.id = i;
                ps.dataDir = ClientBase.createTmpDir();
                ps.clientPort = PortAssignment.unique();
                peers.put(i, ps);

                peersView.put(Long.valueOf(i), new QuorumServer(i, 
                               new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                               new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                               new InetSocketAddress("127.0.0.1", ps.clientPort),
                               LearnerType.PARTICIPANT));
                hostPort += "127.0.0.1:" + ps.clientPort + ((i == ALL) ? "" : ",");
            }
            for (int i = 1; i <= ALL; ++i) {
                PeerStruct ps = peers.get(i);
                LOG.info("Creating QuorumPeer " + i + "; public port " + ps.clientPort);
                ps.peer = new QuorumPeer(peersView, ps.dataDir, ps.dataDir, ps.clientPort,
                        electionAlg, ps.id, tickTime, initLimit, syncLimit);
                Assert.assertEquals(ps.clientPort, ps.peer.getClientPort());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public QuorumUtil(int n) throws RuntimeException {
        this(n, 3);
    }

    public PeerStruct getPeer(int id) {
        return peers.get(id);
    }

    // This was added to avoid running into the problem of ZOOKEEPER-1539
    public boolean disableJMXTest = false;
    

    public void enableLocalSession(boolean localSessionEnabled) {
        this.localSessionEnabled = localSessionEnabled;
    }

    public void startAll() throws IOException {
        shutdownAll();
        for (int i = 1; i <= ALL; ++i) {
            start(i);
            LOG.info("Started QuorumPeer " + i);
        }

        LOG.info("Checking ports " + hostPort);
        for (String hp : hostPort.split(",")) {
            Assert.assertTrue("waiting for server up", ClientBase.waitForServerUp(hp,
                    ClientBase.CONNECTION_TIMEOUT));
            LOG.info(hp + " is accepting client connections");
        }

        // This was added to avoid running into the problem of ZOOKEEPER-1539
        if (disableJMXTest) return;
        
        // interesting to see what's there...
        try {
            JMXEnv.dump();
            // make sure we have all servers listed
            Set<String> ensureNames = new LinkedHashSet<String>();
            for (int i = 1; i <= ALL; ++i) {
                ensureNames.add("InMemoryDataTree");
            }
            for (int i = 1; i <= ALL; ++i) {
                ensureNames
                        .add("name0=ReplicatedServer_id" + i + ",name1=replica." + i + ",name2=");
            }
            for (int i = 1; i <= ALL; ++i) {
                for (int j = 1; j <= ALL; ++j) {
                    ensureNames.add("name0=ReplicatedServer_id" + i + ",name1=replica." + j);
                }
            }
            for (int i = 1; i <= ALL; ++i) {
                ensureNames.add("name0=ReplicatedServer_id" + i);
            }
            JMXEnv.ensureAll(ensureNames.toArray(new String[ensureNames.size()]));
        } catch (IOException e) {
            LOG.warn("IOException during JMXEnv operation", e);
        } catch (InterruptedException e) {
            LOG.warn("InterruptedException during JMXEnv operation", e);
        }
    }

    /**
     * Start first N+1 peers.
     */
    public void startQuorum() throws IOException {
        shutdownAll();
        for (int i = 1; i <= N + 1; ++i) {
            start(i);
        }
        for (int i = 1; i <= N + 1; ++i) {
            Assert.assertTrue("Waiting for server up", ClientBase.waitForServerUp("127.0.0.1:"
                    + getPeer(i).clientPort, ClientBase.CONNECTION_TIMEOUT));
        }
    }

    public void start(int id) throws IOException {
        PeerStruct ps = getPeer(id);
        LOG.info("Creating QuorumPeer " + ps.id + "; public port " + ps.clientPort);
        ps.peer = new QuorumPeer(peersView, ps.dataDir, ps.dataDir, ps.clientPort, electionAlg,
                ps.id, tickTime, initLimit, syncLimit);
        if (localSessionEnabled) {
            ps.peer.enableLocalSessions(true);
        }
        Assert.assertEquals(ps.clientPort, ps.peer.getClientPort());

        ps.peer.start();
    }

    public void restart(int id) throws IOException {
        start(id);
        Assert.assertTrue("Waiting for server up", ClientBase.waitForServerUp("127.0.0.1:"
                + getPeer(id).clientPort, ClientBase.CONNECTION_TIMEOUT));
    }

    public void startThenShutdown(int id) throws IOException {
        PeerStruct ps = getPeer(id);
        LOG.info("Creating QuorumPeer " + ps.id + "; public port " + ps.clientPort);
        ps.peer = new QuorumPeer(peersView, ps.dataDir, ps.dataDir, ps.clientPort, electionAlg,
                ps.id, tickTime, initLimit, syncLimit);
        if (localSessionEnabled) {
            ps.peer.enableLocalSessions(true);
        }
        Assert.assertEquals(ps.clientPort, ps.peer.getClientPort());

        ps.peer.start();
        Assert.assertTrue("Waiting for server up", ClientBase.waitForServerUp("127.0.0.1:"
                + getPeer(id).clientPort, ClientBase.CONNECTION_TIMEOUT));
        shutdown(id);
    }

    public void shutdownAll() {
        for (int i = 1; i <= ALL; ++i) {
            shutdown(i);
        }
        for (String hp : hostPort.split(",")) {
            Assert.assertTrue("Waiting for server down", ClientBase.waitForServerDown(hp,
                    ClientBase.CONNECTION_TIMEOUT));
            LOG.info(hp + " is no longer accepting client connections");
        }
    }

    public void shutdown(int id) {
        QuorumPeer qp = getPeer(id).peer;
        try {
            LOG.info("Shutting down quorum peer " + qp.getName());
            qp.shutdown();
            Election e = qp.getElectionAlg();
            if (e != null) {
                LOG.info("Shutting down leader election " + qp.getName());
                e.shutdown();
            } else {
                LOG.info("No election available to shutdown " + qp.getName());
            }
            LOG.info("Waiting for " + qp.getName() + " to exit thread");
            qp.join(30000);
            if (qp.isAlive()) {
                Assert.fail("QP failed to shutdown in 30 seconds: " + qp.getName());
            }
        } catch (InterruptedException e) {
            LOG.debug("QP interrupted: " + qp.getName(), e);
        }
    }

    public String getConnString() {
        return hostPort;
    }

    public String getConnectString(QuorumPeer peer) {
        return "127.0.0.1:" + peer.getClientPort();
    }

    public boolean allPeersAreConnected() {
        return peers.values().stream()
                .map(ps -> ps.peer)
                .allMatch(peer -> CONNECTED_STATES.contains(peer.getPeerState()));
    }

    public QuorumPeer getLeaderQuorumPeer() {
        for (PeerStruct ps: peers.values()) {
            if (ps.peer.leader != null) {
               return ps.peer;
            }
        }
        throw new RuntimeException("Unable to find a leader peer");
    }

    public List<QuorumPeer> getFollowerQuorumPeers() {
        List<QuorumPeer> peerList = new ArrayList<QuorumPeer>(ALL - 1); 

        for (PeerStruct ps: peers.values()) {
            if (ps.peer.leader == null) {
               peerList.add(ps.peer);      
            }
        }

        return Collections.unmodifiableList(peerList);
    }

    public void tearDown() throws Exception {
        LOG.info("TearDown started");

        OSMXBean osMbean = new OSMXBean();
        if (osMbean.getUnix() == true) {    
            LOG.info("fdcount after test is: " + osMbean.getOpenFileDescriptorCount());
        }

        shutdownAll();
        JMXEnv.tearDown();
    }

    public int getLeaderServer() {
        int index = 0;
        for (int i = 1; i <= ALL; i++) {
            if (getPeer(i).peer.leader != null) {
                index = i;
                break;
            }
        }

        Assert.assertTrue("Leader server not found.", index > 0);
        return index;
    }

    public boolean leaderExists() {
        for (int i = 1; i <= ALL; i++) {
            if (getPeer(i).peer.leader != null) {
                return true;
            }
        }
        return false;
    }

    public String getConnectionStringForServer(final int index) {
        return "127.0.0.1:" + getPeer(index).clientPort;
    }
}
