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
import java.util.Properties;

/**
 * This interface represents a ZooKeeper configuration in the form required to produce a
 * {@link ZooKeeperServerHandle}. The exact methods and behavior of this interface are subject to
 * change without notice as the ZooKeeper internal API changes, so please use the provided builders
 * and factory methods to construct valid instances.
 */
public interface EmbeddedConfig {
    /**
     * Used by {@link EmbeddedZooKeeper#startTest(EmbeddedConfig)} and
     * {@link EmbeddedZooKeeper#startProd(EmbeddedConfig)} to start a new ZooKeeper server.
     * @return
     */
    String[] mainArgs();

    /**
     * Gets a builder to build the configuration from scratch. This requires you to specify all the
     * required configuration parameters as specified in the documentation. It will usually make
     * more sense to start with a baseline file.
     * @return A from-scratch builder
     * @see #builder(Path)
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Gets a builder using the specified config file as a baseline. This facilitates specifying
     * only the config items that need to be determined at runtime.
     * @param baseConfig The path to the ZooKeeper config file to use as a baseline
     * @return A builder with values preloaded from baseConfig
     * @throws IOException If there is an error reading baseConfig
     */
    static Builder builder(final Path baseConfig) throws IOException {
        final Properties properties = new Properties();
        properties.load(Files.newBufferedReader(baseConfig));
        return new Builder(properties);
    }

    /**
     * Gets a builder using the specified {@link Properties} instance as a baseline.
     * @param properties The {@link Properties} to use as a baseline
     * @return A builder with values preloaded from the supplied {@link Properties}
     */
    static Builder builder(final Properties properties) {
        return new Builder(properties);
    }

    /**
     * Uses the specified file as the configuration. In contrast to {@link #builder(Path)}, this
     * method gives you a complete {@link EmbeddedConfig} that cannot be further modified.
     * @param config The path to the config file
     * @return The {@link EmbeddedConfig}
     */
    static EmbeddedConfig fromFile(final Path config) {
        return () -> new String[] {config.toAbsolutePath().toString()};
    }

    /**
     * Uses the specified properties to write a configuration file in the directory specified by
     * baseDir. This method is primarily to support backwards compatibility of the old API, but
     * there are no hacks in its implementation, so it should be fine to use if you have a use-case
     * that can benefit from it.
     * @param properties The properties to build the config with
     * @param baseDir The parent directory to create the config file in
     * @return The {@link EmbeddedConfig}
     */
    static EmbeddedConfig fromProperties(
        final Properties properties,
        final Path baseDir
    ) throws IOException {
        return new Builder(properties).build(baseDir);
    }

    /**
     * Assists in building a valid config.
     * Based on https://zookeeper.apache.org/doc/r3.7.0/zookeeperAdmin.html#sc_configuration
     */
    class Builder {
        private final Properties properties;

        Builder() {
            this(new Properties());
        }

        Builder(final Properties properties) {
            this.properties = properties;
        }

        /**
         * The port to listen for client connections; that is, the port that clients attempt to
         * connect to.
         * @param clientPort The port
         * @return This builder
         */
        public Builder setClientPort(final long clientPort) {
            this.properties.put("clientPort", clientPort);
            return this;
        }

        /**
         * The port to listen on for secure client connections using SSL.
         * <p>clientPort specifies the port for plaintext connections while secureClientPort
         * specifies the port for SSL connections. Specifying both enables mixed-mode while omitting
         * either will disable that mode. Note that SSL feature will be enabled when user plugs-in
         * zookeeper.serverCnxnFactory, zookeeper.clientCnxnSocket as Netty.</p>
         * @param secureClientPort The port
         * @return This builder
         */
        public Builder setSecureClientPort(final long secureClientPort) {
            this.properties.put("secureClientPort", secureClientPort);
            return this;
        }

        /**
         * The port to listen for observer connections; that is, the port that observers attempt to
         * connect to.
         * <p>If this property is set, then the server will host observer connections when in
         * follower mode in addition to when in leader mode and correspondingly attempt to connect
         * to any voting peer when in observer mode.</p>
         * @param observerMasterPort The port
         * @return This builder
         */
        public Builder setObserverMasterPort(final long observerMasterPort) {
            this.properties.put("observerMasterPort", observerMasterPort);
            return this;
        }

        /**
         * The location where ZooKeeper will store the in-memory database snapshots and, unless
         * specified otherwise, the transaction log of updates to the database.
         * <p>Be careful where you put the transaction log. A dedicated transaction log device is
         * key to consistent good performance. Putting the log on a busy device will adversely
         * affect performance.</p>
         * @param dataDir The path to the desired directory
         * @return This builder
         */
        public Builder setDataDir(final Path dataDir) {
            this.properties.put("dataDir", dataDir.toAbsolutePath().toString());
            return this;
        }

        /**
         * The length of a single tick, which is the basic time unit used by ZooKeeper, as measured
         * in milliseconds.
         * <p>It is used to regulate heartbeats, and timeouts. For example, the minimum session
         * timeout will be two ticks.</p>
         * @param millis Time in milliseconds
         * @return This builder
         */
        public Builder setTickTime(final long millis) {
            this.properties.put("tickTime", millis);
            return this;
        }

