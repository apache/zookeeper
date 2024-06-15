/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.zookeeper.server.quorum;

import static org.junit.Assert.assertFalse;

import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.impl.MetricsProviderBootstrap;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.util.JvmPauseMonitor;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.sasl.SaslException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LearnerCnxAcceptorTest {
    private static final Logger LOG = LoggerFactory.getLogger(LearnerCnxAcceptorTest.class);

    static {
        System.setProperty("zookeeper.admin.enableServer", "false");
    }

    /**
     * Inject IOException at the second accept() call
     * and block startZkServer() until the injection ERROR state appears.
     * Failure happens when the leader ERROR state disappears due to startZkServer().
     */
    static class QuorumStateTestHelper {
        private int acceptCount = 0;
        private boolean failure = false;
        final CountDownLatch timeout = new CountDownLatch(1);
        final CountDownLatch latch = new CountDownLatch(1);

        synchronized boolean shouldInject() {
            acceptCount++;
            return acceptCount == 2;
        }

        synchronized void fail() {
            failure = true;
        }

        synchronized boolean isFailure() {
            return failure;
        }
    }

    static final QuorumStateTestHelper quorumStateTestHelper = new QuorumStateTestHelper();

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-4203
     */
    @Test
    public void testLeaderErrorState() throws QuorumPeerConfig.ConfigException, IOException, InterruptedException {
        // We assume that after the leader election always has 1 leader and 2 followers
        // and the injection happens while the leader is accepting the second follower
        MockQuorumPeerMain peerMain3 = buildQuorumPeerMain(3);
        MockQuorumPeerMain peerMain1 = buildQuorumPeerMain(1);
        MockQuorumPeerMain peerMain2 = buildQuorumPeerMain(2);

        quorumStateTestHelper.timeout.await(10, TimeUnit.SECONDS);

        shutdown(peerMain1);
        shutdown(peerMain2);
        shutdown(peerMain3);

        assertFalse(quorumStateTestHelper.isFailure());
    }

    private MockQuorumPeerMain buildQuorumPeerMain(int id)
            throws IOException, QuorumPeerConfig.ConfigException {
        MockQuorumPeerMain peerMain = new MockQuorumPeerMain();

        QuorumPeerConfigTest.MockQuorumPeerConfig peerConfig = new QuorumPeerConfigTest.MockQuorumPeerConfig(id);
        peerConfig.parseProperties(buildConfigProperties(id));
        peerMain.runFromConfig(peerConfig);
        return peerMain;
    }

    private void shutdown(MockQuorumPeerMain peerMain) {
        try {
            peerMain.getQuorumPeer().shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Properties buildConfigProperties(int id) {
        Properties properties = new Properties();
        properties.setProperty("tickTime", "2000");
        properties.setProperty("initLimit", "10");
        properties.setProperty("syncLimit", "2000");
        properties.setProperty("dataDir", "/tmp/zookeeper" + id);
        properties.setProperty("clientPort", "218" + id);
        properties.setProperty("server.1", "127.0.0.1:2887:3887");
        properties.setProperty("server.2", "127.0.0.1:2888:3888");
        properties.setProperty("server.3", "127.0.0.1:2889:3889");
        return properties;
    }

    static class MockLeaderZooKeeperServer extends LeaderZooKeeperServer {
        MockLeaderZooKeeperServer(FileTxnSnapLog logFactory, QuorumPeer self, ZKDatabase zkDb) throws IOException {
            super(logFactory, self, zkDb);
        }

        @Override
        protected synchronized void setState(State state) {
            final State previousState = this.state;
            super.setState(state);
            if (state == State.ERROR) {
                quorumStateTestHelper.latch.countDown();
            }
            if (previousState == State.ERROR &&
                    (this.state == State.RUNNING || this.state == State.INITIAL)) {
                quorumStateTestHelper.fail();
            }
        }
    }

    static class MockLeader extends Leader {
        public MockLeader(QuorumPeer self, LeaderZooKeeperServer zk) throws IOException {
            super(self, zk);
        }

        @Override
        protected synchronized void startZkServer() {
            try {
                quorumStateTestHelper.latch.await();
            } catch (InterruptedException e) {
                LOG.warn("Unexpected error while waiting for testHelper latch", e);
            }
            super.startZkServer();
        }

        @Override
        protected LearnerCnxAcceptor createLearnerCnxAcceptor() {
            return new MockLearnerCnxAcceptor();
        }

        class MockLearnerCnxAcceptor extends LearnerCnxAcceptor {
            @Override
            protected LearnerCnxAcceptorHandler createLearnerCnxAcceptorHandler(
                    ServerSocket serverSocket, CountDownLatch latch) {
                return new MockLearnerCnxAcceptorHandler(serverSocket, latch);
            }

            class MockLearnerCnxAcceptorHandler extends LearnerCnxAcceptorHandler {
                MockLearnerCnxAcceptorHandler(ServerSocket serverSocket, CountDownLatch latch) {
                    super(serverSocket, latch);
                }

                @Override
                protected Socket acceptSocketFrom(ServerSocket serverSocket) throws IOException {
                    if (quorumStateTestHelper.shouldInject()) {
                        throw new IOException("QuorumStateTest inject");
                    }
                    return serverSocket.accept();
                }
            }
        }
    }

    static class MockQuorumPeer extends QuorumPeer {
        public MockQuorumPeer() throws SaslException {
            super();
        }

        @Override
        protected Leader makeLeader(FileTxnSnapLog logFactory) throws IOException {
            return new MockLeader(this, new MockLeaderZooKeeperServer(logFactory, this, this.zkDb));
        }
    }

    static class MockQuorumPeerMain extends QuorumPeerMain {
        private volatile QuorumPeer quorumPeer;

        @Override
        public void runFromConfig(QuorumPeerConfig config) throws IOException {
            LOG.info("Starting quorum peer, myid=" + config.getServerId());
            final MetricsProvider metricsProvider;
            try {
                metricsProvider = MetricsProviderBootstrap.startMetricsProvider(
                        config.getMetricsProviderClassName(),
                        config.getMetricsProviderConfiguration());
            } catch (MetricsProviderLifeCycleException error) {
                throw new IOException("Cannot boot MetricsProvider " + config.getMetricsProviderClassName(), error);
            }
            try {
                ServerMetrics.metricsProviderInitialized(metricsProvider);
                ProviderRegistry.initialize();
                ServerCnxnFactory cnxnFactory = null;
                ServerCnxnFactory secureCnxnFactory = null;

                if (config.getClientPortAddress() != null) {
                    cnxnFactory = ServerCnxnFactory.createFactory();
                    cnxnFactory.configure(config.getClientPortAddress(), config.getMaxClientCnxns(),
                            config.getClientPortListenBacklog(), false);
                }

                if (config.getSecureClientPortAddress() != null) {
                    secureCnxnFactory = ServerCnxnFactory.createFactory();
                    secureCnxnFactory.configure(config.getSecureClientPortAddress(), config.getMaxClientCnxns(),
                            config.getClientPortListenBacklog(), true);
                }

                quorumPeer = getQuorumPeer();
                quorumPeer.setTxnFactory(new FileTxnSnapLog(config.getDataLogDir(), config.getDataDir()));
                quorumPeer.enableLocalSessions(config.areLocalSessionsEnabled());
                quorumPeer.enableLocalSessionsUpgrading(config.isLocalSessionsUpgradingEnabled());
                //quorumPeer.setQuorumPeers(config.getAllMembers());
                quorumPeer.setElectionType(config.getElectionAlg());
                quorumPeer.setMyid(config.getServerId());
                quorumPeer.setTickTime(config.getTickTime());
                quorumPeer.setMinSessionTimeout(config.getMinSessionTimeout());
                quorumPeer.setMaxSessionTimeout(config.getMaxSessionTimeout());
                quorumPeer.setInitLimit(config.getInitLimit());
                quorumPeer.setSyncLimit(config.getSyncLimit());
                quorumPeer.setConnectToLearnerMasterLimit(config.getConnectToLearnerMasterLimit());
                quorumPeer.setObserverMasterPort(config.getObserverMasterPort());
                quorumPeer.setConfigFileName(config.getConfigFilename());
                quorumPeer.setClientPortListenBacklog(config.getClientPortListenBacklog());
                quorumPeer.setZKDatabase(new ZKDatabase(quorumPeer.getTxnFactory()));
                quorumPeer.setQuorumVerifier(config.getQuorumVerifier(), false);
                if (config.getLastSeenQuorumVerifier() != null) {
                    quorumPeer.setLastSeenQuorumVerifier(config.getLastSeenQuorumVerifier(), false);
                }
                quorumPeer.initConfigInZKDatabase();
                quorumPeer.setCnxnFactory(cnxnFactory);
                quorumPeer.setSecureCnxnFactory(secureCnxnFactory);
                quorumPeer.setSslQuorum(config.isSslQuorum());
                quorumPeer.setUsePortUnification(config.shouldUsePortUnification());
                quorumPeer.setLearnerType(config.getPeerType());
                quorumPeer.setSyncEnabled(config.getSyncEnabled());
                quorumPeer.setQuorumListenOnAllIPs(config.getQuorumListenOnAllIPs());
                if (config.sslQuorumReloadCertFiles) {
                    quorumPeer.getX509Util().enableCertFileReloading();
                }
                quorumPeer.setMultiAddressEnabled(config.isMultiAddressEnabled());
                quorumPeer.setMultiAddressReachabilityCheckEnabled(
                        config.isMultiAddressReachabilityCheckEnabled());
                quorumPeer.setMultiAddressReachabilityCheckTimeoutMs(
                        config.getMultiAddressReachabilityCheckTimeoutMs());

                // sets quorum sasl authentication configurations
                quorumPeer.setQuorumSaslEnabled(config.quorumEnableSasl);
                if (quorumPeer.isQuorumSaslAuthEnabled()) {
                    quorumPeer.setQuorumServerSaslRequired(config.quorumServerRequireSasl);
                    quorumPeer.setQuorumLearnerSaslRequired(config.quorumLearnerRequireSasl);
                    quorumPeer.setQuorumServicePrincipal(config.quorumServicePrincipal);
                    quorumPeer.setQuorumServerLoginContext(config.quorumServerLoginContext);
                    quorumPeer.setQuorumLearnerLoginContext(config.quorumLearnerLoginContext);
                }
                quorumPeer.setQuorumCnxnThreadsSize(config.quorumCnxnThreadsSize);
                quorumPeer.initialize();

                if (config.jvmPauseMonitorToRun) {
                    quorumPeer.setJvmPauseMonitor(new JvmPauseMonitor(config));
                }

                quorumPeer.start();
                ZKAuditProvider.addZKStartStopAuditLog();
            } finally {
                try {
                    metricsProvider.stop();
                } catch (Throwable error) {
                    LOG.warn("Error while stopping metrics", error);
                }
            }
        }

        @Override
        protected QuorumPeer getQuorumPeer() throws SaslException {
            return quorumPeer == null ? new MockQuorumPeer() : quorumPeer;
        }
    }
}
