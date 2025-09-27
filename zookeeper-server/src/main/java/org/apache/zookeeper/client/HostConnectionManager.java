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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages ZooKeeper server connections with round-robin selection and load balancing during
 * cluster reconfiguration.
 *
 * <p><strong>Key Features:</strong></p>
 * <ul>
 * <li>Round-robin server selection with IP address resolution</li>
 * <li>Migrates clients to balance load when servers are added or removed</li>
 * <li>Probabilistic client migration during server list updates</li>
 * </ul>
 *
 * <p><strong>Reconfiguration Behavior:</strong></p>
 * When server list changes, enters "reconfigMode" and calculates migration probabilities
 * to balance load between old and new servers.
 *
 * <p>See <a href="https://issues.apache.org/jira/browse/ZOOKEEPER-1355">ZOOKEEPER-1355</a>
 * for more details.</p>
 */
@InterfaceAudience.Private
public final class HostConnectionManager {

    /**
     * Interface for address resolution to support testing and different resolution strategies.
     */
    public interface Resolver {
        InetAddress[] getAllByName(String name) throws UnknownHostException;
    }

    private static final Logger LOG = LoggerFactory.getLogger(HostConnectionManager.class);

    private List<InetSocketAddress> serverAddresses;
    private final ZKClientConfig clientConfig;
    private final Resolver resolver;
    private final Random sourceOfRandomness;
    private int lastIndex;
    private int currentIndex;

    /**
     * The following fields are used to migrate clients during reconfiguration
     */
    private boolean reconfigMode = false;
    private final List<InetSocketAddress> oldServers = new ArrayList<>(5);
    private final List<InetSocketAddress> newServers = new ArrayList<>(5);
    private int currentIndexOld = -1;
    private int currentIndexNew = -1;
    private float pOld, pNew;

    /**
     * Constructs a HostConnectionManager with default resolver.
     *
     * @param serverAddresses
     *              possibly unresolved ZooKeeper server addresses
     *  @param randomnessSeed
     *              a seed used to initialize sourceOfRandomness
     * @param clientConfig
     *              ZooKeeper client configuration
     * @throws IllegalArgumentException
     *              if serverAddresses is empty
     */
    public HostConnectionManager(Collection<InetSocketAddress> serverAddresses, long randomnessSeed, ZKClientConfig clientConfig) {
        this(serverAddresses, randomnessSeed, InetAddress::getAllByName, clientConfig);
    }

    /**
     * Constructs a HostConnectionManager with custom resolver.
     *
     * @param serverAddresses
     *              possibly unresolved ZooKeeper server addresses
     * @param randomnessSeed
     *              a seed used to initialize sourceOfRandomness
     * @param resolver
     *              custom resolver implementation
     * @param clientConfig
     *              ZooKeeper client configuration
     * @throws IllegalArgumentException
     *              if serverAddresses is empty
     */
    public HostConnectionManager(Collection<InetSocketAddress> serverAddresses,
                                long randomnessSeed,
                                Resolver resolver,
                                ZKClientConfig clientConfig) {
        this.clientConfig = clientConfig == null ? new ZKClientConfig() : clientConfig;
        this.sourceOfRandomness = new Random(randomnessSeed);
        this.resolver = resolver;

        if (serverAddresses.isEmpty()) {
            throw new IllegalArgumentException("A HostProvider may not be empty!");
        }

        this.serverAddresses = shuffle(serverAddresses);
        currentIndex = -1;
        lastIndex = -1;

        LOG.info("HostConnectionManager initialized with {} servers", serverAddresses.size());
    }

