package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.quorum.exception.RuntimeNoReachableHostException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.stream.Collectors;

public class MultipleAddresses {

    private Set<InetSocketAddress> addresses;

    public MultipleAddresses() {
        addresses = new HashSet<>();
    }

    public MultipleAddresses(List<InetSocketAddress> addresses) {
        this.addresses = new HashSet<>();
        this.addresses.addAll(addresses);
    }

    public MultipleAddresses(InetSocketAddress address) {
        addresses = new HashSet<>();
        addresses.add(address);
    }

    public boolean isEmpty() {
        return addresses.isEmpty();
    }

    public boolean isReachable() {
        try {
            getValidAddress();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<InetSocketAddress> getAllAddresses() {
        return new LinkedList<>(addresses);
    }

    public List<InetSocketAddress> getAllAddressesForAllPorts() {
       return addresses.stream().map(a -> new InetSocketAddress(a.getPort())).distinct().collect(Collectors.toList());
    }

    public List<Integer> getAllPorts() {
        return addresses.stream().map(InetSocketAddress::getPort).collect(Collectors.toList());
    }

    public void addAddress(InetSocketAddress address) {
        addresses.add(address);
    }

    public InetSocketAddress getValidAddress() {

        for(InetSocketAddress addr : addresses) {
            try {
                if (addr.getAddress().isReachable(100))
                    return addr;
            } catch (NullPointerException | IOException e) {
            }
        }

        throw new RuntimeNoReachableHostException("No valid address among " + addresses);
    }

    public void recreateSocketAddresses() {
        Set<InetSocketAddress> temp = new HashSet<>();

        for(InetSocketAddress addr : addresses) {
            try {
                temp.addAll(resolveFqdnToAddress(addr));
            } catch (UnknownHostException e) {
                temp.add(addr);
            }
        }

        addresses = temp;
    }

    private List<InetSocketAddress> resolveFqdnToAddress(InetSocketAddress addr) throws UnknownHostException {
        String host = addr.getHostString();
        int port = addr.getPort();

        return Arrays.stream(InetAddress.getAllByName(host))
                .map(address -> new InetSocketAddress(address, port)).collect(Collectors.toList());
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
        StringBuilder result = new StringBuilder();

        addresses.forEach(addr -> result.append(String.format("%s.", addr)));
        result.deleteCharAt(result.length() - 1);

        return result.toString();
    }
}
