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

import static java.util.Arrays.asList;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * This class allows to store several quorum and electing addresses.
 *
 * See ZOOKEEPER-3188 for a discussion of this feature.
 */
public final class MultipleAddresses {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(1000);

    private static Set<InetSocketAddress> newConcurrentHashSet() {
        return Collections.newSetFromMap(new ConcurrentHashMap<>());
    }

    private Set<InetSocketAddress> addresses;
    private final Duration timeout;

    public MultipleAddresses() {
        this(Collections.emptyList());
    }

    public MultipleAddresses(Collection<InetSocketAddress> addresses) {
        this(addresses, DEFAULT_TIMEOUT);
    }

    public MultipleAddresses(InetSocketAddress address) {
        this(asList(address), DEFAULT_TIMEOUT);
    }

    public MultipleAddresses(Collection<InetSocketAddress> addresses, Duration timeout) {
        this.addresses = newConcurrentHashSet();
        this.addresses.addAll(addresses);
        this.timeout = timeout;
    }

    public boolean isEmpty() {
        return addresses.isEmpty();
    }

    /**
     * Returns all addresses in an unmodifiable set.
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
     * Returns a reachable address. If none is reachable than throws exception.
     * The function is nondeterministic in the sense that the result of calling this function
     * twice with the same set of reachable addresses might lead to different results.
     *
     * @return address which is reachable.
     * @throws NoRouteToHostException if none of the addresses are reachable
     */
    public InetSocketAddress getReachableAddress() throws NoRouteToHostException {
        // using parallelStream() + findAny() will help to minimize the time spent on network operations
        return addresses.parallelStream()
          .filter(this::checkIfAddressIsReachable)
          .findAny()
          .orElseThrow(() -> new NoRouteToHostException("No valid address among " + addresses));
    }

    /**
     * Returns a set of all reachable addresses. If none is reachable than returns empty set.
     *
     * @return all addresses which are reachable.
     */
    public Set<InetSocketAddress> getAllReachableAddresses() {
        // using parallelStream() will help to minimize the time spent on network operations
        return addresses.parallelStream()
          .filter(this::checkIfAddressIsReachable)
          .collect(Collectors.toSet());
    }

    /**
     * Returns a set of all reachable addresses. If none is reachable than returns all addresses.
     *
     * @return all reachable addresses, or all addresses if none is reachable.
     */
    public Set<InetSocketAddress> getAllReachableAddressesOrAll() {
        // if there is only a single address provided then we don't need to do any reachability check
        if (addresses.size() == 1) {
            return getAllAddresses();
        }

        Set<InetSocketAddress> allReachable = getAllReachableAddresses();
        if (allReachable.isEmpty()) {
            return getAllAddresses();
        }
        return allReachable;
    }

    /**
     * Returns a reachable address or an arbitrary one, if none is reachable. It throws an exception
     * if there are no addresses registered. The function is nondeterministic in the sense that the
     * result of calling this function twice with the same set of reachable addresses might lead
     * to different results.
     *
     * @return address which is reachable or fist one.
     * @throws NoSuchElementException if there is no address registered
     */
    public InetSocketAddress getReachableOrOne() {
        InetSocketAddress address;

        // if there is only a single address provided then we don't do any reachability check
        if (addresses.size() == 1) {
            return getOne();
        }

        try {
            address = getReachableAddress();
        } catch (NoRouteToHostException e) {
            address = getOne();
        }
        return address;
    }

    /**
     * Performs a parallel DNS lookup for all addresses.
     *
     * If the DNS lookup fails, then address remain unmodified.
     */
    public void recreateSocketAddresses() {
        addresses = addresses.parallelStream()
          .map(this::recreateSocketAddress)
          .collect(Collectors.toCollection(MultipleAddresses::newConcurrentHashSet));
    }

    /**
     * Returns an address from the set.
     *
     * @return address from a set.
     * @throws NoSuchElementException if there is no address registered
     */
    public InetSocketAddress getOne() {
        return addresses.iterator().next();
    }


    /**
     * Returns the number of addresses in the set.
     *
     * @return the number of addresses.
     */
    public int size() {
        return addresses.size();
    }

    private boolean checkIfAddressIsReachable(InetSocketAddress address) {
        if (address.isUnresolved()) {
            return false;
        }
        try {
            if (address.getAddress().isReachable((int) timeout.toMillis())) {
                return true;
            }
        } catch (IOException ignored) {
            // ignore, we don't really care if we can't reach it for timeout or for IO problems
        }
        return false;
    }

    private InetSocketAddress recreateSocketAddress(InetSocketAddress address) {
        try {
            return new InetSocketAddress(InetAddress.getByName(address.getHostString()), address.getPort());
        } catch (UnknownHostException e) {
            return address;
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
        return addresses.stream().map(InetSocketAddress::toString).collect(Collectors.joining("|"));
    }
}