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

package org.apache.zookeeper.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.function.Function;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.admin.ZooKeeperAdmin;

/**
 * Builder to construct {@link ZooKeeper} and {@link ZooKeeperAdmin}.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class ZooKeeperBuilder {
    private final String connectString;
    private final int sessionTimeout;
    private Function<Collection<InetSocketAddress>, HostProvider> hostProvider;
    private Watcher defaultWatcher;
    private boolean canBeReadOnly = false;
    private long sessionId = 0;
    private byte[] sessionPasswd;
    private ZKClientConfig clientConfig;

    /**
     * Creates a builder with given connect string and session timeout.
     *
     * @param connectString
     *            comma separated host:port pairs, each corresponding to a zk
     *            server. e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"
     *            If the optional chroot suffix is used the example would look
     *            like: "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002/app/a"
     *            where the client would be rooted at "/app/a" and all paths
     *            would be relative to this root - ie getting/setting/etc...
     *            "/foo/bar" would result in operations being run on
     *            "/app/a/foo/bar" (from the server perspective).
     * @param sessionTimeoutMs
     *            session timeout in milliseconds
     */
    public ZooKeeperBuilder(String connectString, int sessionTimeoutMs) {
        this.connectString = connectString;
        this.sessionTimeout = sessionTimeoutMs;
    }

    /**
     * Specified watcher to receive state changes, and node events if attached later.
     *
     * @param watcher
     *            a watcher object which will be notified of state changes, may
     *            also be notified for node events
     * @return this
     */
    public ZooKeeperBuilder withDefaultWatcher(Watcher watcher) {
        this.defaultWatcher = watcher;
        return this;
    }

    /**
     * Specifies a function to construct a {@link HostProvider} with initial server addresses from connect string.
     *
     * @param hostProvider
     *            use this as HostProvider to enable custom behaviour.
     * @return this
     */
    public ZooKeeperBuilder withHostProvider(Function<Collection<InetSocketAddress>, HostProvider> hostProvider) {
        this.hostProvider = hostProvider;
        return this;
    }

    /**
     * Specifies whether the created client is allowed to go to read-only mode in case of partitioning.
     *
     * @param canBeReadOnly
     *            whether the created client is allowed to go to
     *            read-only mode in case of partitioning. Read-only mode
     *            basically means that if the client can't find any majority
     *            servers but there's partitioned server it could reach, it
     *            connects to one in read-only mode, i.e. read requests are
     *            allowed while write requests are not. It continues seeking for
     *            majority in the background.
     * @return this
     * @since 3.4
     */
    public ZooKeeperBuilder withCanBeReadOnly(boolean canBeReadOnly) {
        this.canBeReadOnly = canBeReadOnly;
        return this;
    }

    /**
     * Specifies session id and password in session reestablishment.
     *
     * @param sessionId
     *            session id to use if reconnecting, otherwise 0 to open new session
     * @param sessionPasswd
     *            password for this session
     * @return this
     * @see ZooKeeper#getSessionId()
     * @see ZooKeeper#getSessionPasswd()
     */
    @SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public ZooKeeperBuilder withSession(long sessionId, byte[] sessionPasswd) {
        this.sessionId = sessionId;
        this.sessionPasswd = sessionPasswd;
        return this;
    }

    /**
     * Specifies the client config used to construct ZooKeeper instances.
     *
     * @param clientConfig
     *            passing this conf object gives each client the flexibility of
     *            configuring properties differently compared to other instances
     * @return this
     * @since 3.5.2
     */
    public ZooKeeperBuilder withClientConfig(ZKClientConfig clientConfig) {
        this.clientConfig = clientConfig;
        return this;
    }

    /**
     * Creates a {@link ZooKeeperOptions} with configured options.
     *
     * @apiNote helper to delegate existing constructors to {@link ZooKeeper#ZooKeeper(ZooKeeperOptions)}
     */
    @InterfaceAudience.Private
    public ZooKeeperOptions toOptions() {
        return new ZooKeeperOptions(
            connectString,
            sessionTimeout,
            defaultWatcher,
            hostProvider,
            canBeReadOnly,
            sessionId,
            sessionPasswd,
            clientConfig
        );
    }

    /**
     * Constructs an instance of {@link ZooKeeper}.
     *
     * @return an instance of {@link ZooKeeper}
     * @throws IOException from constructor of {@link ZooKeeper}
     */
    public ZooKeeper build() throws IOException {
        return new ZooKeeper(toOptions());
    }

    /**
     * Constructs an instance of {@link ZooKeeperAdmin}.
     *
     * @return an instance of {@link ZooKeeperAdmin}
     * @throws IOException from constructor of {@link ZooKeeperAdmin}
     */
    public ZooKeeperAdmin buildAdmin() throws IOException {
        return new ZooKeeperAdmin(toOptions());
    }
}