    /**
     * Update the list of servers. This returns true if changing connections is necessary for load-balancing, false
     * otherwise. Changing connections is necessary if one of the following holds:
     * a) the host to which this client is currently connected is not in serverAddresses.
     *    Otherwise (if currentHost is in the new list serverAddresses):
     * b) the number of servers in the cluster is increasing - in this case the load on currentHost should decrease,
     *    which means that SOME of the clients connected to it will migrate to the new servers. The decision whether
     *    this client migrates or not (i.e., whether true or false is returned) is probabilistic so that the expected
     *    number of clients connected to each server is the same.
     *
     * If true is returned, the function sets pOld and pNew that correspond to the probability to migrate to one of the
     * new servers in serverAddresses or one of the old servers (migrating to one of the old servers is done only
     * if our client's currentHost is not in serverAddresses). See nextHostInReconfigMode for the selection logic.
     *
     * See <a href="https://issues.apache.org/jira/browse/ZOOKEEPER-1355">ZOOKEEPER-1355</a>
     * for the protocol and its evaluation.
     *
     * @param serverAddresses
     *              new host list
     * @param currentHost
     *              the host to which this client is currently connected
     * @return true if changing connections is necessary for load-balancing, false otherwise
     */
    synchronized boolean updateServerList(Collection<InetSocketAddress> serverAddresses, InetSocketAddress currentHost) {
        List<InetSocketAddress> shuffledList = shuffle(serverAddresses);
        if (shuffledList.isEmpty()) {
            throw new IllegalArgumentException("The server list may not be empty!");
        }

        int oldSize = this.serverAddresses.size();
        int newSize = shuffledList.size();

        // Check if client's current server is in the new list of servers
        boolean myServerInNewConfig = false;
        InetSocketAddress myServer = currentHost;

        // choose "current" server according to the client rebalancing algorithm
        if (reconfigMode) {
            myServer = next(0);
        }

        // if the client is not currently connected to any server
        if (myServer == null) {
            // reconfigMode = false (next shouldn't return null).
            if (lastIndex >= 0) {
                // take the last server to which we were connected
                myServer = this.serverAddresses.get(lastIndex);
            } else {
                // take the first server on the list
                myServer = this.serverAddresses.get(0);
            }
        }

        for (InetSocketAddress addr : shuffledList) {
            if (addr.getPort() == myServer.getPort()
                && ((addr.getAddress() != null
                     && myServer.getAddress() != null
                     && addr.getAddress().equals(myServer.getAddress()))
                    || addr.getHostString().equals(myServer.getHostString()))) {
                myServerInNewConfig = true;
                break;
            }
        }

        reconfigMode = true;

        newServers.clear();
        oldServers.clear();
        // Divide the new servers into oldServers that were in the previous list
        // and newServers that were not in the previous list
        for (InetSocketAddress address : shuffledList) {
            if (this.serverAddresses.contains(address)) {
                oldServers.add(address);
            } else {
                newServers.add(address);
            }
        }

        int numOld = oldServers.size();
        int numNew = newServers.size();

        // number of servers increased
        if (numOld + numNew > this.serverAddresses.size()) {
            if (myServerInNewConfig) {
                // my server is in new config, but load should be decreased.
                // Need to decide if this client
                // is moving to one of the new servers
                if (sourceOfRandomness.nextFloat() <= (1 - ((float) this.serverAddresses.size()) / (numOld + numNew))) {
                    pNew = 1;
                    pOld = 0;
                } else {
                    // do nothing special - stay with the current server
                    reconfigMode = false;
                }
            } else {
                // my server is not in new config, and load on old servers must
                // be decreased, so connect to
                // one of the new servers
                pNew = 1;
                pOld = 0;
            }
        } else { // number of servers stayed the same or decreased
            if (myServerInNewConfig) {
                // my server is in new config, and load should be increased, so
                // stay with this server and do nothing special
                reconfigMode = false;
            } else {
                pOld = ((float) (numOld * (this.serverAddresses.size() - (numOld + numNew))))
                       / ((numOld + numNew) * (this.serverAddresses.size() - numOld));
                pNew = 1 - pOld;
            }
        }

        if (!reconfigMode) {
            currentIndex = shuffledList.indexOf(getServerAtCurrentIndex());
        } else {
            currentIndex = -1;
        }
        this.serverAddresses = shuffledList;
        currentIndexOld = -1;
        currentIndexNew = -1;
        lastIndex = currentIndex;

        LOG.info("Server list updated: oldSize={}, newSize={}, reconfigMode={}", oldSize, newSize, reconfigMode);

        return reconfigMode;
    }

