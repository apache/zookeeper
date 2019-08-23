/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class allows to store several quorum and electing addresses.
 *
 * See ZOOKEEPER-3188 for a discussion of this feature.
 */
public class MultipleAddresses {
    private static final int DEFAULT_TIMEOUT = 100;

    private Set<InetSocketAddress> addresses;
    private int timeout;

    public MultipleAddresses() {
        addresses = Collections.newSetFromMap(new ConcurrentHashMap<>());
        timeout = DEFAULT_TIMEOUT;
    }

    public MultipleAddresses(List<InetSocketAddress> addresses) {
        this(addresses, DEFAULT_TIMEOUT);
    }

    public MultipleAddresses(InetSocketAddress address) {
        this(address, DEFAULT_TIMEOUT);
    }

    public MultipleAddresses(List<InetSocketAddress> addresses, int timeout) {
        this.addresses = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.addresses.addAll(addresses);
        this.timeout = timeout;
    }

    public MultipleAddresses(InetSocketAddress address, int timeout) {
        addresses = Collections.newSetFromMap(new ConcurrentHashMap<>());
        addresses.add(address);
        this.timeout = timeout;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public boolean isEmpty() {
        return addresses.isEmpty();
    }

    /**
     * Returns all addresses.
     *
     * @return set of all InetSocketAddress
     */
    public Set<InetSocketAddress> getAllAddresses() {
        return Collections.unmodifiableSet(addresses);
    }

    /**
     * Returns wildcard addresses for all ports
     *
     * @return set of InetSocketAddress with wildcards for all ports
     */
    public Set<InetSocketAddress> getWildcardAddresses() {
        return addresses.stream().map(a -> new InetSocketAddress(a.getPort())).collect(Collectors.toSet());
    }

    /**
     * Returns all ports
     *
     * @return list of all ports
     */
    public List<Integer> getAllPorts() {
        return addresses.stream().map(InetSocketAddress::getPort).distinct().collect(Collectors.toList());
    }

    /**
     * Returns distinct list of all host strings
     *
     * @return list of all hosts
     */
    public List<String> getAllHostStrings() {
        return addresses.stream().map(InetSocketAddress::getHostString).distinct().collect(Collectors.toList());
    }

    public void addAddress(InetSocketAddress address) {
        addresses.add(address);
    }

    /**
     * Returns reachable address. If none is reachable than throws exception.
     *
     * @return address which is reachable.
     * @throws NoRouteToHostException if none address is reachable
     */
    public InetSocketAddress getReachableAddress() throws NoRouteToHostException {
        AtomicReference<InetSocketAddress> address = new AtomicReference<>(null);
        getInetSocketAddressStream().forEach(addr -> checkIfAddressIsReachableAndSet(addr, address));

        if (address.get() != null) {
            return address.get();
        } else {
            throw new NoRouteToHostException("No valid address among " + addresses);
        }
    }

    /**
     * Returns reachable address or first one, if none is reachable.
     *
     * @return address which is reachable or fist one.
     */
    public InetSocketAddress getReachableOrOne() {
        InetSocketAddress address;
        try {
            address = getReachableAddress();
        } catch (NoRouteToHostException e) {
            address = getOne();
        }
        return address;
    }

    /**
     * Performs a DNS lookup for addresses.
     *
     * If the DNS lookup fails, than address remain unmodified.
     */
    public void recreateSocketAddresses() {
        Set<InetSocketAddress> temp = Collections.newSetFromMap(new ConcurrentHashMap<>());
        temp.addAll(getInetSocketAddressStream().map(this::recreateSocketAddress).collect(Collectors.toSet()));
        addresses = temp;
    }

    /**
     * Returns first address from set.
     *
     * @return address from a set.
     */
    public InetSocketAddress getOne() {
        return addresses.iterator().next();
    }

    private void checkIfAddressIsReachableAndSet(InetSocketAddress address,
                                                 AtomicReference<InetSocketAddress> reachableAddress) {
        for (int i = 0; i < 5 && reachableAddress.get() == null; i++) {
            try {
                if (address.getAddress().isReachable((i + 1) * timeout)) {
                    reachableAddress.compareAndSet(null, address);
                    break;
                }
                Thread.sleep(timeout);
            } catch (NullPointerException | IOException | InterruptedException ignored) {
            }
        }
    }

    private InetSocketAddress recreateSocketAddress(InetSocketAddress address) {
        try {
            return new InetSocketAddress(InetAddress.getByName(address.getHostString()), address.getPort());
        } catch (UnknownHostException e) {
            return address;
        }
    }

    private Stream<InetSocketAddress> getInetSocketAddressStream() {
        if (addresses.size() > 1) {
            return addresses.parallelStream();
        } else {
            return addresses.stream();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (o == null || getClass() != o.getClass()) {
            return false;
        }

        MultipleAddresses that = (MultipleAddresses) o;
        return Objects.equals(addresses, that.addresses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(addresses);
    }

    @Override
    public String toString() {
        return addresses.stream().map(InetSocketAddress::toString).collect(Collectors.joining(","));
    }
}