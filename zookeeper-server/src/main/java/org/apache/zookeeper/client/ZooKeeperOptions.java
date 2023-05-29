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
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.function.Function;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.apache.zookeeper.Watcher;

/**
 * Options to construct {@link org.apache.zookeeper.ZooKeeper} and its derivations.
 *
 * <p>Caution: This class is not intended to be used in sophisticated environments(say, serialization, comparison). Only
 * its type, getters and {@link #toBuilder()} are considered public.
 */
@InterfaceAudience.LimitedPrivate("ZooKeeper")
@InterfaceStability.Evolving
public class ZooKeeperOptions {
    private final String connectString;
    private final int sessionTimeout;
    private final Watcher defaultWatcher;
    private final Function<Collection<InetSocketAddress>, HostProvider> hostProvider;
    private final boolean canBeReadOnly;
    private final long sessionId;
    private final byte[] sessionPasswd;
    private final ZKClientConfig clientConfig;

    @InterfaceAudience.Private
    ZooKeeperOptions(String connectString,
                     int sessionTimeout,
                     Watcher defaultWatcher,
                     Function<Collection<InetSocketAddress>, HostProvider> hostProvider,
                     boolean canBeReadOnly,
                     long sessionId,
                     byte[] sessionPasswd,
                     ZKClientConfig clientConfig) {
        this.connectString = connectString;
        this.sessionTimeout = sessionTimeout;
        this.hostProvider = hostProvider;
        this.defaultWatcher = defaultWatcher;
        this.canBeReadOnly = canBeReadOnly;
        this.sessionId = sessionId;
        this.sessionPasswd = sessionPasswd;
        this.clientConfig = clientConfig;
    }

    @InterfaceAudience.Public
    public String getConnectString() {
        return connectString;
    }

    @InterfaceAudience.Public
    public int getSessionTimeout() {
        return sessionTimeout;
    }

    @InterfaceAudience.Public
    public Watcher getDefaultWatcher() {
        return defaultWatcher;
    }

    @InterfaceAudience.Public
    public Function<Collection<InetSocketAddress>, HostProvider> getHostProvider() {
        return hostProvider;
    }

    @InterfaceAudience.Public
    public boolean isCanBeReadOnly() {
        return canBeReadOnly;
    }

    @InterfaceAudience.Public
    public long getSessionId() {
        return sessionId;
    }

    @InterfaceAudience.Public
    @SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public byte[] getSessionPasswd() {
        return sessionPasswd;
    }

    @InterfaceAudience.Public
    public ZKClientConfig getClientConfig() {
        return clientConfig;
    }

    /**
     * Creates a {@link ZooKeeperBuilder} with these options.
     */
    @InterfaceAudience.Public
    public ZooKeeperBuilder toBuilder() {
        return new ZooKeeperBuilder(connectString, sessionTimeout)
            .withDefaultWatcher(defaultWatcher)
            .withHostProvider(hostProvider)
            .withCanBeReadOnly(canBeReadOnly)
            .withSession(sessionId, sessionPasswd)
            .withClientConfig(clientConfig);
    }
}
