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

package org.apache.zookeeper.server.embedded;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
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
 * Implementation of ZooKeeperServerEmbedded.
 */
class ZooKeeperServerHandleImpl implements ZooKeeperServerHandle {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperServerHandleImpl.class);

    private final QuorumPeerConfig config;
    private QuorumPeerMain server;
    private ZooKeeperServerMain mainsingle;
    private final ExitHandler exitHandler;
    private volatile boolean stopping;

    ZooKeeperServerHandleImpl(
        final Properties p,
        final Path baseDir,
        final ExitHandler exitHandler
    ) throws IOException, QuorumPeerConfig.ConfigException {
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
        LOG.info("ServerID:{}", config.getServerId());
        LOG.info("DataDir:{}", config.getDataDir());
        LOG.info("Servers:{}", config.getServers());
        LOG.info("ElectionPort:{}", config.getElectionPort());
        LOG.info("SyncLimit:{}", config.getSyncLimit());
        LOG.info("PeerType:{}", config.getPeerType());
        LOG.info("Distributed:{}", config.isDistributed());
        LOG.info("SyncEnabled:{}", config.getSyncEnabled());
        LOG.info("MetricsProviderClassName:{}", config.getMetricsProviderClassName());

        for (Map.Entry<Long, QuorumPeer.QuorumServer> server : config.getServers().entrySet()) {
            LOG.info(
                "Server: {} -> addr {} elect {} id={} type {}",
                server.getKey(),
                server.getValue().addr,
                server.getValue().electionAddr,
                server.getValue().id,
                server.getValue().type
            );
        }
    }

    @Override
    public void start() {
        if (exitHandler == ExitHandler.LOG_ONLY) {
            ServiceUtils.setSystemExitProcedure(ServiceUtils.LOG_ONLY);
        } else {
            ServiceUtils.setSystemExitProcedure(ServiceUtils.SYSTEM_EXIT);
        }

        final DatadirCleanupManager purgeMgr;
        final Thread thread;
        if (config.getServers().size() > 1 || config.isDistributed()) {
            LOG.info("Running ZK Server in Quorum MODE");

            server = new QuorumPeerMain();

            // Start and schedule the the purge task
            purgeMgr = new DatadirCleanupManager(
                config.getDataDir(),
                config.getDataLogDir(),
                config.getSnapRetainCount(),
                config.getPurgeInterval()
            );
            purgeMgr.start();

            thread = new Thread(
                () -> {
                    try {
                        server.runFromConfig(config);
                        server.close();
                        LOG.info("ZK server died. Requesting stop on JVM");
                        if (!stopping) {
                            ServiceUtils.requestSystemExit(ExitCode.EXECUTION_FINISHED.getValue());
                        }
                    } catch (Throwable t) {
                        LOG.error("error during server lifecycle", t);
                        server.close();
                        if (!stopping) {
                            ServiceUtils.requestSystemExit(ExitCode.INVALID_INVOCATION.getValue());
                        }
                    }
                },
                "zkservermainrunner"
            );
        } else {
            LOG.info("Running ZK Server in STANDALONE MODE");
            mainsingle = new ZooKeeperServerMain();
            purgeMgr = new DatadirCleanupManager(
                config.getDataDir(),
                config.getDataLogDir(),
                config.getSnapRetainCount(),
                config.getPurgeInterval()
            );
            purgeMgr.start();
            thread = new Thread(
                () -> {
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
                },
                "zkservermainrunner"
            );
        }
        thread.start();
    }

    @Override
    public void close() {
        LOG.info("Stopping ZK Server");
        stopping = true;
        if (mainsingle != null) {
            mainsingle.close();
        }
        if (server != null) {
            server.close();
        }
    }
}
