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
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.util.OSMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuorumBase extends ClientBase {

    private static final Logger LOG = LoggerFactory.getLogger(QuorumBase.class);

    private static final String LOCALADDR = "127.0.0.1";

    File s1dir, s2dir, s3dir, s4dir, s5dir;
    QuorumPeer s1, s2, s3, s4, s5;
    protected int port1;
    protected int port2;
    protected int port3;
    protected int port4;
    protected int port5;

    protected int portLE1;
    protected int portLE2;
    protected int portLE3;
    protected int portLE4;
    protected int portLE5;

    protected int portClient1;
    protected int portClient2;
    protected int portClient3;
    protected int portClient4;
    protected int portClient5;

    protected boolean localSessionsEnabled = false;
    protected boolean localSessionsUpgradingEnabled = false;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        setUp(false);
    }

    protected void setUp(boolean withObservers) throws Exception {
        LOG.info("QuorumBase.setup {}", getTestName());
        setupTestEnv();

        JMXEnv.setUp();

        setUpAll();

        port1 = PortAssignment.unique();
        port2 = PortAssignment.unique();
        port3 = PortAssignment.unique();
        port4 = PortAssignment.unique();
        port5 = PortAssignment.unique();

        portLE1 = PortAssignment.unique();
        portLE2 = PortAssignment.unique();
        portLE3 = PortAssignment.unique();
        portLE4 = PortAssignment.unique();
        portLE5 = PortAssignment.unique();

        portClient1 = PortAssignment.unique();
        portClient2 = PortAssignment.unique();
        portClient3 = PortAssignment.unique();
        portClient4 = PortAssignment.unique();
        portClient5 = PortAssignment.unique();

        hostPort = "127.0.0.1:"
                           + portClient1
                           + ",127.0.0.1:"
                           + portClient2
                           + ",127.0.0.1:"
                           + portClient3
                           + ",127.0.0.1:"
                           + portClient4
                           + ",127.0.0.1:"
                           + portClient5;
        LOG.info("Ports are: {}", hostPort);

        s1dir = ClientBase.createTmpDir();
        s2dir = ClientBase.createTmpDir();
        s3dir = ClientBase.createTmpDir();
        s4dir = ClientBase.createTmpDir();
        s5dir = ClientBase.createTmpDir();

        startServers(withObservers);

        OSMXBean osMbean = new OSMXBean();
        if (osMbean.getUnix()) {
            LOG.info("Initial fdcount is: {}", osMbean.getOpenFileDescriptorCount());
        }

        LOG.info("Setup finished");
    }

    void startServers() throws Exception {
        startServers(false);
    }

    void startServers(boolean withObservers) throws Exception {
        int tickTime = 2000;
        int initLimit = 3;
        int syncLimit = 3;
        int connectToLearnerMasterLimit = 3;
        Map<Long, QuorumServer> peers = new HashMap<Long, QuorumServer>();
        peers.put(Long.valueOf(1), new QuorumServer(1, new InetSocketAddress(LOCALADDR, port1), new InetSocketAddress(LOCALADDR, portLE1), new InetSocketAddress(LOCALADDR, portClient1), LearnerType.PARTICIPANT));
        peers.put(Long.valueOf(2), new QuorumServer(2, new InetSocketAddress(LOCALADDR, port2), new InetSocketAddress(LOCALADDR, portLE2), new InetSocketAddress(LOCALADDR, portClient2), LearnerType.PARTICIPANT));
        peers.put(Long.valueOf(3), new QuorumServer(3, new InetSocketAddress(LOCALADDR, port3), new InetSocketAddress(LOCALADDR, portLE3), new InetSocketAddress(LOCALADDR, portClient3), LearnerType.PARTICIPANT));
        peers.put(Long.valueOf(4), new QuorumServer(4, new InetSocketAddress(LOCALADDR, port4), new InetSocketAddress(LOCALADDR, portLE4), new InetSocketAddress(LOCALADDR, portClient4), LearnerType.PARTICIPANT));
        peers.put(Long.valueOf(5), new QuorumServer(5, new InetSocketAddress(LOCALADDR, port5), new InetSocketAddress(LOCALADDR, portLE5), new InetSocketAddress(LOCALADDR, portClient5), LearnerType.PARTICIPANT));

        if (withObservers) {
            peers.get(Long.valueOf(4)).type = LearnerType.OBSERVER;
            peers.get(Long.valueOf(5)).type = LearnerType.OBSERVER;
        }

        LOG.info("creating QuorumPeer 1 port {}", portClient1);
        s1 = new QuorumPeer(peers, s1dir, s1dir, portClient1, 3, 1, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
        assertEquals(portClient1, s1.getClientPort());
        LOG.info("creating QuorumPeer 2 port {}", portClient2);
        s2 = new QuorumPeer(peers, s2dir, s2dir, portClient2, 3, 2, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
        assertEquals(portClient2, s2.getClientPort());
        LOG.info("creating QuorumPeer 3 port {}", portClient3);
        s3 = new QuorumPeer(peers, s3dir, s3dir, portClient3, 3, 3, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
        assertEquals(portClient3, s3.getClientPort());
        LOG.info("creating QuorumPeer 4 port {}", portClient4);
        s4 = new QuorumPeer(peers, s4dir, s4dir, portClient4, 3, 4, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
        assertEquals(portClient4, s4.getClientPort());
        LOG.info("creating QuorumPeer 5 port {}", portClient5);
        s5 = new QuorumPeer(peers, s5dir, s5dir, portClient5, 3, 5, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
        assertEquals(portClient5, s5.getClientPort());

        if (withObservers) {
            s4.setLearnerType(LearnerType.OBSERVER);
            s5.setLearnerType(LearnerType.OBSERVER);
        }

        LOG.info("QuorumPeer 1 voting view: {}", s1.getVotingView());
        LOG.info("QuorumPeer 2 voting view: {}", s2.getVotingView());
        LOG.info("QuorumPeer 3 voting view: {}", s3.getVotingView());
        LOG.info("QuorumPeer 4 voting view: {}", s4.getVotingView());
        LOG.info("QuorumPeer 5 voting view: {}", s5.getVotingView());

        s1.enableLocalSessions(localSessionsEnabled);
        s2.enableLocalSessions(localSessionsEnabled);
        s3.enableLocalSessions(localSessionsEnabled);
        s4.enableLocalSessions(localSessionsEnabled);
        s5.enableLocalSessions(localSessionsEnabled);
        s1.enableLocalSessionsUpgrading(localSessionsUpgradingEnabled);
        s2.enableLocalSessionsUpgrading(localSessionsUpgradingEnabled);
        s3.enableLocalSessionsUpgrading(localSessionsUpgradingEnabled);
        s4.enableLocalSessionsUpgrading(localSessionsUpgradingEnabled);
        s5.enableLocalSessionsUpgrading(localSessionsUpgradingEnabled);

        LOG.info("start QuorumPeer 1");
        s1.start();
        LOG.info("start QuorumPeer 2");
        s2.start();
        LOG.info("start QuorumPeer 3");
        s3.start();
        LOG.info("start QuorumPeer 4");
        s4.start();
        LOG.info("start QuorumPeer 5");
        s5.start();
        LOG.info("started QuorumPeer 5");

        LOG.info("Checking ports {}", hostPort);
        for (String hp : hostPort.split(",")) {
            assertTrue(ClientBase.waitForServerUp(hp, CONNECTION_TIMEOUT), "waiting for server up");
            LOG.info("{} is accepting client connections", hp);
        }

        // interesting to see what's there...
        JMXEnv.dump();
        // make sure we have these 5 servers listed
        Set<String> ensureNames = new LinkedHashSet<String>();
        for (int i = 1; i <= 5; i++) {
            ensureNames.add("InMemoryDataTree");
        }
        for (int i = 1; i <= 5; i++) {
            ensureNames.add("name0=ReplicatedServer_id" + i + ",name1=replica." + i + ",name2=");
        }
        for (int i = 1; i <= 5; i++) {
            for (int j = 1; j <= 5; j++) {
                ensureNames.add("name0=ReplicatedServer_id" + i + ",name1=replica." + j);
            }
        }
        for (int i = 1; i <= 5; i++) {
            ensureNames.add("name0=ReplicatedServer_id" + i);
        }
        JMXEnv.ensureAll(ensureNames.toArray(new String[ensureNames.size()]));
    }

    public int getLeaderIndex() {
        if (s1.getPeerState() == ServerState.LEADING) {
            return 0;
        } else if (s2.getPeerState() == ServerState.LEADING) {
            return 1;
        } else if (s3.getPeerState() == ServerState.LEADING) {
            return 2;
        } else if (s4.getPeerState() == ServerState.LEADING) {
            return 3;
        } else if (s5.getPeerState() == ServerState.LEADING) {
            return 4;
        }
        return -1;
    }

    public int getLeaderClientPort() {
      if (s1.getPeerState() == ServerState.LEADING) {
        return portClient1;
      } else if (s2.getPeerState() == ServerState.LEADING) {
        return portClient2;
      } else if (s3.getPeerState() == ServerState.LEADING) {
        return portClient3;
      } else if (s4.getPeerState() == ServerState.LEADING) {
        return portClient4;
      } else if (s5.getPeerState() == ServerState.LEADING) {
        return portClient5;
      }
      return -1;
    }

    public QuorumPeer getLeaderQuorumPeer() {
      if (s1.getPeerState() == ServerState.LEADING) {
        return s1;
      } else if (s2.getPeerState() == ServerState.LEADING) {
        return s2;
      } else if (s3.getPeerState() == ServerState.LEADING) {
        return s3;
      } else if (s4.getPeerState() == ServerState.LEADING) {
        return s4;
      } else if (s5.getPeerState() == ServerState.LEADING) {
        return s5;
      }
      return null;
    }

    public QuorumPeer getFirstObserver() {
      if (s1.getLearnerType() == LearnerType.OBSERVER) {
        return s1;
      } else if (s2.getLearnerType() == LearnerType.OBSERVER) {
        return s2;
      } else if (s3.getLearnerType() == LearnerType.OBSERVER) {
        return s3;
      } else if (s4.getLearnerType() == LearnerType.OBSERVER) {
        return s4;
      } else if (s5.getLearnerType() == LearnerType.OBSERVER) {
        return s5;
      }
      return null;
    }

    public int getFirstObserverClientPort() {
      if (s1.getLearnerType() == LearnerType.OBSERVER) {
        return portClient1;
      } else if (s2.getLearnerType() == LearnerType.OBSERVER) {
        return portClient2;
      } else if (s3.getLearnerType() == LearnerType.OBSERVER) {
        return portClient3;
      } else if (s4.getLearnerType() == LearnerType.OBSERVER) {
        return portClient4;
      } else if (s5.getLearnerType() == LearnerType.OBSERVER) {
        return portClient5;
      }
      return -1;
    }

    public String getPeersMatching(ServerState state) {
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
        peers.add(s3);
        peers.add(s4);
        peers.add(s5);
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
        setupServer(3);
        setupServer(4);
        setupServer(5);
    }

    Map<Long, QuorumServer> peers = null;
    public void setupServer(int i) throws IOException {
        int tickTime = 2000;
        int initLimit = 3;
        int syncLimit = 3;
        int connectToLearnerMasterLimit = 3;

        if (peers == null) {
            peers = new HashMap<Long, QuorumServer>();

            peers.put(Long.valueOf(1), new QuorumServer(1, new InetSocketAddress(LOCALADDR, port1), new InetSocketAddress(LOCALADDR, portLE1), new InetSocketAddress(LOCALADDR, portClient1), LearnerType.PARTICIPANT));
            peers.put(Long.valueOf(2), new QuorumServer(2, new InetSocketAddress(LOCALADDR, port2), new InetSocketAddress(LOCALADDR, portLE2), new InetSocketAddress(LOCALADDR, portClient2), LearnerType.PARTICIPANT));
            peers.put(Long.valueOf(3), new QuorumServer(3, new InetSocketAddress(LOCALADDR, port3), new InetSocketAddress(LOCALADDR, portLE3), new InetSocketAddress(LOCALADDR, portClient3), LearnerType.PARTICIPANT));
            peers.put(Long.valueOf(4), new QuorumServer(4, new InetSocketAddress(LOCALADDR, port4), new InetSocketAddress(LOCALADDR, portLE4), new InetSocketAddress(LOCALADDR, portClient4), LearnerType.PARTICIPANT));
            peers.put(Long.valueOf(5), new QuorumServer(5, new InetSocketAddress(LOCALADDR, port5), new InetSocketAddress(LOCALADDR, portLE5), new InetSocketAddress(LOCALADDR, portClient5), LearnerType.PARTICIPANT));
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
        case 3:
            LOG.info("creating QuorumPeer 3 port {}", portClient3);
            s3 = new QuorumPeer(peers, s3dir, s3dir, portClient3, 3, 3, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
            assertEquals(portClient3, s3.getClientPort());
            break;
        case 4:
            LOG.info("creating QuorumPeer 4 port {}", portClient4);
            s4 = new QuorumPeer(peers, s4dir, s4dir, portClient4, 3, 4, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
            assertEquals(portClient4, s4.getClientPort());
            break;
        case 5:
            LOG.info("creating QuorumPeer 5 port {}", portClient5);
            s5 = new QuorumPeer(peers, s5dir, s5dir, portClient5, 3, 5, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
            assertEquals(portClient5, s5.getClientPort());
        }
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        LOG.info("TearDown started");

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
        shutdown(s3);
        shutdown(s4);
        shutdown(s5);
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
        CountdownWatcher watcher = new CountdownWatcher();
        return createClient(watcher, hp);
    }

    protected TestableZooKeeper createClient(CountdownWatcher watcher, ServerState state) throws IOException, InterruptedException {
        return createClient(watcher, getPeersMatching(state));
    }

}
