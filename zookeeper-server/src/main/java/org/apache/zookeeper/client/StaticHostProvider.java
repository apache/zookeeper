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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Most simple HostProvider, resolves on every next() call.
 *
 * Please be aware that although this class doesn't do any DNS caching, there're multiple levels of caching already
 * present across the stack like in JVM, OS level, hardware, etc. The best we could do here is to get the most recent
 * address from the underlying system which is considered up-to-date.
 *
 * It uses HostConnectionManager for all connection management and reconfiguration logic.
 */
@InterfaceAudience.Public
public final class StaticHostProvider implements HostProvider {

    public interface Resolver {

        InetAddress[] getAllByName(String name) throws UnknownHostException;

    }

    private final HostConnectionManager connectionManager;

    /**
     * Constructs a SimpleHostSet.
     *
     * @param serverAddresses
     *            possibly unresolved ZooKeeper server addresses
     * @throws IllegalArgumentException
     *             if serverAddresses is empty or resolves to an empty list
     */
    public StaticHostProvider(Collection<InetSocketAddress> serverAddresses) {
        connectionManager = new HostConnectionManager(serverAddresses, System.currentTimeMillis() ^ this.hashCode(), null);
    }

    /**
     * Constructs a SimpleHostSet with ZKClientConfig.
     *
     * Introduced this new overload in 3.10.0 in order to take advantage of some newly introduced feature flags. Like
     * the shuffle (old) / not to shuffle (new) behavior of DNS resolution.
     *
     * @param serverAddresses
     *            possibly unresolved ZooKeeper server addresses
     * @param clientConfig
     *            ZooKeeper client configuration
     */
    public StaticHostProvider(Collection<InetSocketAddress> serverAddresses, ZKClientConfig clientConfig) {
        connectionManager = new HostConnectionManager(serverAddresses,
                                                        System.currentTimeMillis() ^ this.hashCode(),
                                                        InetAddress::getAllByName,
                                                        clientConfig);
    }

    /**
     * Constructs a SimpleHostSet.
     *
     * Introduced for testing purposes. getAllByName() is a static method of InetAddress, therefore cannot be easily mocked.
     * By abstraction of Resolver interface we can easily inject a mocked implementation in tests.
     *
     * @param serverAddresses
     *              possibly unresolved ZooKeeper server addresses
     * @param resolver
     *              custom resolver implementation
     */
    public StaticHostProvider(Collection<InetSocketAddress> serverAddresses, Resolver resolver) {
        this.connectionManager = new HostConnectionManager(serverAddresses,
                                                    System.currentTimeMillis() ^ this.hashCode(),
                                                    resolver::getAllByName,
                                                    null);
    }

    /**
     * Constructs a SimpleHostSet. This constructor is used from StaticHostProviderTest to produce deterministic test results
     * by initializing sourceOfRandomness with the same seed
     *
     * @param serverAddresses
     *            possibly unresolved ZooKeeper server addresses
     * @param randomnessSeed a seed used to initialize sourceOfRandomness
     * @throws IllegalArgumentException
     *             if serverAddresses is empty or resolves to an empty list
     */
    public StaticHostProvider(Collection<InetSocketAddress> serverAddresses, long randomnessSeed) {
        this.connectionManager = new HostConnectionManager(serverAddresses,
                                                            randomnessSeed,
                                                            InetAddress::getAllByName,
                                                            null);
    }

    @Override
    public boolean updateServerList(Collection<InetSocketAddress> serverAddresses, InetSocketAddress currentHost) {
        return connectionManager.updateServerList(serverAddresses, currentHost);
    }

    @Override
    public int size() {
        return connectionManager.size();
    }

    @Override
    public InetSocketAddress next(long spinDelay) {
        return connectionManager.next(spinDelay);
    }

    @Override
    public void onConnected() {
        connectionManager.onConnected();
    }


    public InetSocketAddress getServerAtIndex(int i) {
        return connectionManager.getServerAtIndex(i);
    }

    public InetSocketAddress getServerAtCurrentIndex() {
        return connectionManager.getServerAtCurrentIndex();
    }
}
