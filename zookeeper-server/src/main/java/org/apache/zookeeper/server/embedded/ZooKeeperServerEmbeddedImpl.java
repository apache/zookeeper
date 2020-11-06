package org.apache.zookeeper.server.embedded;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.apache.zookeeper.server.DatadirCleanupManager;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerMain;
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
 * Implementation
 */
@SuppressFBWarnings("DM_EXIT")
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
        LOG.info("ElectionAlg:" + config.getElectionAlg());
        LOG.info("ElectionPort:" + config.getElectionPort());
        LOG.info("SyncLimit:" + config.getSyncLimit());
        LOG.info("PeerType:" + config.getPeerType());
        LOG.info("Distributed:" + config.isDistributed());
        LOG.info("SyncEnabled:" + config.getSyncEnabled());
        LOG.info("SnapRetainCount:" + config.getSnapRetainCount());
        LOG.info("PurgeInterval:" + config.getPurgeInterval());
        LOG.info("MetricsProviderClassName:" + config.getMetricsProviderClassName());

        for (Map.Entry<Long, QuorumPeer.QuorumServer> server : config.getServers().entrySet()) {
            LOG.info("Server: " + server.getKey() + " -> addr " + server.getValue().addr + " elect "
                    + server.getValue().electionAddr + " id=" + server.getValue().id + " type "
                    + server.getValue().type);
        }
    }

    @Override
    public void start() throws Exception {

        if (config.getServers().size() > 1 || config.isDistributed()) {
            LOG.info("Running ZK Server in single Quorum MODE");

            maincluster = new QuorumPeerMain();

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
                            exitHandler.systemExit(0);
                        }
                    } catch (Throwable t) {
                        LOG.error("error during server lifecycle", t);
                        maincluster.close();
                        if (!stopping) {
                            exitHandler.systemExit(1);
                        }
                    }
                }
            };
            thread.start();
        } else {
            LOG.info("Running ZK Server in single STANDALONE MODE");
            mainsingle = new ZooKeeperServerMain();
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
                            exitHandler.systemExit(0);
                        }
                    } catch (Throwable t) {
                        LOG.error("error during server lifecycle", t);
                        mainsingle.close();
                        if (!stopping) {
                            exitHandler.systemExit(1);
                        }
                    }
                }
            };
            thread.start();
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
