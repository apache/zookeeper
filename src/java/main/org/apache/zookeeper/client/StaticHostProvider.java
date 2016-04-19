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

package org.apache.zookeeper.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.zookeeper.ServerCfg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Most simple HostProvider, resolves only on instantiation.
 * 
 */
public final class StaticHostProvider implements HostProvider {
    private static final Logger LOG = LoggerFactory
            .getLogger(StaticHostProvider.class);

    private List<ServerCfg> serversCfg = new ArrayList<ServerCfg>(5);

    private Random sourceOfRandomness;
    private int lastIndex = -1;

    private int currentIndex = -1;

    /**
     * The following fields are used to migrate clients during reconfiguration
     */
    private boolean reconfigMode = false;

    private final List<ServerCfg> oldServers = new ArrayList<ServerCfg>(5);

    private final List<ServerCfg> newServers = new ArrayList<ServerCfg>(5);

    private int currentIndexOld = -1;
    private int currentIndexNew = -1;

    private float pOld, pNew;

    /**
     * Constructs a SimpleHostSet.
     * 
     * @param serversCfg
     *            possibly unresolved ZooKeeper server addresses with
     *            optional ssl cfg.
     * @throws IllegalArgumentException
     *             if serverAddresses is empty or resolves to an empty list
     */
    public StaticHostProvider(
            final Collection<ServerCfg> serversCfg) {
       sourceOfRandomness = new Random(System.currentTimeMillis() ^ this.hashCode());

        this.serversCfg = resolveAndShuffle(serversCfg);
        if (this.serversCfg.isEmpty()) {
            throw new IllegalArgumentException(
                    "A HostProvider may not be empty!");
        }       
        currentIndex = -1;
        lastIndex = -1;              
    }

    /**
     * Constructs a SimpleHostSet. This constructor is used from StaticHostProviderTest to produce deterministic test results
     * by initializing sourceOfRandomness with the same seed
     * 
     * @param serversCfg
     *            possibly unresolved ZooKeeper server addresses with
     *            optional ssl cfg.
     * @param randomnessSeed a seed used to initialize sourceOfRandomnes
     * @throws IllegalArgumentException
     *             if serverAddresses is empty or resolves to an empty list
     */
    public StaticHostProvider(final Collection<ServerCfg> serversCfg,
        long randomnessSeed) {
        sourceOfRandomness = new Random(randomnessSeed);

        this.serversCfg = resolveAndShuffle(serversCfg);
        if (this.serversCfg.isEmpty()) {
            throw new IllegalArgumentException(
                    "A HostProvider may not be empty!");
        }       
        currentIndex = -1;
        lastIndex = -1;              
    }

