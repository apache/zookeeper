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

import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Most simple HostProvider, resolves only on instantiation.
 *
 */
@InterfaceAudience.Public
public final class StaticHostProvider implements HostProvider {
    public interface Resolver {
        InetAddress[] getAllByName(String name) throws UnknownHostException;
    }

    private static final Logger LOG = LoggerFactory
            .getLogger(StaticHostProvider.class);

    private final List<InetSocketAddress> serverAddresses = new ArrayList<InetSocketAddress>(
            5);

    private int lastIndex = -1;

    private int currentIndex = -1;

    // Don't re-resolve on first next() call
    private boolean connectedSinceNext = true;

    private Resolver resolver;

    /**
     * Constructs a SimpleHostSet.
     *
     * @param serverAddresses
     *            possibly unresolved ZooKeeper server addresses
     * @throws IllegalArgumentException
     *             if serverAddresses is empty or resolves to an empty list
     */
    public StaticHostProvider(Collection<InetSocketAddress> serverAddresses) {
        this.resolver = new Resolver() {
            @Override
            public InetAddress[] getAllByName(String name) throws UnknownHostException {
                return InetAddress.getAllByName(name);
            }
        };
        init(serverAddresses);
    }

    /**
     * Introduced for testing purposes. getAllByName() is a static method of InetAddress, therefore cannot be easily mocked.
     * By abstraction of Resolver interface we can easily inject a mocked implementation in tests.
     *
     * @param serverAddresses
     *            possibly unresolved ZooKeeper server addresses
     * @param resolver
     *            custom resolver implementation
     * @throws IllegalArgumentException
     *             if serverAddresses is empty or resolves to an empty list
     */
    public StaticHostProvider(Collection<InetSocketAddress> serverAddresses, Resolver resolver) {
        this.resolver = resolver;
        init(serverAddresses);
    }

    /**
     * Common init method for all constructors.
     * Resolve all unresolved server addresses, put them in a list and shuffle.
     */
    private void init(Collection<InetSocketAddress> serverAddresses) {
        for (InetSocketAddress address : serverAddresses) {
            try {
                InetAddress resolvedAddresses[] = this.resolver.getAllByName(getHostString(address));
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

    public List<InetSocketAddress> getServerAddresses() {
        return Collections.unmodifiableList(serverAddresses);
    }

    public InetSocketAddress next(long spinDelay) {
        // Handle possible connection error by re-resolving hostname if possible
        if (!connectedSinceNext) {
            InetSocketAddress curAddr = serverAddresses.get(currentIndex);
            String curHostString = getHostString(curAddr);
            if (!curHostString.equals(curAddr.getAddress().getHostAddress())) {
                LOG.info("Resolving again hostname: {}", curHostString);
                try {
                    int thePort = curAddr.getPort();
                    InetAddress resolvedAddresses[] = this.resolver.getAllByName(curHostString);
                    int i = 0;
                    while (i < serverAddresses.size()) {
                        if (getHostString(serverAddresses.get(i)).equals(curHostString) &&
                                serverAddresses.get(i).getPort() == curAddr.getPort()) {
                            LOG.debug("Removing address: {}", serverAddresses.get(i));
                            serverAddresses.remove(i);
                        } else {
                            i++;
                        }
                    }

                    for (InetAddress resolvedAddress : resolvedAddresses) {
                        InetSocketAddress newAddr = new InetSocketAddress(resolvedAddress, thePort);
                        if (!serverAddresses.contains(newAddr)) {
                            LOG.debug("Adding address: {}", newAddr);
                            serverAddresses.add(newAddr);
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
