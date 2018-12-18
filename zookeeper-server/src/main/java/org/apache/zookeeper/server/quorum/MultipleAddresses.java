package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.quorum.exception.RuntimeNoReachableHostException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * This class allows to store several quorum and electing addresses.
 *
 * See ZOOKEEPER-3188 for a discussion of this feature.
 */
public class MultipleAddresses {

    private Set<InetSocketAddress> addresses;
    private int timeout;

    public MultipleAddresses() {
        addresses = Collections.newSetFromMap(new ConcurrentHashMap<>());
        timeout = 100;
    }

    public MultipleAddresses(List<InetSocketAddress> addresses) {
        this(addresses, 100);
    }

    public MultipleAddresses(InetSocketAddress address) {
        this(address, 100);
    }

    public MultipleAddresses(List<InetSocketAddress> addresses, int timeout) {
        this.addresses = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.addresses.addAll(addresses);
        this.timeout = timeout;
    }

    public MultipleAddresses(InetSocketAddress address,  int timeout) {
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
     * @return list of all InetSocketAddress
     */
    public List<InetSocketAddress> getAllAddresses() {
        return new LinkedList<>(addresses);
    }

    /**
     * Returns wildcard addresses for all ports
     *
     * @return list of InetSocketAddress with wildcards for all ports
     */
    public List<InetSocketAddress> getWildcardAddresses() {
       return addresses.stream().map(a -> new InetSocketAddress(a.getPort())).distinct().collect(Collectors.toList());
    }

    /**
     * Returns all ports
     *
     * @return list of all ports
     */
    public List<Integer> getAllPorts() {
        return addresses.stream().map(InetSocketAddress::getPort).distinct().collect(Collectors.toList());
    }

    public void addAddress(InetSocketAddress address) {
        addresses.add(address);
    }

    /**
     * Returns reachable address. If none is reachable than throws exception.
     *
     * @return address which is reachable.
     */
    public InetSocketAddress getValidAddress() {

        for(int i = 0; i < 3; i++) {
            for (InetSocketAddress addr : addresses) {
                try {
                    if (addr.getAddress().isReachable(timeout))
                        return addr;
                } catch (NullPointerException | IOException ignored) {
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }

        throw new RuntimeNoReachableHostException("No valid address among " + addresses);
    }

    /**
     * Performs a DNS lookup for addresses.
     *
     * If the DNS lookup fails, than address remain unmodified.
     */
    public void recreateSocketAddresses() {
        Set<InetSocketAddress> temp = Collections.newSetFromMap(new ConcurrentHashMap<>());

        for(InetSocketAddress addr : addresses) {
            try {
                temp.add(new InetSocketAddress(InetAddress.getByName(addr.getHostString()), addr.getPort()));
            } catch (UnknownHostException e) {
                temp.add(addr);
            }
        }

        addresses = temp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
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
