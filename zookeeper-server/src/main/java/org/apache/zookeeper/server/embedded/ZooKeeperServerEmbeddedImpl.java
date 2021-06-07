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
 * Implementation of ZooKeeperServerEmbedded that uses the {@link EmbeddedZooKeeper} API. Preserved
 * for backwards compatibility.
 */
//@Deprecated
class ZooKeeperServerEmbeddedImpl implements ZooKeeperServerEmbedded {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperServerEmbeddedImpl.class);

    private final EmbeddedConfig config;
    private final ExitHandler exitHandler;
    private ZooKeeperServerHandle handle;

    ZooKeeperServerEmbeddedImpl(
        final Properties properties,
        final Path baseDir,
        final ExitHandler exitHandler
    ) throws IOException {
        this.config = EmbeddedConfig.fromProperties(properties, baseDir);
        this.exitHandler = exitHandler;
    }

    @Override
    public void start() throws Exception {
        if (exitHandler == ExitHandler.LOG_ONLY) {
            this.handle = EmbeddedZooKeeper.startTest(this.config);
        } else {
            this.handle = EmbeddedZooKeeper.startProd(this.config);
        }
    }

    @Override
    public void close() {
        LOG.info("Stopping ZK Server");
        try {
            this.handle.close();
        } catch (final Exception e) {
            LOG.error("Error shutting ZooKeeper down.", e);
        }
    }
}
