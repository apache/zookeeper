/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import org.apache.commons.collections.CollectionUtils;
import org.apache.zookeeper.PortAssignment;
import org.junit.Assert;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MultipleAddressesTest {

    public final static int PORTS_AMOUNT = 10;

    @Test
    public void testIsEmpty() {
        MultipleAddresses multipleAddresses = new MultipleAddresses();
        Assert.assertTrue(multipleAddresses.isEmpty());

        multipleAddresses.addAddress(new InetSocketAddress(22));
        Assert.assertFalse(multipleAddresses.isEmpty());
    }

    @Test
    public void testGetAllAddresses() {
        List<InetSocketAddress> addresses = getAddressList();
        MultipleAddresses multipleAddresses = new MultipleAddresses(addresses);

        Assert.assertTrue(CollectionUtils.isEqualCollection(addresses, multipleAddresses.getAllAddresses()));

        multipleAddresses.addAddress(addresses.get(1));
        Assert.assertTrue(CollectionUtils.isEqualCollection(addresses, multipleAddresses.getAllAddresses()));
    }

    @Test
    public void testGetAllPorts() {
        List<Integer> ports = getPortList();
        MultipleAddresses multipleAddresses = new MultipleAddresses(getAddressList(ports));

        Assert.assertTrue(CollectionUtils.isEqualCollection(ports, multipleAddresses.getAllPorts()));

        multipleAddresses.addAddress(new InetSocketAddress("localhost", ports.get(ports.size() - 1)));
        Assert.assertTrue(CollectionUtils.isEqualCollection(ports, multipleAddresses.getAllPorts()));
    }

    @Test
    public void testGetWildcardAddresses() {
        List<Integer> ports = getPortList();
        List<InetSocketAddress> addresses = getAddressList(ports);
        MultipleAddresses multipleAddresses = new MultipleAddresses(addresses);
        List<InetSocketAddress> allAddresses = ports.stream().map(InetSocketAddress::new).collect(Collectors.toList());

        Assert.assertTrue(CollectionUtils.isEqualCollection(allAddresses, multipleAddresses.getWildcardAddresses()));

        multipleAddresses.addAddress(new InetSocketAddress("localhost", ports.get(ports.size() - 1)));
        Assert.assertTrue(CollectionUtils.isEqualCollection(allAddresses, multipleAddresses.getWildcardAddresses()));
    }

    @Test
    public void testGetValidAddress() throws NoRouteToHostException {
        List<InetSocketAddress> addresses = getAddressList();
        MultipleAddresses multipleAddresses = new MultipleAddresses(addresses);

        Assert.assertTrue(addresses.contains(multipleAddresses.getReachableAddress()));
    }

    @Test(expected = NoRouteToHostException.class)
    public void testGetValidAddressWithNotValid() throws NoRouteToHostException {
        MultipleAddresses multipleAddresses = new MultipleAddresses(new InetSocketAddress("10.0.0.1", 22));
        multipleAddresses.getReachableAddress();
    }

    @Test
    public void testRecreateSocketAddresses() throws UnknownHostException {
        List<InetSocketAddress> searchedAddresses = Arrays.stream(InetAddress.getAllByName("google.com"))
                .map(addr -> new InetSocketAddress(addr, 222)).collect(Collectors.toList());

        MultipleAddresses multipleAddresses = new MultipleAddresses(searchedAddresses.get(searchedAddresses.size() - 1));
        List<InetSocketAddress> addresses = new ArrayList<>(multipleAddresses.getAllAddresses());

        Assert.assertEquals(1, addresses.size());
        Assert.assertEquals(searchedAddresses.get(searchedAddresses.size() - 1), addresses.get(0));

        multipleAddresses.recreateSocketAddresses();

        addresses = new ArrayList<>(multipleAddresses.getAllAddresses());
        Assert.assertEquals(1, addresses.size());
        Assert.assertEquals(searchedAddresses.get(0), addresses.get(0));
    }

    @Test
    public void testRecreateSocketAddressesWithWrongAddresses() {
        InetSocketAddress address = new InetSocketAddress("locahost", 222);
        MultipleAddresses multipleAddresses = new MultipleAddresses(address);
        multipleAddresses.recreateSocketAddresses();

        Assert.assertEquals(address, multipleAddresses.getOne());
    }

    @Test
    public void testEquals() {
        List<InetSocketAddress> addresses = getAddressList();

        MultipleAddresses multipleAddresses = new MultipleAddresses(addresses);
        MultipleAddresses multipleAddressesEquals = new MultipleAddresses(addresses);

        Assert.assertEquals(multipleAddresses, multipleAddressesEquals);

        MultipleAddresses multipleAddressesNotEquals = new MultipleAddresses(getAddressList());

        Assert.assertNotEquals(multipleAddresses, multipleAddressesNotEquals);
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

}
