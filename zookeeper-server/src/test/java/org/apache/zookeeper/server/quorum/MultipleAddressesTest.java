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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.zookeeper.PortAssignment;
import org.junit.jupiter.api.Test;

public class MultipleAddressesTest {

    public static final int PORTS_AMOUNT = 10;

    @Test
    public void testIsEmpty() {
        MultipleAddresses multipleAddresses = new MultipleAddresses();
        assertTrue(multipleAddresses.isEmpty());

        multipleAddresses.addAddress(new InetSocketAddress(22));
        assertFalse(multipleAddresses.isEmpty());
    }

    @Test
    public void testGetAllAddresses() {
        List<InetSocketAddress> addresses = getAddressList();
        MultipleAddresses multipleAddresses = new MultipleAddresses(addresses);
        assertTrue(CollectionUtils.isEqualCollection(addresses, multipleAddresses.getAllAddresses()));

        multipleAddresses.addAddress(addresses.get(1));
        assertTrue(CollectionUtils.isEqualCollection(addresses, multipleAddresses.getAllAddresses()));
    }

    @Test
    public void testGetAllHostStrings() {
        List<InetSocketAddress> addresses = getAddressList();
        List<String> hostStrings = getHostStrings(addresses);
        MultipleAddresses multipleAddresses = new MultipleAddresses(addresses);

        assertTrue(CollectionUtils.isEqualCollection(hostStrings, multipleAddresses.getAllHostStrings()));

        multipleAddresses.addAddress(addresses.get(addresses.size() - 1));
        assertTrue(CollectionUtils.isEqualCollection(hostStrings, multipleAddresses.getAllHostStrings()));
    }

    @Test
    public void testGetAllPorts() {
        List<Integer> ports = getPortList();
        MultipleAddresses multipleAddresses = new MultipleAddresses(getAddressList(ports));

        assertTrue(CollectionUtils.isEqualCollection(ports, multipleAddresses.getAllPorts()));

        multipleAddresses.addAddress(new InetSocketAddress("localhost", ports.get(ports.size() - 1)));
        assertTrue(CollectionUtils.isEqualCollection(ports, multipleAddresses.getAllPorts()));
    }

    @Test
    public void testGetWildcardAddresses() {
        List<Integer> ports = getPortList();
        List<InetSocketAddress> addresses = getAddressList(ports);
        MultipleAddresses multipleAddresses = new MultipleAddresses(addresses);
        List<InetSocketAddress> allAddresses = ports.stream().map(InetSocketAddress::new).collect(Collectors.toList());

        assertTrue(CollectionUtils.isEqualCollection(allAddresses, multipleAddresses.getWildcardAddresses()));

        multipleAddresses.addAddress(new InetSocketAddress("localhost", ports.get(ports.size() - 1)));
        assertTrue(CollectionUtils.isEqualCollection(allAddresses, multipleAddresses.getWildcardAddresses()));
    }

    @Test
    public void testGetValidAddress() throws NoRouteToHostException {
        List<InetSocketAddress> addresses = getAddressList();
        MultipleAddresses multipleAddresses = new MultipleAddresses(addresses);

        assertTrue(addresses.contains(multipleAddresses.getReachableAddress()));
    }

    @Test
    public void testGetValidAddressWithNotValid() {
        assertThrows(NoRouteToHostException.class, () -> {
            // IP chosen because it is reserved for documentation/examples and should be unreachable (RFC 5737)
            MultipleAddresses multipleAddresses = new MultipleAddresses(new InetSocketAddress("203.0.113.1", 22));
            multipleAddresses.getReachableAddress();
        });
    }

    @Test
    public void testGetReachableOrOneWithSingleReachableAddress() {
        InetSocketAddress reachableAddress = new InetSocketAddress("127.0.0.1", PortAssignment.unique());

        MultipleAddresses multipleAddresses = new MultipleAddresses(Collections.singletonList(reachableAddress));
        InetSocketAddress actualReturnedAddress = multipleAddresses.getReachableOrOne();

        assertEquals(reachableAddress, actualReturnedAddress);
    }

    @Test
    public void testGetReachableOrOneWithSingleUnreachableAddress() {
        InetSocketAddress unreachableAddress = new InetSocketAddress("unreachable.address.zookeeper.apache.com", 1234);

        MultipleAddresses multipleAddresses = new MultipleAddresses(Collections.singletonList(unreachableAddress));
        InetSocketAddress actualReturnedAddress = multipleAddresses.getReachableOrOne();

        assertEquals(unreachableAddress, actualReturnedAddress);
    }

    @Test
    public void testRecreateSocketAddresses() throws UnknownHostException {
        List<InetSocketAddress> searchedAddresses = Arrays.stream(InetAddress.getAllByName("google.com"))
                .map(addr -> new InetSocketAddress(addr, 222)).collect(Collectors.toList());

        MultipleAddresses multipleAddresses = new MultipleAddresses(searchedAddresses.get(searchedAddresses.size() - 1));
        List<InetSocketAddress> addresses = new ArrayList<>(multipleAddresses.getAllAddresses());

        assertEquals(1, addresses.size());
        assertEquals(searchedAddresses.get(searchedAddresses.size() - 1), addresses.get(0));

        multipleAddresses.recreateSocketAddresses();

        addresses = new ArrayList<>(multipleAddresses.getAllAddresses());
        assertEquals(1, addresses.size());
        assertEquals(searchedAddresses.get(0), addresses.get(0));
    }

