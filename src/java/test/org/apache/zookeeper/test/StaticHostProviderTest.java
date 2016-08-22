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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.client.StaticHostProvider;
import org.junit.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;

public class StaticHostProviderTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(StaticHostProviderTest.class);
    
    @Test
    public void testNextGoesRound() throws UnknownHostException {
        HostProvider hostProvider = getHostProvider((byte) 2);
        InetSocketAddress first = hostProvider.next(0);
        assertTrue(first instanceof InetSocketAddress);
        hostProvider.next(0);
        assertEquals(first, hostProvider.next(0));
    }

    @Test
    public void testNextGoesRoundAndSleeps() throws UnknownHostException {
        byte size = 2;
        HostProvider hostProvider = getHostProvider(size);
        while (size > 0) {
            hostProvider.next(0);
            --size;
        }
        long start = System.currentTimeMillis();
        hostProvider.next(1000);
        long stop = System.currentTimeMillis();
        assertTrue(900 <= stop - start);
    }

    @Test
    public void testNextDoesNotSleepForZero() throws UnknownHostException {
        byte size = 2;
        HostProvider hostProvider = getHostProvider(size);
        while (size > 0) {
            hostProvider.next(0);
            --size;
        }
        long start = System.currentTimeMillis();
        hostProvider.next(0);
        long stop = System.currentTimeMillis();
        assertTrue(5 > stop - start);
    }

    @Test
    public void testTwoConsequitiveCallsToNextReturnDifferentElement()
            throws UnknownHostException {
        HostProvider hostProvider = getHostProvider((byte) 2);
        assertNotSame(hostProvider.next(0), hostProvider.next(0));
    }

    @Test
    public void testOnConnectDoesNotReset() throws UnknownHostException {
        HostProvider hostProvider = getHostProvider((byte) 2);
        InetSocketAddress first = hostProvider.next(0);
        hostProvider.onConnected();
        InetSocketAddress second = hostProvider.next(0);
        assertNotSame(first, second);
    }

    @Test
    public void testLiteralIPNoReverseNS() throws Exception {
        byte size = 30;
        HostProvider hostProvider = getHostProviderUnresolved(size);
        for (int i = 0; i < size; i++) {
            InetSocketAddress next = hostProvider.next(0);
            assertTrue(next instanceof InetSocketAddress);
            assertTrue(!next.isUnresolved());
            assertTrue("Did not match "+ next.toString(), !next.toString().startsWith("/"));
            // Do NOT trigger the reverse name service lookup.
            String hostname = next.getHostName();
            // In this case, the hostname equals literal IP address.
            hostname.equals(next.getAddress().getHostAddress());
        }
    }

    private StaticHostProvider getHostProviderUnresolved(byte size)
            throws UnknownHostException {
        return new StaticHostProvider(getUnresolvedServerAddresses(size));
    }

    private Collection<InetSocketAddress> getUnresolvedServerAddresses(byte size) {
        ArrayList<InetSocketAddress> list = new ArrayList<InetSocketAddress>(size);
        while (size > 0) {
            list.add(InetSocketAddress.createUnresolved("10.10.10." + size, 1234 + size));
            --size;
        }
        return list;
    }
    
    private StaticHostProvider getHostProvider(byte size)
            throws UnknownHostException {
        ArrayList<InetSocketAddress> list = new ArrayList<InetSocketAddress>(
                size);
        while (size > 0) {
            try {
                list.add(new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 10, 10, size}), 1234 + size));
            } catch (UnknownHostException e) {
                LOG.error("Exception while resolving address", e);
                fail("Failed to resolve address");
            }
            --size;
        }
        return new StaticHostProvider(list);
    }
}
