package org.apache.zookeeper.server.embedded;

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

import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * This API allows you to start a ZooKeeper server node from Java code <p>
 * The server will run inside the same process.<p>
 * Typical usecases are:
 * <ul>
 * <li>Running automated tests</li>
 * <li>Launch ZooKeeper server with a Java based service management system</li>
 * </ul>
 * <p>
 * Please take into consideration that in production usually it is better to not run the client
 * together with the server in order to avoid race conditions, especially around how ephemeral nodes work.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public interface ZooKeeperServerEmbedded extends AutoCloseable {
    /**
     * Builder for ZooKeeperServerEmbedded.
     */
    class ZookKeeperServerEmbeddedBuilder {

        private Path baseDir;
        private Properties configuration;
        private ExitHandler exitHandler = ExitHandler.EXIT;

        /**
         * Base directory of the server.
         * The system will create a temporary configuration file inside this directory.
         * Please remember that dynamic configuration files wil be saved into this directory by default.
         * <p>
         * If you do not set a 'dataDir' configuration entry the system will use a subdirectory of baseDir.
         * @param baseDir
         * @return the builder
         */
        public ZookKeeperServerEmbeddedBuilder baseDir(Path baseDir) {
            this.baseDir = Objects.requireNonNull(baseDir);
            return this;
        }

        /**
         * Set the contents of the main configuration as it would be in zk_server.conf file.
         * @param configuration the configuration
         * @return the builder
         */
        public ZookKeeperServerEmbeddedBuilder configuration(Properties configuration) {
            this.configuration = Objects.requireNonNull(configuration);
            return this;
        }

        /**
         * Set the behaviour in case of hard system errors, see {@link ExitHandler}.
         * @param exitHandler the handler
         * @return the builder
         */
        public ZookKeeperServerEmbeddedBuilder exitHandler(ExitHandler exitHandler) {
            this.exitHandler = Objects.requireNonNull(exitHandler);
            return this;
        }

        /**
         * Validate the configuration and create the server, without starting it.
         * @return the new server
         * @throws Exception
         * @see #start()
         */
        public ZooKeeperServerEmbedded build() throws Exception {
            if (baseDir == null) {
                throw new IllegalStateException("baseDir is null");
            }
            if (configuration == null) {
                throw new IllegalStateException("configuration is null");
            }
            return new ZooKeeperServerEmbeddedImpl(configuration, baseDir, exitHandler);
        }
    }

    static ZookKeeperServerEmbeddedBuilder builder() {
        return new ZookKeeperServerEmbeddedBuilder();
    }

    /**
     * Start the server.
     * @throws Exception
     */
    void start() throws Exception;

    /**
     * Start the server
     * @param startupTimeout time to wait in millis for the server to start
     * @throws Exception
     */
    void start(long startupTimeout) throws Exception;

    /**
     * Get a connection string useful for the client.
     * @return the connection string
     * @throws Exception in case the connection string is not available
     */
    String getConnectionString() throws Exception;

    String getSecureConnectionString() throws Exception;

    /**
     * Shutdown gracefully the server and wait for resources to be released.
     */
    @Override
    void close();

}
