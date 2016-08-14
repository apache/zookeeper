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

package org.apache.zookeeper.test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Random;

import org.apache.zookeeper.ServerCfg;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.client.StaticHostProvider;
import org.apache.zookeeper.common.Time;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class StaticHostProviderTest extends ZKTestCase {
    private Random r = new Random(1);

    @Test
    public void testNextGoesRound() {
        HostProvider hostProvider = getHostProvider((byte) 2);
        ServerCfg first = hostProvider.next(0);
        assertTrue(first != null);
        hostProvider.next(0);
        assertEquals(first, hostProvider.next(0));
    }

    @Test
    public void testNextGoesRoundAndSleeps() {
        byte size = 2;
        HostProvider hostProvider = getHostProvider(size);
        while (size > 0) {
            hostProvider.next(0);
            --size;
        }
        long start = Time.currentElapsedTime();
        hostProvider.next(1000);
        long stop = Time.currentElapsedTime();
        assertTrue(900 <= stop - start);
    }

    @Test
    public void testNextDoesNotSleepForZero() {
        byte size = 2;
        HostProvider hostProvider = getHostProvider(size);
        while (size > 0) {
            hostProvider.next(0);
            --size;
        }
        long start = Time.currentElapsedTime();
        hostProvider.next(0);
        long stop = Time.currentElapsedTime();
        assertTrue(5 > stop - start);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTwoInvalidHostAddresses() {
        ArrayList<ServerCfg> list = new ArrayList<ServerCfg>();
        list.add(new ServerCfg("a...", new InetSocketAddress("a...", 2181)));
        list.add(new ServerCfg("b...", new InetSocketAddress("b...", 2181)));
        new StaticHostProvider(list);
    }

    @Test
    public void testOneInvalidHostAddresses() {
        Collection<InetSocketAddress> addrs = getServerAddresses((byte) 1);

        Collection<ServerCfg> serverCfgs = new ArrayList<ServerCfg>();
        for (final InetSocketAddress addr : addrs) {
            serverCfgs.add(new ServerCfg("a...",
                    new InetSocketAddress("a...", 2181)));
        }

        StaticHostProvider sp = new StaticHostProvider(serverCfgs);
        ServerCfg n1 = sp.next(0);
        ServerCfg n2 = sp.next(0);

        assertEquals(n2, n1);
    }

    @Test
    public void testTwoConsequitiveCallsToNextReturnDifferentElement() {
        HostProvider hostProvider = getHostProvider((byte) 2);
        assertNotSame(hostProvider.next(0), hostProvider.next(0));
    }

    @Test
    public void testOnConnectDoesNotReset() {
        HostProvider hostProvider = getHostProvider((byte) 2);
        ServerCfg first = hostProvider.next(0);
        hostProvider.onConnected();
        ServerCfg second = hostProvider.next(0);
        assertNotSame(first, second);
    }

    private final double slackPercent = 10;
    private final int numClients = 10000;

    @Test
    public void testUpdateClientMigrateOrNot() throws UnknownHostException {
        HostProvider hostProvider = getHostProvider((byte) 4); // 10.10.10.4:1238, 10.10.10.3:1237, 10.10.10.2:1236, 10.10.10.1:1235
        Collection<InetSocketAddress> newAddrList = getServerAddresses((byte) 3); // 10.10.10.3:1237, 10.10.10.2:1236, 10.10.10.1:1235
        Collection<ServerCfg> newList = inetToServerCfgList(newAddrList);

        InetSocketAddress myServer = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 10, 10, 3}), 1237);

        // Number of machines becomes smaller, my server is in the new cluster
        boolean disconnectRequired = hostProvider.updateServerList(newList, myServer);
        assertTrue(!disconnectRequired);
        hostProvider.onConnected();
        
        // Number of machines stayed the same, my server is in the new cluster
        disconnectRequired = hostProvider.updateServerList(newList, myServer);
        assertTrue(!disconnectRequired);
        hostProvider.onConnected();

        // Number of machines became smaller, my server is not in the new
        // cluster
        newAddrList = getServerAddresses((byte) 2); // 10.10.10.2:1236, 10.10.10.1:1235
        newList = inetToServerCfgList(newAddrList);

        disconnectRequired = hostProvider.updateServerList(newList, myServer);
        assertTrue(disconnectRequired);
        hostProvider.onConnected();

        // Number of machines stayed the same, my server is not in the new
        // cluster
        disconnectRequired = hostProvider.updateServerList(newList, myServer);
        assertTrue(disconnectRequired);
        hostProvider.onConnected();

        // Number of machines increased, my server is not in the new cluster
        newAddrList = new ArrayList<InetSocketAddress>(3);
        newList = new ArrayList<ServerCfg>();
        for (byte i = 4; i > 1; i--) { // 10.10.10.4:1238, 10.10.10.3:1237, 10.10.10.2:1236
            final InetSocketAddress addr = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 10, 10, i}), 1234 + i);
            newList.add(new ServerCfg(addr.getHostString(), addr));
        }
        myServer = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 10, 10, 1}), 1235);
        disconnectRequired = hostProvider.updateServerList(newList, myServer);
        assertTrue(disconnectRequired);
        hostProvider.onConnected();

        // Number of machines increased, my server is in the new cluster
        // Here whether to move or not depends on the difference of cluster
        // sizes
        // With probability 1 - |old|/|new} the client disconnects
        // In the test below 1-9/10 = 1/10 chance of disconnecting
        HostProvider[] hostProviderArray = new HostProvider[numClients];
        newAddrList = getServerAddresses((byte) 10);
        newList = inetToServerCfgList(newAddrList);

        int numDisconnects = 0;
        for (int i = 0; i < numClients; i++) {
            hostProviderArray[i] = getHostProvider((byte) 9);
            disconnectRequired = hostProviderArray[i].updateServerList(newList, myServer);
            if (disconnectRequired)
                numDisconnects++;
        }
        hostProvider.onConnected();

       // should be numClients/10 in expectation, we test that its numClients/10 +- slackPercent 
        assertTrue(numDisconnects < upperboundCPS(numClients, 10));
    }

    @Test
    public void testUpdateMigrationGoesRound() throws UnknownHostException {
        HostProvider hostProvider = getHostProvider((byte) 4);
        // old list (just the ports): 1238, 1237, 1236, 1235
        Collection<InetSocketAddress> newList = new ArrayList<InetSocketAddress>(
                10);
        for (byte i = 12; i > 2; i--) { // 1246, 1245, 1244, 1243, 1242, 1241,
                                       // 1240, 1239, 1238, 1237
            newList.add(new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 10, 10, i}), 1234 + i));
        }

        // servers from the old list that appear in the new list
        Collection<InetSocketAddress> oldStaying = new ArrayList<InetSocketAddress>(2);
        for (byte i = 4; i > 2; i--) { // 1238, 1237
            oldStaying.add(new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 10, 10, i}), 1234 + i));
        }

        // servers in the new list that are not in the old list
        Collection<InetSocketAddress> newComing = new ArrayList<InetSocketAddress>(10);
        for (byte i = 12; i > 4; i--) {// 1246, 1245, 1244, 1243, 1242, 1241, 1240, 1139
            newComing.add(new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 10, 10, i}), 1234 + i));
        }

        // Number of machines increases, my server is not in the new cluster
        // load on old servers must be decreased, so must connect to one of the
        // new servers
        // i.e., pNew = 1.
        Collection<ServerCfg> serverCfgList = inetToServerCfgList(newList);
        boolean disconnectRequired = hostProvider.updateServerList(serverCfgList, new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 10, 10, 1}), 1235));
        assertTrue(disconnectRequired);

        // This means reconfigMode = true, and nextHostInReconfigMode will be
        // called from next
        // Since pNew = 1 we should first try the new servers
        ArrayList<InetSocketAddress> seen = new ArrayList<InetSocketAddress>();
        for (int i = 0; i < newComing.size(); i++) {
            ServerCfg serverCfg = hostProvider.next(0);
            assertTrue(newComing.contains(serverCfg.getInetAddress()));
            assertTrue(!seen.contains(serverCfg.getInetAddress()));
            seen.add(serverCfg.getInetAddress());
        }

        // Next the old servers
        seen.clear();
        for (int i = 0; i < oldStaying.size(); i++) {
            ServerCfg serverCfg = hostProvider.next(0);
            assertTrue(oldStaying.contains(serverCfg.getInetAddress()));
            assertTrue(!seen.contains(serverCfg.getInetAddress()));
            seen.add(serverCfg.getInetAddress());
        }

        // And now it goes back to normal next() so it should be everything
        // together like in testNextGoesRound()
        ServerCfg serverCfg = hostProvider.next(0);
        assertTrue(serverCfg != null);
        for (int i = 0; i < newList.size() - 1; i++) {
            hostProvider.next(0);
        }

        assertEquals(serverCfg, hostProvider.next(0));
        hostProvider.onConnected();
    }

    @Test
    public void testUpdateLoadBalancing() throws UnknownHostException {
        // Start with 9 servers and 10000 clients
        boolean disconnectRequired;
        HostProvider[] hostProviderArray = new HostProvider[numClients];
        ServerCfg[] curHostForEachClient = new ServerCfg[numClients];
        int[] numClientsPerHost = new int[9];

        // initialization
        for (int i = 0; i < numClients; i++) {
            hostProviderArray[i] = getHostProvider((byte) 9);
            curHostForEachClient[i] = hostProviderArray[i].next(0);
            numClientsPerHost[curHostForEachClient[i].getPort() - 1235]++;
            hostProviderArray[i].onConnected();
        }

        for (int i = 0; i < 9; i++) {
            assertTrue(numClientsPerHost[i] <= upperboundCPS(numClients, 9));
            assertTrue(numClientsPerHost[i] >= lowerboundCPS(numClients, 9));
            numClientsPerHost[i] = 0; // prepare for next test
        }

        // remove host number 8 (the last one in a list of 9 hosts)
        Collection<InetSocketAddress> newList = getServerAddresses((byte) 8);
        Collection<ServerCfg> serverCfgList = inetToServerCfgList(newList);
        for (int i = 0; i < numClients; i++) {
            disconnectRequired = hostProviderArray[i].updateServerList(serverCfgList, curHostForEachClient[i].getInetAddress());
            if (disconnectRequired) curHostForEachClient[i] = hostProviderArray[i].next(0);
            numClientsPerHost[curHostForEachClient[i].getPort() - 1235]++;
            hostProviderArray[i].onConnected();
        }

        for (int i = 0; i < 8; i++) {
            assertTrue(numClientsPerHost[i] <= upperboundCPS(numClients, 8));
            assertTrue(numClientsPerHost[i] >= lowerboundCPS(numClients, 8));
            numClientsPerHost[i] = 0; // prepare for next test
        }
        assertTrue(numClientsPerHost[8] == 0);

        // remove hosts number 6 and 7 (the currently last two in the list)
        newList = getServerAddresses((byte) 6);
        serverCfgList = inetToServerCfgList(newList);
        for (int i = 0; i < numClients; i++) {
            disconnectRequired = hostProviderArray[i].updateServerList(serverCfgList, curHostForEachClient[i].getInetAddress());
            if (disconnectRequired) curHostForEachClient[i] = hostProviderArray[i].next(0);
            numClientsPerHost[curHostForEachClient[i].getPort() - 1235]++;
            hostProviderArray[i].onConnected();
        }

        for (int i = 0; i < 6; i++) {
            assertTrue(numClientsPerHost[i] <= upperboundCPS(numClients, 6));
            assertTrue(numClientsPerHost[i] >= lowerboundCPS(numClients, 6));
            numClientsPerHost[i] = 0; // prepare for next test
        }
        assertTrue(numClientsPerHost[6] == 0);
        assertTrue(numClientsPerHost[7] == 0);
        assertTrue(numClientsPerHost[8] == 0);

        // remove host number 0 (the first one in the current list)
        // and add back hosts 6, 7 and 8
        newList = new ArrayList<InetSocketAddress>(8);
        for (byte i = 9; i > 1; i--) {
            newList.add(new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 10, 10, i}), 1234 + i));
        }
        serverCfgList = inetToServerCfgList(newList);
        for (int i = 0; i < numClients; i++) {
            disconnectRequired = hostProviderArray[i].updateServerList(serverCfgList, curHostForEachClient[i].getInetAddress());
            if (disconnectRequired) curHostForEachClient[i] = hostProviderArray[i].next(0);
            numClientsPerHost[curHostForEachClient[i].getPort() - 1235]++;
            hostProviderArray[i].onConnected();
        }

        assertTrue(numClientsPerHost[0] == 0);

        for (int i = 1; i < 9; i++) {
            assertTrue(numClientsPerHost[i] <= upperboundCPS(numClients, 8));
            assertTrue(numClientsPerHost[i] >= lowerboundCPS(numClients, 8));
            numClientsPerHost[i] = 0; // prepare for next test
        }

        // add back host number 0
        newList = getServerAddresses((byte) 9);
        serverCfgList = inetToServerCfgList(newList);
        for (int i = 0; i < numClients; i++) {
            disconnectRequired = hostProviderArray[i].updateServerList(serverCfgList, curHostForEachClient[i].getInetAddress());
            if (disconnectRequired) curHostForEachClient[i] = hostProviderArray[i].next(0);
            numClientsPerHost[curHostForEachClient[i].getPort() - 1235]++;
            hostProviderArray[i].onConnected();
        }

        for (int i = 0; i < 9; i++) {
            assertTrue(numClientsPerHost[i] <= upperboundCPS(numClients, 9));
            assertTrue(numClientsPerHost[i] >= lowerboundCPS(numClients, 9));
        }
    }

    @Test
    public void testNoCurrentHostDuringNormalMode() throws UnknownHostException {
        // Start with 9 servers and 10000 clients
        boolean disconnectRequired;
        StaticHostProvider[] hostProviderArray = new StaticHostProvider[numClients];
        ServerCfg[] curHostForEachClient = new ServerCfg[numClients];
        int[] numClientsPerHost = new int[9];

        // initialization
        for (int i = 0; i < numClients; i++) {
            hostProviderArray[i] = getHostProvider((byte) 9);
            if (i >= (numClients / 2)) {
                curHostForEachClient[i] = hostProviderArray[i].next(0);
            } else {
                // its supposed to be the first server on serverList.
                // we'll set it later, see below (*)
                curHostForEachClient[i] = null;
            }
        }

        // remove hosts 7 and 8 (the last two in a list of 9 hosts)
        Collection<InetSocketAddress> newList = getServerAddresses((byte) 7);
        Collection<ServerCfg> serverCfgList = inetToServerCfgList(newList);
        for (int i = 0; i < numClients; i++) {
            // tests the case currentHost == null && lastIndex == -1
            // calls next for clients with index < numClients/2
            disconnectRequired = hostProviderArray[i].updateServerList(serverCfgList,
                    curHostForEachClient[i].getInetAddress());
            if (disconnectRequired)
                curHostForEachClient[i] = hostProviderArray[i].next(0);
            else if (curHostForEachClient[i] == null) {
                // (*) setting it to what it should be
                curHostForEachClient[i] = hostProviderArray[i]
                        .getServerAtIndex(0);
            }
            numClientsPerHost[curHostForEachClient[i].getPort() - 1235]++;
            // sets lastIndex, resets reconfigMode
            hostProviderArray[i].onConnected();
        }

        for (int i = 0; i < 7; i++) {
            assertTrue(numClientsPerHost[i] <= upperboundCPS(numClients, 7));
            assertTrue(numClientsPerHost[i] >= lowerboundCPS(numClients, 7));
            numClientsPerHost[i] = 0; // prepare for next test
        }
        assertTrue(numClientsPerHost[7] == 0);
        assertTrue(numClientsPerHost[8] == 0);

        // add back server 7
        newList = getServerAddresses((byte) 8);
        serverCfgList = inetToServerCfgList(newList);
        for (int i = 0; i < numClients; i++) {
            InetSocketAddress myServer = (i < (numClients / 2)) ? null
                    : curHostForEachClient[i].getInetAddress();
            // tests the case currentHost == null && lastIndex >= 0
            disconnectRequired = hostProviderArray[i].updateServerList(serverCfgList,
                    myServer);
            if (disconnectRequired)
                curHostForEachClient[i] = hostProviderArray[i].next(0);
            numClientsPerHost[curHostForEachClient[i].getPort() - 1235]++;
            hostProviderArray[i].onConnected();
        }

        for (int i = 0; i < 8; i++) {
            assertTrue(numClientsPerHost[i] <= upperboundCPS(numClients, 8));
            assertTrue(numClientsPerHost[i] >= lowerboundCPS(numClients, 8));
        }
    }

    @Test
    public void testReconfigDuringReconfigMode() throws UnknownHostException {
        // Start with 9 servers and 10000 clients
        boolean disconnectRequired;
        StaticHostProvider[] hostProviderArray = new StaticHostProvider[numClients];
        ServerCfg[] curHostForEachClient = new ServerCfg[numClients];
        int[] numClientsPerHost = new int[9];

        // initialization
        for (int i = 0; i < numClients; i++) {
            hostProviderArray[i] = getHostProvider((byte) 9);
            curHostForEachClient[i] = hostProviderArray[i].next(0);
        }

        // remove hosts 7 and 8 (the last two in a list of 9 hosts)
        Collection<InetSocketAddress> newList = getServerAddresses((byte) 7);
        Collection<ServerCfg> serverCfgList = inetToServerCfgList(newList);
        for (int i = 0; i < numClients; i++) {
            // sets reconfigMode
            hostProviderArray[i].updateServerList(serverCfgList,
                    curHostForEachClient[i].getInetAddress());
        }

        // add back servers 7 and 8 while still in reconfigMode (we didn't call
        // next)
        newList = getServerAddresses((byte) 9);
        serverCfgList = inetToServerCfgList(newList);
        for (int i = 0; i < numClients; i++) {
            InetSocketAddress myServer = (i < (numClients / 2)) ? null
                    : curHostForEachClient[i].getInetAddress();
            // for i < (numClients/2) this tests the case currentHost == null &&
            // reconfigMode = true
            // for i >= (numClients/2) this tests the case currentHost!=null &&
            // reconfigMode = true
            disconnectRequired = hostProviderArray[i].updateServerList(serverCfgList,
                    myServer);
            if (disconnectRequired)
                curHostForEachClient[i] = hostProviderArray[i].next(0);
            else {
                // currentIndex was set by the call to updateServerList, which
                // called next
                curHostForEachClient[i] = hostProviderArray[i]
                        .getServerAtCurrentIndex();
            }
            numClientsPerHost[curHostForEachClient[i].getPort() - 1235]++;
            hostProviderArray[i].onConnected();
        }

        for (int i = 0; i < 9; i++) {
            assertTrue(numClientsPerHost[i] <= upperboundCPS(numClients, 9));
            assertTrue(numClientsPerHost[i] >= lowerboundCPS(numClients, 9));
        }
    }

    private StaticHostProvider getHostProvider(byte size) {
        return new StaticHostProvider(inetToServerCfgList(getServerAddresses(size)), r.nextLong());
    }

    private HashMap<Byte, Collection<InetSocketAddress>> precomputedLists = new
            HashMap<Byte, Collection<InetSocketAddress>>();
    private Collection<InetSocketAddress> getServerAddresses(byte size)   {
        if (precomputedLists.containsKey(size)) return precomputedLists.get(size);
        ArrayList<InetSocketAddress> list = new ArrayList<InetSocketAddress>(
                size);
        while (size > 0) {
            try {
                list.add(new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 10, 10, size}), 1234 + size));
            } catch (UnknownHostException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            --size;
        }
        precomputedLists.put(size, list);
        return list;
    }

    private double lowerboundCPS(int numClients, int numServers) {
        return (1 - slackPercent/100.0) * numClients / numServers;
    }

    private double upperboundCPS(int numClients, int numServers) {
        return (1 + slackPercent/100.0) * numClients / numServers;
    }

    @Test
    public void testLiteralIPNoReverseNS() throws Exception {
        byte size = 30;
        HostProvider hostProvider = getHostProviderUnresolved(size);
        for (int i = 0; i < size; i++) {
            ServerCfg next = hostProvider.next(0);
            assertTrue(next instanceof ServerCfg);
            assertTrue(!next.getInetAddress().isUnresolved());
            assertTrue(!next.toString().startsWith("/"));
            // Do NOT trigger the reverse name service lookup.
            String hostname = next.getHostString();
            // In this case, the hostname equals literal IP address.
            hostname.equals(next.getInetAddress().getAddress().getHostAddress());
        }
    }

    private StaticHostProvider getHostProviderUnresolved(byte size)
            throws UnknownHostException {
        return new StaticHostProvider(inetToServerCfgList(getUnresolvedServerAddresses(size)), r.nextLong());
    }

    private Collection<InetSocketAddress> getUnresolvedServerAddresses(byte size) {
        ArrayList<InetSocketAddress> list = new ArrayList<InetSocketAddress>(size);
        while (size > 0) {
            list.add(InetSocketAddress.createUnresolved("10.10.10." + size, 1234 + size));
            --size;
        }
        return list;
    }

    private Collection<ServerCfg> inetToServerCfgList(
            final Collection<InetSocketAddress> inetSocketAddresses) {
        final Collection<ServerCfg> serverCfgList = new ArrayList<ServerCfg>();
        for (final InetSocketAddress addr : inetSocketAddresses) {
            serverCfgList.add(new ServerCfg(addr.getHostString(), addr));
        }

        return serverCfgList;
    }
}
