package org.apache.zookeeper.server.embedded;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.security.sasl.SaslException;
import org.apache.zookeeper.server.DatadirCleanupManager;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerMain;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing permissions and limitations under the License.
 */
/**
 * Implementation of ZooKeeperServerEmbedded.
 */
class ZooKeeperServerEmbeddedImpl implements ZooKeeperServerEmbedded {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperServerEmbeddedImpl.class);

    private final QuorumPeerConfig config;
    private QuorumPeerMain maincluster;
    private ZooKeeperServerMain mainsingle;
    private Thread thread;
    private DatadirCleanupManager purgeMgr;
    private final ExitHandler exitHandler;
    private volatile boolean stopping;

    ZooKeeperServerEmbeddedImpl(Properties p, Path baseDir, ExitHandler exitHandler) throws Exception {
        if (!p.containsKey("dataDir")) {
            p.put("dataDir", baseDir.resolve("data").toAbsolutePath().toString());
        }
        Path configFile = Files.createTempFile(baseDir, "zookeeper.configuration", ".properties");
        try (OutputStream oo = Files.newOutputStream(configFile)) {
            p.store(oo, "Automatically generated at every-boot");
        }
        this.exitHandler = exitHandler;
        LOG.info("Current configuration is at {}", configFile.toAbsolutePath());
        config = new QuorumPeerConfig();
        config.parse(configFile.toAbsolutePath().toString());
        LOG.info("ServerID:" + config.getServerId());
        LOG.info("DataDir:" + config.getDataDir());
        LOG.info("Servers:" + config.getServers());
        LOG.info("ElectionPort:" + config.getElectionPort());
        LOG.info("SyncLimit:" + config.getSyncLimit());
        LOG.info("PeerType:" + config.getPeerType());
        LOG.info("Distributed:" + config.isDistributed());
        LOG.info("SyncEnabled:" + config.getSyncEnabled());
        LOG.info("MetricsProviderClassName:" + config.getMetricsProviderClassName());

        for (Map.Entry<Long, QuorumPeer.QuorumServer> server : config.getServers().entrySet()) {
            LOG.info("Server: " + server.getKey() + " -> addr " + server.getValue().addr + " elect "
                    + server.getValue().electionAddr + " id=" + server.getValue().id + " type "
                    + server.getValue().type);
        }
    }

    @Override
    public void start() throws Exception {
        start(Integer.MAX_VALUE);
    }

    @Override
    public void start(long startupTimeout) throws Exception {
        switch (exitHandler) {
            case EXIT:
                ServiceUtils.setSystemExitProcedure(ServiceUtils.SYSTEM_EXIT);
                break;
            case LOG_ONLY:
                ServiceUtils.setSystemExitProcedure(ServiceUtils.LOG_ONLY);
                break;
            default:
                ServiceUtils.setSystemExitProcedure(ServiceUtils.SYSTEM_EXIT);
                break;
        }
        final CompletableFuture<String> started = new CompletableFuture<>();

        if (config.getServers().size() > 1 || config.isDistributed()) {
            LOG.info("Running ZK Server in single Quorum MODE");

            maincluster = new QuorumPeerMain() {
                protected QuorumPeer getQuorumPeer() throws SaslException {
                    return new QuorumPeer() {
                        @Override
                        public void start() {
                            super.start();
                            LOG.info("ZK Server {} started", this);
                            started.complete(null);
                        }
                    };
                }
            };

            // Start and schedule the the purge task
            purgeMgr = new DatadirCleanupManager(config
                    .getDataDir(), config.getDataLogDir(), config
                    .getSnapRetainCount(), config.getPurgeInterval());
            purgeMgr.start();

            thread = new Thread("zkservermainrunner") {
                @Override
                public void run() {
                    try {
                        maincluster.runFromConfig(config);
                        maincluster.close();
                        LOG.info("ZK server died. Requsting stop on JVM");
                        if (!stopping) {
                            ServiceUtils.requestSystemExit(ExitCode.EXECUTION_FINISHED.getValue());
                        }
                    } catch (Throwable t) {
                        LOG.error("error during server lifecycle", t);
                        maincluster.close();
                        if (!stopping) {
                            ServiceUtils.requestSystemExit(ExitCode.INVALID_INVOCATION.getValue());
                        }
                    }
                }
            };
            thread.start();
        } else {
            LOG.info("Running ZK Server in single STANDALONE MODE");
            mainsingle = new ZooKeeperServerMain() {
                @Override
                public void serverStarted() {
                    LOG.info("ZK Server started");
                    started.complete(null);
                }
            };
            purgeMgr = new DatadirCleanupManager(config
                    .getDataDir(), config.getDataLogDir(), config
                    .getSnapRetainCount(), config.getPurgeInterval());
            purgeMgr.start();
            thread = new Thread("zkservermainrunner") {
                @Override
                public void run() {
                    try {
                        ServerConfig cc = new ServerConfig();
                        cc.readFrom(config);
                        LOG.info("ZK server starting");
                        mainsingle.runFromConfig(cc);
                        LOG.info("ZK server died. Requesting stop on JVM");
                        if (!stopping) {
                            ServiceUtils.requestSystemExit(ExitCode.EXECUTION_FINISHED.getValue());
                        }
                    } catch (Throwable t) {
                        LOG.error("error during server lifecycle", t);
                        mainsingle.close();
                        if (!stopping) {
                            ServiceUtils.requestSystemExit(ExitCode.INVALID_INVOCATION.getValue());
                        }
                    }
                }
            };
            thread.start();
        }

        try {
            started.get(startupTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException err) {
            LOG.info("Startup timed out, trying to close");
            close();
            throw err;
        }
    }

    @Override
    public String getConnectionString() {
        if (config.getClientPortAddress() != null) {
            String raw = config.getClientPortAddress().getHostString() + ":" + config.getClientPortAddress().getPort();
            return raw.replace("0.0.0.0", "localhost");
        } else {
            throw new IllegalStateException("No client address is configured");
        }
    }

    @Override
    public String getSecureConnectionString() {
        if (config.getSecureClientPortAddress() != null) {
            String raw = config.getSecureClientPortAddress().getHostString() + ":" + config.getSecureClientPortAddress().getPort();
            return raw.replace("0.0.0.0", "localhost");
        } else {
            throw new IllegalStateException("No client address is configured");
        }
    }

    @Override
    public void close() {
        LOG.info("Stopping ZK Server");
        stopping = true;
        if (mainsingle != null) {
            mainsingle.close();
        }
        if (maincluster != null) {
            maincluster.close();
        }
    }
}