    private List<ServerCfg> resolveAndShuffle(
            final Collection<ServerCfg> serversCfg) {
        final List<ServerCfg> tmpList =
                new ArrayList<ServerCfg>(serversCfg.size());

        for (final ServerCfg cfg : serversCfg) {
            try {
                final InetAddress ia = cfg.getInetAddress().getAddress();
                String addr = (ia != null) ? ia.getHostAddress() :
                        cfg.getInetAddress().getHostString();
                final InetAddress resolvedAddresses[] =
                        InetAddress.getAllByName(addr);
                for (final InetAddress resolvedAddress : resolvedAddresses) {
                    final InetAddress taddr = InetAddress.getByAddress(
                            cfg.getInetAddress().getHostString(),
                            resolvedAddress.getAddress());
                    tmpList.add(new ServerCfg(cfg.getHostStr(),
                            new InetSocketAddress(taddr,
                                    cfg.getInetAddress().getPort()),
                            cfg.getSslCertCfg()));
                }
            } catch (UnknownHostException ex) {
                LOG.warn("No IP address found for server: {}",
                        cfg.getInetAddress(), ex);
            }
        }
        Collections.shuffle(tmpList, sourceOfRandomness);
        return Collections.unmodifiableList(tmpList);
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
     * If true is returned, the function sets pOld and pNew that correspond to the probability to migrate to ones of the
     * new servers in serverAddresses or one of the old servers (migrating to one of the old servers is done only
     * if our client's currentHost is not in serverAddresses). See nextHostInReconfigMode for the selection logic.
     * 
     * See {@link https://issues.apache.org/jira/browse/ZOOKEEPER-1355} for the protocol and its evaluation, and
	 * StaticHostProviderTest for the tests that illustrate how load balancing works with this policy.
     * @param serversCfg new host list with optional SSL config.
     * @param currentHost the host to which this client is currently connected
     * @return true if changing connections is necessary for load-balancing, false otherwise  
     */


    @Override
    public synchronized boolean updateServerList(
            final Collection<ServerCfg> serversCfg,
            final InetSocketAddress currentHost) {
        // Resolve server addresses and shuffle them
        final List<ServerCfg> resolvedList = resolveAndShuffle(serversCfg);
        if (resolvedList.isEmpty()) {
            throw new IllegalArgumentException(
                    "A HostProvider may not be empty!");
        }
        // Check if client's current server is in the new list of servers
        boolean myServerInNewConfig = false;

        InetSocketAddress myServer = currentHost;

        // choose "current" server according to the client rebalancing algorithm
        if (reconfigMode) {
            myServer = next(0).getInetAddress();
        }

        // if the client is not currently connected to any server
        if (myServer == null) {
            // reconfigMode = false (next shouldn't return null).
            if (lastIndex >= 0) {
                // take the last server to which we were connected
                myServer = this.serversCfg.get(lastIndex).getInetAddress();
            } else {
                // take the first server on the list
                myServer = this.serversCfg.get(0).getInetAddress();
            }
        }

        for (final ServerCfg serverCfg : resolvedList) {
            if (serverCfg.getInetAddress().getPort() == myServer.getPort()
                    && ((serverCfg.getInetAddress().getAddress() != null
                            && myServer.getAddress() != null
                    && serverCfg.getInetAddress().getAddress().equals(
                    myServer.getAddress())) ||
                    serverCfg.getInetAddress().getHostString().equals(
                            myServer.getHostString()))) {
                myServerInNewConfig = true;
                break;
            }
        }

        reconfigMode = true;

        newServers.clear();
        oldServers.clear();
        // Divide the new servers into oldServers that were in the previous list
        // and newServers that were not in the previous list
        for (final ServerCfg resolvedCfg : resolvedList) {
            if (this.serversCfg.contains(resolvedCfg)) {
                oldServers.add(resolvedCfg);
            } else {
                newServers.add(resolvedCfg);
            }
        }

        int numOld = oldServers.size();
        int numNew = newServers.size();

        // number of servers increased
        if (numOld + numNew > this.serversCfg.size()) {
            if (myServerInNewConfig) {
                // my server is in new config, but load should be decreased.
                // Need to decide if this client
                // is moving to one of the new servers
                if (sourceOfRandomness.nextFloat() <= (1 -
                        ((float) this.serversCfg.size()) / (numOld + numNew))) {
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
                pOld = ((float) (numOld * (this.serversCfg.size() -
                        (numOld + numNew))))
                        / ((numOld + numNew) *
                        (this.serversCfg.size() - numOld));
                pNew = 1 - pOld;
            }
        }

        if (!reconfigMode) {
            currentIndex = resolvedList.indexOf(getServerAtCurrentIndex());
        } else {
            currentIndex = -1;
        }
        this.serversCfg = resolvedList;
        currentIndexOld = -1;
        currentIndexNew = -1;
        lastIndex = currentIndex;
        return reconfigMode;
    }

    public synchronized ServerCfg getServerAtIndex(int i) {
    	if (i < 0 || i >= serversCfg.size()) return null;
    	return serversCfg.get(i);
    }
    
    public synchronized ServerCfg getServerAtCurrentIndex() {
    	return getServerAtIndex(currentIndex);
    }

    public synchronized int size() {
        return serversCfg.size();
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
    private ServerCfg nextHostInReconfigMode() {
        boolean takeNew = (sourceOfRandomness.nextFloat() <= pNew);

        // take one of the new servers if it is possible (there are still such
        // servers we didn't try),
        // and either the probability tells us to connect to one of the new
        // servers or if we already
        // tried all the old servers
        if (((currentIndexNew + 1) < newServers.size())
                && (takeNew || (currentIndexOld + 1) >= oldServers.size())) {
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

    @Override
    public ServerCfg next(long spinDelay) {
        boolean needToSleep = false;
        ServerCfg serverCfg;

        synchronized(this) {
            if (reconfigMode) {
                serverCfg = nextHostInReconfigMode();
                if (serverCfg != null) {
                	currentIndex = serversCfg.indexOf(serverCfg);
                	return serverCfg;
                }
                //tried all servers and couldn't connect
                reconfigMode = false;
                needToSleep = (spinDelay > 0);
            }        
            ++currentIndex;
            if (currentIndex == serversCfg.size()) {
                currentIndex = 0;
            }            
            serverCfg = serversCfg.get(currentIndex);
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

        return serverCfg;
    }

    public synchronized void onConnected() {
        lastIndex = currentIndex;
        reconfigMode = false;
    }

}
