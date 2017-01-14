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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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

    private final List<InetSocketAddress> serverAddresses = new ArrayList<InetSocketAddress>(
            5);

    private int lastIndex = -1;

    private int currentIndex = -1;

    // Don't re-resolve on first next() call
    private boolean connectedSinceNext = true;

    /**
     * Constructs a SimpleHostSet.
     * 
     * @param serverAddresses
     *            possibly unresolved ZooKeeper server addresses
     * @throws IllegalArgumentException
     *             if serverAddresses is empty or resolves to an empty list
     */
    public StaticHostProvider(Collection<InetSocketAddress> serverAddresses) {
        for (InetSocketAddress address : serverAddresses) {
			try {
            	InetAddress resolvedAddresses[] =  InetAddress.getAllByName(getHostString(address));
            	for (InetAddress resolvedAddress : resolvedAddresses) {
                	this.serverAddresses.add(new InetSocketAddress(resolvedAddress, address.getPort()));
            	}
			} catch (UnknownHostException e) {
                LOG.error("Unable to connect to server: {}", address, e);
            }
        }
        
        if (this.serverAddresses.isEmpty()) {
            throw new IllegalArgumentException(
                    "A HostProvider may not be empty!");
        }
        Collections.shuffle(this.serverAddresses);
    }

    /**
     * Evaluate to a hostname if one is available and otherwise it returns the
     * string representation of the IP address.
     *
     * In Java 7, we have a method getHostString, but earlier versions do not support it.
     * This method is to provide a replacement for InetSocketAddress.getHostString().
     *
     * @param addr
     * @return Hostname string of address parameter
     */
    private String getHostString(InetSocketAddress addr) {
        String hostString = "";

        if (addr == null) {
            return hostString;
        }
        if (!addr.isUnresolved()) {
            InetAddress ia = addr.getAddress();

            // If the string starts with '/', then it has no hostname
            // and we want to avoid the reverse lookup, so we return
            // the string representation of the address.
            if (ia.toString().startsWith("/")) {
                hostString = ia.getHostAddress();
            } else {
                hostString = addr.getHostName();
            }
        } else {
            // According to the Java 6 documentation, if the hostname is
            // unresolved, then the string before the colon is the hostname.
            String addrString = addr.toString();
            hostString = addrString.substring(0, addrString.lastIndexOf(':'));
        }

        return hostString;
    }

    public int size() {
        return serverAddresses.size();
    }

    // Counts the number of addresses added and removed during
    // the last call to next. Used mainly for test purposes.
    // See StasticHostProviderTest.
    private int nextAdded = 0;
    private int nextRemoved = 0;

    int getNextAdded() {
        return nextAdded;
    }

    int getNextRemoved() {
        return nextRemoved;
    }

    public InetSocketAddress next(long spinDelay) {
        // Handle possible connection error by re-resolving hostname if possible
        if (!connectedSinceNext) {
            InetSocketAddress curAddr = serverAddresses.get(currentIndex);
            String curHostString = getHostString(curAddr);
            if (!curHostString.equals(curAddr.getAddress().getHostAddress())) {
                LOG.info("Resolving again hostname: {}", getHostString(curAddr));
                try {
                    int thePort = curAddr.getPort();
                    InetAddress resolvedAddresses[] = InetAddress.getAllByName(curHostString);
                    nextAdded = 0;
                    nextRemoved = 0;
                    if (resolvedAddresses.length == 1) {
                        serverAddresses.set(currentIndex, new InetSocketAddress(resolvedAddresses[0], thePort));
                        nextAdded = nextRemoved = 1;
                        LOG.debug("Newly resolved address: {}", resolvedAddresses[0]);
                    } else {
                        int i = 0;
                        while (i < serverAddresses.size()) {
                            if (getHostString(serverAddresses.get(i)).equals(curHostString) &&
                                    serverAddresses.get(i).getPort() == curAddr.getPort()) {
                                LOG.debug("Removing address: {}", serverAddresses.get(i));
                                serverAddresses.remove(i);
                                nextRemoved++;
                            } else {
                                i++;
                            }
                        }

                        for (InetAddress resolvedAddress : resolvedAddresses) {
                            InetSocketAddress newAddr = new InetSocketAddress(resolvedAddress, thePort);
                            if (!serverAddresses.contains(newAddr)) {
                                LOG.debug("Adding address: {}", newAddr);
                                serverAddresses.add(newAddr);
                                nextAdded++;
                            }
                        }
                    }
                } catch (UnknownHostException e) {
                    LOG.warn("Cannot re-resolve server: {}", curAddr, e);
                }
            }
        }
        connectedSinceNext = false;
        currentIndex = ++currentIndex % serverAddresses.size();
        if (currentIndex == lastIndex && spinDelay > 0) {
            try {
                Thread.sleep(spinDelay);
            } catch (InterruptedException e) {
                LOG.warn("Unexpected exception", e);
            }
        } else if (lastIndex == -1) {
            // We don't want to sleep on the first ever connect attempt.
            lastIndex = 0;
        }

        return serverAddresses.get(currentIndex);
    }

    public void onConnected() {
        lastIndex = currentIndex;
        connectedSinceNext = true;
    }
}
