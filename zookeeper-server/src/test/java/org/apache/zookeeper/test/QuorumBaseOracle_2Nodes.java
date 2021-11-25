/*
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.server.quorum.Election;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.util.OSMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class QuorumBaseOracle_2Nodes extends ClientBase{

    private static final Logger LOG = LoggerFactory.getLogger(QuorumBase.class);

    private static final String LOCALADDR = "127.0.0.1";

    private File oracleDir;
    private static String oraclePath_0 = "/oraclePath/0/mastership/";
    private static String oraclePath_1 = "/oraclePath/1/mastership/";

    private static final String mastership = "value";

    File s1dir, s2dir;
    QuorumPeer s1, s2;
    protected int port1;
    protected int port2;

    protected int portLE1;
    protected int portLE2;

    protected int portClient1;
    protected int portClient2;

    protected boolean localSessionsEnabled = false;
    protected boolean localSessionsUpgradingEnabled = false;



    @BeforeEach
    @Override
    public void setUp() throws Exception {
        LOG.info("QuorumBase.setup {}", getTestName());
        setupTestEnv();

        JMXEnv.setUp();

        setUpAll();

        port1 = PortAssignment.unique();
        port2 = PortAssignment.unique();

        portLE1 = PortAssignment.unique();
        portLE2 = PortAssignment.unique();

        portClient1 = PortAssignment.unique();
        portClient2 = PortAssignment.unique();

        hostPort = "127.0.0.1:"
                + portClient1
                + ",127.0.0.1:"
                + portClient2;
        LOG.info("Ports are: {}", hostPort);

        s1dir = ClientBase.createTmpDir();
        s2dir = ClientBase.createTmpDir();

        createOraclePath();

        startServers();

        OSMXBean osMbean = new OSMXBean();
        if (osMbean.getUnix()) {
            LOG.info("Initial fdcount is: {}", osMbean.getOpenFileDescriptorCount());
        }

        LOG.info("Setup finished");
    }

    private void createOraclePath() throws IOException {
        oracleDir = ClientBase.createTmpDir();
        File directory = new File(oracleDir, oraclePath_0);
        directory.mkdirs();
        FileWriter fw = new FileWriter(oracleDir.getAbsolutePath() + oraclePath_0 + mastership);
        fw.write("0");
        fw.close();

        directory = new File(oracleDir, oraclePath_1);
        directory.mkdirs();
        fw = new FileWriter(oracleDir.getAbsolutePath() + oraclePath_1 + mastership);
        fw.write("1");
        fw.close();
    }

    void startServers() throws Exception {
        int tickTime = 2000;
        int initLimit = 3;
        int syncLimit = 3;
        int connectToLearnerMasterLimit = 3;
        Map<Long, QuorumPeer.QuorumServer> peers = new HashMap<Long, QuorumPeer.QuorumServer>();
        peers.put(Long.valueOf(1), new QuorumPeer.QuorumServer(1, new InetSocketAddress(LOCALADDR, port1), new InetSocketAddress(LOCALADDR, portLE1), new InetSocketAddress(LOCALADDR, portClient1), QuorumPeer.LearnerType.PARTICIPANT));
        peers.put(Long.valueOf(2), new QuorumPeer.QuorumServer(2, new InetSocketAddress(LOCALADDR, port2), new InetSocketAddress(LOCALADDR, portLE2), new InetSocketAddress(LOCALADDR, portClient2), QuorumPeer.LearnerType.PARTICIPANT));

        LOG.info("creating QuorumPeer 1 port {}", portClient1);
        s1 = new QuorumPeer(peers, s1dir, s1dir, portClient1, 3, 1, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit, oracleDir
                .getAbsolutePath() + oraclePath_0 + mastership);
        assertEquals(portClient1, s1.getClientPort());
        LOG.info("creating QuorumPeer 2 port {}", portClient2);
        s2 = new QuorumPeer(peers, s2dir, s2dir, portClient2, 3, 2, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit, oracleDir
                .getAbsolutePath() + oraclePath_1 + mastership);
        assertEquals(portClient2, s2.getClientPort());


        LOG.info("QuorumPeer 1 voting view: {}", s1.getVotingView());
        LOG.info("QuorumPeer 2 voting view: {}", s2.getVotingView());

        s1.enableLocalSessions(localSessionsEnabled);
        s2.enableLocalSessions(localSessionsEnabled);
        s1.enableLocalSessionsUpgrading(localSessionsUpgradingEnabled);
        s2.enableLocalSessionsUpgrading(localSessionsUpgradingEnabled);

        LOG.info("start QuorumPeer 1");
        s1.start();
        LOG.info("start QuorumPeer 2");
        s2.start();

        LOG.info("Checking ports {}", hostPort);
        for (String hp : hostPort.split(",")) {
            assertTrue(ClientBase.waitForServerUp(hp, CONNECTION_TIMEOUT), "waiting for server up");
            LOG.info("{} is accepting client connections", hp);
        }

        // interesting to see what's there...
        JMXEnv.dump();
        // make sure we have these 5 servers listed
        Set<String> ensureNames = new LinkedHashSet<String>();
        for (int i = 1; i <= 2; i++) {
            ensureNames.add("InMemoryDataTree");
        }
        for (int i = 1; i <= 2; i++) {
            ensureNames.add("name0=ReplicatedServer_id" + i + ",name1=replica." + i + ",name2=");
        }
        for (int i = 1; i <= 2; i++) {
            for (int j = 1; j <= 2; j++) {
                ensureNames.add("name0=ReplicatedServer_id" + i + ",name1=replica." + j);
            }
        }
        for (int i = 1; i <= 2; i++) {
            ensureNames.add("name0=ReplicatedServer_id" + i);
        }
        JMXEnv.ensureAll(ensureNames.toArray(new String[ensureNames.size()]));
    }

    public int getLeaderIndex() {
        if (s1.getPeerState() == QuorumPeer.ServerState.LEADING) {
            return 0;
        } else if (s2.getPeerState() == QuorumPeer.ServerState.LEADING) {
            return 1;
        }
        return -1;
    }

    public int getLeaderClientPort() {
        if (s1.getPeerState() == QuorumPeer.ServerState.LEADING) {
            return portClient1;
        } else if (s2.getPeerState() == QuorumPeer.ServerState.LEADING) {
            return portClient2;
        }
        return -1;
    }

    public QuorumPeer getLeaderQuorumPeer() {
        if (s1.getPeerState() == QuorumPeer.ServerState.LEADING) {
            return s1;
        } else if (s2.getPeerState() == QuorumPeer.ServerState.LEADING) {
            return s2;
        }
        return null;
    }

    public QuorumPeer getFirstObserver() {
        if (s1.getLearnerType() == QuorumPeer.LearnerType.OBSERVER) {
            return s1;
        } else if (s2.getLearnerType() == QuorumPeer.LearnerType.OBSERVER) {
            return s2;
        }
        return null;
    }

    public int getFirstObserverClientPort() {
        if (s1.getLearnerType() == QuorumPeer.LearnerType.OBSERVER) {
            return portClient1;
        } else if (s2.getLearnerType() == QuorumPeer.LearnerType.OBSERVER) {
            return portClient2;
        }
        return -1;
    }

    public String getPeersMatching(QuorumPeer.ServerState state) {
        StringBuilder hosts = new StringBuilder();
        for (QuorumPeer p : getPeerList()) {
            if (p.getPeerState() == state) {
                hosts.append(String.format("%s:%d,", LOCALADDR, p.getClientAddress().getPort()));
            }
        }
        LOG.info("getPeersMatching ports are {}", hosts);
        return hosts.toString();
    }

    public ArrayList<QuorumPeer> getPeerList() {
        ArrayList<QuorumPeer> peers = new ArrayList<QuorumPeer>();
        peers.add(s1);
        peers.add(s2);
        return peers;
    }

    public QuorumPeer getPeerByClientPort(int clientPort) {
        for (QuorumPeer p : getPeerList()) {
            if (p.getClientAddress().getPort() == clientPort) {
                return p;
            }
        }
        return null;
    }

    public void setupServers() throws IOException {
        setupServer(1);
        setupServer(2);
    }

    Map<Long, QuorumPeer.QuorumServer> peers = null;

    public void setupServer(int i) throws IOException {
        int tickTime = 2000;
        int initLimit = 3;
        int syncLimit = 3;
        int connectToLearnerMasterLimit = 3;

        if (peers == null) {
            peers = new HashMap<Long, QuorumPeer.QuorumServer>();

            peers.put(Long.valueOf(1), new QuorumPeer.QuorumServer(1, new InetSocketAddress(LOCALADDR, port1), new InetSocketAddress(LOCALADDR, portLE1), new InetSocketAddress(LOCALADDR, portClient1), QuorumPeer.LearnerType.PARTICIPANT));
            peers.put(Long.valueOf(2), new QuorumPeer.QuorumServer(2, new InetSocketAddress(LOCALADDR, port2), new InetSocketAddress(LOCALADDR, portLE2), new InetSocketAddress(LOCALADDR, portClient2), QuorumPeer.LearnerType.PARTICIPANT));
        }

        switch (i) {
            case 1:
                LOG.info("creating QuorumPeer 1 port {}", portClient1);
                s1 = new QuorumPeer(peers, s1dir, s1dir, portClient1, 3, 1, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
                assertEquals(portClient1, s1.getClientPort());
                break;
            case 2:
                LOG.info("creating QuorumPeer 2 port {}", portClient2);
                s2 = new QuorumPeer(peers, s2dir, s2dir, portClient2, 3, 2, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
                assertEquals(portClient2, s2.getClientPort());
                break;
        }
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        LOG.info("TearDown started");
        if (oracleDir != null) {
            ClientBase.recursiveDelete(oracleDir);
        }

        OSMXBean osMbean = new OSMXBean();
        if (osMbean.getUnix()) {
            LOG.info("fdcount after test is: {}", osMbean.getOpenFileDescriptorCount());
        }

        shutdownServers();

        for (String hp : hostPort.split(",")) {
            assertTrue(ClientBase.waitForServerDown(hp, ClientBase.CONNECTION_TIMEOUT), "waiting for server down");
            LOG.info("{} is no longer accepting client connections", hp);
        }

        JMXEnv.tearDown();
    }
    public void shutdownServers() {
        shutdown(s1);
        shutdown(s2);
    }

    public static void shutdown(QuorumPeer qp) {
        if (qp == null) {
            return;
        }
        try {
            LOG.info("Shutting down quorum peer {}", qp.getName());
            qp.shutdown();
            Election e = qp.getElectionAlg();
            if (e != null) {
                LOG.info("Shutting down leader election {}", qp.getName());
                e.shutdown();
            } else {
                LOG.info("No election available to shutdown {}", qp.getName());
            }
            LOG.info("Waiting for {} to exit thread", qp.getName());
            long readTimeout = qp.getTickTime() * qp.getInitLimit();
            long connectTimeout = qp.getTickTime() * qp.getSyncLimit();
            long maxTimeout = Math.max(readTimeout, connectTimeout);
            maxTimeout = Math.max(maxTimeout, ClientBase.CONNECTION_TIMEOUT);
            qp.join(maxTimeout * 2);
            if (qp.isAlive()) {
                fail("QP failed to shutdown in " + (maxTimeout * 2) + " seconds: " + qp.getName());
            }
        } catch (InterruptedException e) {
            LOG.debug("QP interrupted: {}", qp.getName(), e);
        }
    }

    protected TestableZooKeeper createClient() throws IOException, InterruptedException {
        return createClient(hostPort);
    }

    protected TestableZooKeeper createClient(String hp) throws IOException, InterruptedException {
        ClientBase.CountdownWatcher watcher = new ClientBase.CountdownWatcher();
        return createClient(watcher, hp);
    }

    protected TestableZooKeeper createClient(ClientBase.CountdownWatcher watcher, QuorumPeer.ServerState state) throws IOException, InterruptedException {
        return createClient(watcher, getPeersMatching(state));
    }

}
