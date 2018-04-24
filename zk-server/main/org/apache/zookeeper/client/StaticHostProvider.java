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

import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Most simple HostProvider, resolves only on instantiation.
 * 
 */
@InterfaceAudience.Public
public final class StaticHostProvider implements HostProvider {
    private static final Logger LOG = LoggerFactory
            .getLogger(StaticHostProvider.class);

    private List<InetSocketAddress> serverAddresses = new ArrayList<InetSocketAddress>(
            5);

    private Random sourceOfRandomness;
    private int lastIndex = -1;

    private int currentIndex = -1;

    /**
     * The following fields are used to migrate clients during reconfiguration
     */
    private boolean reconfigMode = false;

    private final List<InetSocketAddress> oldServers = new ArrayList<InetSocketAddress>(
            5);

    private final List<InetSocketAddress> newServers = new ArrayList<InetSocketAddress>(
            5);

    private int currentIndexOld = -1;
    private int currentIndexNew = -1;

    private float pOld, pNew;

    /**
     * Constructs a SimpleHostSet.
     * 
     * @param serverAddresses
     *            possibly unresolved ZooKeeper server addresses
     * @throws IllegalArgumentException
     *             if serverAddresses is empty or resolves to an empty list
     */
    public StaticHostProvider(Collection<InetSocketAddress> serverAddresses) {
       sourceOfRandomness = new Random(System.currentTimeMillis() ^ this.hashCode());

        this.serverAddresses = resolveAndShuffle(serverAddresses);
        if (this.serverAddresses.isEmpty()) {
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
     * @param serverAddresses
     *            possibly unresolved ZooKeeper server addresses
     * @param randomnessSeed a seed used to initialize sourceOfRandomnes
     * @throws IllegalArgumentException
     *             if serverAddresses is empty or resolves to an empty list
     */
    public StaticHostProvider(Collection<InetSocketAddress> serverAddresses,
        long randomnessSeed) {
        sourceOfRandomness = new Random(randomnessSeed);

        this.serverAddresses = resolveAndShuffle(serverAddresses);
        if (this.serverAddresses.isEmpty()) {
            throw new IllegalArgumentException(
                    "A HostProvider may not be empty!");
        }       
        currentIndex = -1;
        lastIndex = -1;              
    }

    private List<InetSocketAddress> resolveAndShuffle(Collection<InetSocketAddress> serverAddresses) {
        List<InetSocketAddress> tmpList = new ArrayList<InetSocketAddress>(serverAddresses.size());       
        for (InetSocketAddress address : serverAddresses) {
            try {
                InetAddress ia = address.getAddress();
                String addr = (ia != null) ? ia.getHostAddress() : address.getHostString();
                InetAddress resolvedAddresses[] = InetAddress.getAllByName(addr);
                for (InetAddress resolvedAddress : resolvedAddresses) {
                    InetAddress taddr = InetAddress.getByAddress(address.getHostString(), resolvedAddress.getAddress());
                    tmpList.add(new InetSocketAddress(taddr, address.getPort()));
                }
            } catch (UnknownHostException ex) {
                LOG.warn("No IP address found for server: {}", address, ex);
            }
        }
        Collections.shuffle(tmpList, sourceOfRandomness);
        return tmpList;
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
     * See <a href="https://issues.apache.org/jira/browse/ZOOKEEPER-1355">ZOOKEEPER-1355</a>
     * for the protocol and its evaluation, and StaticHostProviderTest for the tests that illustrate how load balancing
     * works with this policy.
     *
     * @param serverAddresses new host list
     * @param currentHost the host to which this client is currently connected
     * @return true if changing connections is necessary for load-balancing, false otherwise  
     */


    @Override
    public synchronized boolean updateServerList(
            Collection<InetSocketAddress> serverAddresses,
            InetSocketAddress currentHost) {
        // Resolve server addresses and shuffle them
        List<InetSocketAddress> resolvedList = resolveAndShuffle(serverAddresses);
        if (resolvedList.isEmpty()) {
            throw new IllegalArgumentException(
                    "A HostProvider may not be empty!");
        }
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

        for (InetSocketAddress addr : resolvedList) {
            if (addr.getPort() == myServer.getPort()
                    && ((addr.getAddress() != null
                            && myServer.getAddress() != null && addr
                            .getAddress().equals(myServer.getAddress())) || addr
                            .getHostString().equals(myServer.getHostString()))) {
                myServerInNewConfig = true;
                break;
            }
        }

        reconfigMode = true;

        newServers.clear();
        oldServers.clear();
        // Divide the new servers into oldServers that were in the previous list
        // and newServers that were not in the previous list
        for (InetSocketAddress resolvedAddress : resolvedList) {
            if (this.serverAddresses.contains(resolvedAddress)) {
                oldServers.add(resolvedAddress);
            } else {
                newServers.add(resolvedAddress);
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
                if (sourceOfRandomness.nextFloat() <= (1 - ((float) this.serverAddresses
                        .size()) / (numOld + numNew))) {
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
            currentIndex = resolvedList.indexOf(getServerAtCurrentIndex());
        } else {
            currentIndex = -1;
        }
        this.serverAddresses = resolvedList;
        currentIndexOld = -1;
        currentIndexNew = -1;
        lastIndex = currentIndex;
        return reconfigMode;
    }

    public synchronized InetSocketAddress getServerAtIndex(int i) {
    	if (i < 0 || i >= serverAddresses.size()) return null;
    	return serverAddresses.get(i);
    }
    
    public synchronized InetSocketAddress getServerAtCurrentIndex() {
    	return getServerAtIndex(currentIndex);
    }

    public synchronized int size() {
        return serverAddresses.size();
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

    public InetSocketAddress next(long spinDelay) {
        boolean needToSleep = false;
        InetSocketAddress addr;

        synchronized(this) {
            if (reconfigMode) {
                addr = nextHostInReconfigMode();
                if (addr != null) {
                	currentIndex = serverAddresses.indexOf(addr);
                	return addr;                
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

        return addr;
    }

    public synchronized void onConnected() {
        lastIndex = currentIndex;
        reconfigMode = false;
    }

}