        /**
         * This option will direct the machine to write the transaction log to the dataLogDir rather
         * than the dataDir.
         * <p>This allows a dedicated log device to be used, and helps avoid competition between
         * logging and snapshots.</p>
         * <p>Having a dedicated log device has a large impact on throughput and stable latencies.
         * It is highly recommended to dedicate a log device and set dataLogDir to point to a
         * directory on that device, and then make sure to point dataDir to a directory not residing
         * on that device.</p>
         * @param dataLogDir The path to the desired directory
         * @return This builder
         */
        public Builder setDataLogDir(final Path dataLogDir) {
            this.properties.put("dataLogDir", dataLogDir.toAbsolutePath().toString());
            return this;
        }

        /**
         * The maximum number of outstanding requests that may be queued at one time.
         * <p>Clients can submit requests faster than ZooKeeper can process them, especially if
         * there are a lot of clients. To prevent ZooKeeper from running out of memory due to queued
         * requests, ZooKeeper will throttle clients so that there is no more than
         * globalOutstandingLimit outstanding requests in the system.</p>
         * <p>The default limit is 1,000.</p>
         * @param globalOutstandingLimit The limit
         * @return This builder
         */
        public Builder setGlobalOutstandingLimit(final long globalOutstandingLimit) {
            this.properties.put("globalOutstandingLimit", globalOutstandingLimit);
            return this;
        }

        /**
         * To avoid seeks ZooKeeper allocates space in the transaction log file in blocks of
         * preAllocSize kilobytes.
         * <p>The default block size is 64M. One reason for changing the size of the blocks is to
         * reduce the block size if snapshots are taken more often.</p>
         * @param kilobytes The size in kilobytes of transaction log blocks
         * @return This builder
         * @see #setSnapCount(long)
         * @see #setSnapSizeLimitInKb(long)
         */
        public Builder setPreAllocSize(final long kilobytes) {
            this.properties.put("preAllocSize", kilobytes);
            return this;
        }

        /**
         * ZooKeeper records its transactions using snapshots and a transaction log (think
         * write-ahead log).
         * <p>The number of transactions recorded in the transaction log before a snapshot can be
         * taken (and the transaction log rolled) is determined by snapCount. In order to prevent
         * all of the machines in the quorum from taking a snapshot at the same time, each ZooKeeper
         * server will take a snapshot when the number of transactions in the transaction log
         * reaches a runtime generated random value in the [snapCount/2+1, snapCount] range.The
         * default snapCount is 100,000.</p>
         * @param count The number of transaction required to be in the log before a snapshot can be
         *  taken
         * @return This builder
         */
        public Builder setSnapCount(final long count) {
            this.properties.put("snapCount", count);
            return this;
        }

        /**
         * Zookeeper maintains an in-memory list of last committed requests for fast synchronization
         * with followers when the followers are not too behind. This improves sync performance when
         * your snapshots are large (>100,000). The default commitLogCount value is 500.
         * @param count The number of committed request to keep in memory for fast sync
         * @return This builder
         */
        public Builder setCommitLogCount(final long count) {
            this.properties.put("commitLogCount", count);
            return this;
        }

        /**
         * ZooKeeper records its transactions using snapshots and a transaction log (think
         * write-ahead log). The total size in bytes allowed in the set of transactions recorded in
         * the transaction log before a snapshot can be taken (and the transaction log rolled) is
         * determined by snapSizeLimitInKb. In order to prevent all of the machines in the quorum
         * from taking a snapshot at the same time, each ZooKeeper server will take a snapshot when
         * the size in bytes of the set of transactions in the transaction log reaches a runtime
         * generated random value in the [snapSize/2+1, snapSize] range. Each file system has a
         * minimum standard file size and in order this feature to work, the number chosen must be
         * larger than that value. The default snapSizeLimitInKb is 4,194,304 (4GB).
         * A non-positive value will disable the feature.
         * @param kilobytes The maximum size in KB of a snapshot
         * @return This builder
         */
        public Builder setSnapSizeLimitInKb(final long kilobytes) {
            this.properties.put("snapSizeLimitInKb", kilobytes);
            return this;
        }

        /**
         * Writes out a configuration file in the directory at the specified path and returns an
         * instance of {@link EmbeddedConfig} to use in
         * {@link EmbeddedZooKeeper#startProd(EmbeddedConfig)} or
         * {@link EmbeddedZooKeeper#startTest(EmbeddedConfig)}.
         * @param baseDir The path to the desired parent directory of the config
         * @return The {@link EmbeddedConfig}
         * @throws IOException If there is an issue writing the file
         */
        public EmbeddedConfig build(final Path baseDir) throws IOException {
            if (
                !this.properties.containsKey("clientPort")
                && !this.properties.containsKey("secureClientPort")
            ) {
                this.setClientPort(2181);
            }
            if (!this.properties.containsKey("dataDir")) {
                this.setDataDir(baseDir.resolve("data"));
            }
            final Path configFile = Files.createTempFile(
                baseDir,
                "zookeeper.configuration",
                ".properties"
            );
            try (final OutputStream oo = Files.newOutputStream(configFile)) {
                this.properties.store(oo, "Automatically generated at every-boot");
            }
            return () -> new String[] {configFile.toAbsolutePath().toString()};
        }
    }
}