    /**
     * Get the next server to connect to.
     *
     * @param spinDelay milliseconds to wait if all hosts have been tried once
     * @return the next server address to connect to
     */
    InetSocketAddress next(long spinDelay) {
        boolean needToSleep = false;
        InetSocketAddress addr;

        synchronized (this) {
            if (reconfigMode) {
                addr = nextHostInReconfigMode();
                if (addr != null) {
                    currentIndex = serverAddresses.indexOf(addr);
                    return resolve(addr);
                }
                //tried all servers and couldn't connect
                reconfigMode = false;
                needToSleep = (spinDelay > 0);
            }
            ++currentIndex;
            if (currentIndex == serverAddresses.size()) {
                currentIndex = 0;
            }
            addr = serverAddresses.get(currentIndex);
            needToSleep = needToSleep || (currentIndex == lastIndex && spinDelay > 0);
            if (lastIndex == -1) {
                // We don't want to sleep on the first ever connect attempt.
                lastIndex = 0;
            }
        }

        if (needToSleep) {
            try {
                Thread.sleep(spinDelay);
            } catch (InterruptedException e) {
                LOG.warn("Unexpected exception", e);
            }
        }

        return resolve(addr);
    }

    /**
     * Notify that a connection has been established successfully.
     */
    synchronized void onConnected() {
        lastIndex = currentIndex;
        reconfigMode = false;
    }

    /**
     * Get the server at the specified index.
     *
     * @param i the index
     * @return the server address at the index, or null if index is out of bounds
     */
    synchronized InetSocketAddress getServerAtIndex(int i) {
        if (i < 0 || i >= serverAddresses.size()) {
            return null;
        }
        return serverAddresses.get(i);
    }

    /**
     * Get the server at the current index.
     *
     * @return the server address at the current index
     */
    synchronized InetSocketAddress getServerAtCurrentIndex() {
        return getServerAtIndex(currentIndex);
    }

    /**
     * Get the number of servers in the list.
     *
     * @return the number of servers
     */
    synchronized int size() {
        return serverAddresses.size();
    }

    /**
     * Resolves an InetSocketAddress to a concrete address.
     *
     * @param address
     *              the address to resolve
     * @return resolved address or original address if resolution fails
     */
    private InetSocketAddress resolve(InetSocketAddress address) {
        try {
            String curHostString = address.getHostString();
            List<InetAddress> resolvedAddresses = new ArrayList<>(Arrays.asList(this.resolver.getAllByName(curHostString)));
            if (resolvedAddresses.isEmpty()) {
                return address;
            }
            if (clientConfig.isShuffleDnsResponseEnabled()) {
                Collections.shuffle(resolvedAddresses);
            }
            return new InetSocketAddress(resolvedAddresses.get(0), address.getPort());
        } catch (UnknownHostException e) {
            LOG.error("Unable to resolve address: {}", address, e);
            return address;
        }
    }

    /**
     * Shuffles the server addresses using the internal random source.
     *
     * @param serverAddresses
     *              collection of server addresses to shuffle
     * @return shuffled list of server addresses
     */
    private List<InetSocketAddress> shuffle(Collection<InetSocketAddress> serverAddresses) {
        List<InetSocketAddress> tmpList = new ArrayList<>(serverAddresses.size());
        tmpList.addAll(serverAddresses);
        Collections.shuffle(tmpList, sourceOfRandomness);
        return tmpList;
    }

    /**
     * Get the next server to connect to, when in "reconfigMode", which means that
     * you've just updated the server list, and now trying to find some server to connect to.
     * Once onConnected() is called, reconfigMode is set to false. Similarly, if we tried to connect
     * to all servers in new config and failed, reconfigMode is set to false.
     *
     * While in reconfigMode, we should connect to a server in newServers with probability pNew and to servers in
     * oldServers with probability pOld (which is just 1-pNew). If we tried out all servers in either oldServers
     * or newServers we continue to try servers from the other set, regardless of pNew or pOld. If we tried all servers
     * we give up and go back to the normal round robin mode
     *
     * When called, this should be protected by synchronized(this)
     */
    private InetSocketAddress nextHostInReconfigMode() {
        boolean takeNew = (sourceOfRandomness.nextFloat() <= pNew);

        // take one of the new servers if it is possible (there are still such
        // servers we didn't try),
        // and either the probability tells us to connect to one of the new
        // servers or if we already
        // tried all the old servers
        if (((currentIndexNew + 1) < newServers.size()) && (takeNew || (currentIndexOld + 1) >= oldServers.size())) {
            ++currentIndexNew;
            return newServers.get(currentIndexNew);
        }

        // start taking old servers
        if ((currentIndexOld + 1) < oldServers.size()) {
            ++currentIndexOld;
            return oldServers.get(currentIndexOld);
        }

        return null;
    }
}