    @Test
    public void testRecreateSocketAddressesWithWrongAddresses() {
        InetSocketAddress address = new InetSocketAddress("locahost", 222);
        MultipleAddresses multipleAddresses = new MultipleAddresses(address);
        multipleAddresses.recreateSocketAddresses();

        assertEquals(address, multipleAddresses.getOne());
    }

    @Test
    public void testAlwaysGetReachableAddress() throws Exception{
        InetSocketAddress reachableHost = new InetSocketAddress("127.0.0.1", 1234);
        InetSocketAddress unreachableHost1 = new InetSocketAddress("unreachable1.address.zookeeper.apache.com", 1234);
        InetSocketAddress unreachableHost2 = new InetSocketAddress("unreachable2.address.zookeeper.apache.com", 1234);
        InetSocketAddress unreachableHost3 = new InetSocketAddress("unreachable3.address.zookeeper.apache.com", 1234);

        MultipleAddresses multipleAddresses = new MultipleAddresses(
          Arrays.asList(unreachableHost1, unreachableHost2, unreachableHost3, reachableHost));

        // we call the getReachableAddress() function multiple times, to make sure we
        // always got back a reachable address and not just a random one
        for (int i = 0; i < 10; i++) {
            assertEquals(reachableHost, multipleAddresses.getReachableAddress());
        }
    }

    @Test
    public void testGetAllReachableAddresses() throws Exception {
        InetSocketAddress reachableHost1 = new InetSocketAddress("127.0.0.1", 1234);
        InetSocketAddress reachableHost2 = new InetSocketAddress("127.0.0.1", 2345);
        InetSocketAddress unreachableHost1 = new InetSocketAddress("unreachable1.address.zookeeper.apache.com", 1234);
        InetSocketAddress unreachableHost2 = new InetSocketAddress("unreachable2.address.zookeeper.apache.com", 1234);

        MultipleAddresses multipleAddresses = new MultipleAddresses(
          Arrays.asList(unreachableHost1, unreachableHost2, reachableHost1, reachableHost2));

        Set<InetSocketAddress> reachableHosts = new HashSet<>(Arrays.asList(reachableHost1, reachableHost2));
        assertEquals(reachableHosts, multipleAddresses.getAllReachableAddresses());
    }

    @Test
    public void testGetAllReachableAddressesOrAllWhenSomeReachable() throws Exception {
        InetSocketAddress reachableHost1 = new InetSocketAddress("127.0.0.1", 1234);
        InetSocketAddress reachableHost2 = new InetSocketAddress("127.0.0.1", 2345);
        InetSocketAddress unreachableHost1 = new InetSocketAddress("unreachable1.address.zookeeper.apache.com", 1234);
        InetSocketAddress unreachableHost2 = new InetSocketAddress("unreachable2.address.zookeeper.apache.com", 1234);

        MultipleAddresses multipleAddresses = new MultipleAddresses(
          Arrays.asList(unreachableHost1, unreachableHost2, reachableHost1, reachableHost2));

        Set<InetSocketAddress> reachableHosts = new HashSet<>(Arrays.asList(reachableHost1, reachableHost2));
        assertEquals(reachableHosts, multipleAddresses.getAllReachableAddressesOrAll());
    }

    @Test
    public void testGetAllReachableAddressesOrAllWhenNoneReachable() throws Exception {
        InetSocketAddress unreachableHost1 = new InetSocketAddress("unreachable1.address.zookeeper.apache.com", 1234);
        InetSocketAddress unreachableHost2 = new InetSocketAddress("unreachable2.address.zookeeper.apache.com", 1234);
        InetSocketAddress unreachableHost3 = new InetSocketAddress("unreachable3.address.zookeeper.apache.com", 1234);
        List<InetSocketAddress> allUnreachableAddresses = Arrays.asList(unreachableHost1, unreachableHost2, unreachableHost3);

        MultipleAddresses multipleAddresses = new MultipleAddresses(allUnreachableAddresses);

        assertEquals(new HashSet<>(allUnreachableAddresses), multipleAddresses.getAllReachableAddressesOrAll());
    }

    @Test
    public void testEquals() {
        List<InetSocketAddress> addresses = getAddressList();

        MultipleAddresses multipleAddresses = new MultipleAddresses(addresses);
        MultipleAddresses multipleAddressesEquals = new MultipleAddresses(addresses);

        assertEquals(multipleAddresses, multipleAddressesEquals);

        MultipleAddresses multipleAddressesNotEquals = new MultipleAddresses(getAddressList());

        assertNotEquals(multipleAddresses, multipleAddressesNotEquals);
    }

    @Test
    public void testSize() {
        List<InetSocketAddress> addresses = getAddressList();
        MultipleAddresses multipleAddresses = new MultipleAddresses(addresses);

        assertEquals(PORTS_AMOUNT, multipleAddresses.size());
    }

    public List<Integer> getPortList() {
        return IntStream.range(0, PORTS_AMOUNT).mapToObj(i -> PortAssignment.unique()).collect(Collectors.toList());
    }

    public List<InetSocketAddress> getAddressList() {
        return getAddressList(getPortList());
    }

    public List<InetSocketAddress> getAddressList(List<Integer> ports) {
        return IntStream.range(0, ports.size())
                .mapToObj(i -> new InetSocketAddress("127.0.0." + i, ports.get(i))).collect(Collectors.toList());
    }

    private List<String> getHostStrings(List<InetSocketAddress> addresses) {
        return IntStream.range(0, addresses.size())
                .mapToObj(i -> "127.0.0." + i).collect(Collectors.toList());
    }

}
