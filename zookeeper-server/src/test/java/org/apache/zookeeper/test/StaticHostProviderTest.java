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

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.client.StaticHostProvider;
import org.apache.zookeeper.common.Time;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.matchers.JUnitMatchers.hasItems;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StaticHostProviderTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(StaticHostProviderTest.class);

    @Test
    public void testNextGoesRound() {
        HostProvider hostProvider = getHostProvider((byte) 2);
        InetSocketAddress first = hostProvider.next(0);
        assertTrue(first instanceof InetSocketAddress);
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

    @Test
    public void testTwoConsequitiveCallsToNextReturnDifferentElement() {
        HostProvider hostProvider = getHostProvider((byte) 2);
        assertNotSame(hostProvider.next(0), hostProvider.next(0));
    }

    @Test
    public void testOnConnectDoesNotReset() {
        HostProvider hostProvider = getHostProvider((byte) 2);
        InetSocketAddress first = hostProvider.next(0);
        hostProvider.onConnected();
        InetSocketAddress second = hostProvider.next(0);
        assertNotSame(first, second);
    }

    @Test
    public void testLiteralIPNoReverseNS() {
        byte size = 30;
        HostProvider hostProvider = getHostProviderUnresolved(size);
        for (int i = 0; i < size; i++) {
            InetSocketAddress next = hostProvider.next(0);
            assertTrue(next instanceof InetSocketAddress);
            assertTrue(!next.isUnresolved());
            assertTrue("InetSocketAddress must not have hostname part " +
                    next.toString(), next.toString().startsWith("/"));
            // Do NOT trigger the reverse name service lookup.
            String hostname = next.getHostName();
            // In this case, the hostname equals literal IP address.
            hostname.equals(next.getAddress().getHostAddress());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyServerAddressesList() {
        HostProvider hp = new StaticHostProvider(new ArrayList<InetSocketAddress>());
    }

    @Test
    public void testInvalidHostAddresses() {
        // Arrange
        final List<InetSocketAddress> invalidAddresses = new ArrayList<InetSocketAddress>();
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("a", 1234);
        invalidAddresses.add(unresolved);
        StaticHostProvider.Resolver resolver = new StaticHostProvider.Resolver() {
            @Override
            public InetAddress[] getAllByName(String name) throws UnknownHostException {
                throw new UnknownHostException();
            }
        };
        StaticHostProvider sp = new StaticHostProvider(invalidAddresses, resolver);

        // Act & Assert
        InetSocketAddress n1 = sp.next(0);
        assertTrue("Provider should return unresolved address is host is unresolvable", n1.isUnresolved());
        assertSame("Provider should return original address is host is unresolvable", unresolved, n1);
    }

    @Test
    public void testReResolvingSingle() throws UnknownHostException {
        // Arrange
        byte size = 1;
        ArrayList<InetSocketAddress> list = new ArrayList<InetSocketAddress>(size);

        // Test a hostname that resolves to a single address
        list.add(InetSocketAddress.createUnresolved("issues.apache.org", 1234));

        final InetAddress issuesApacheOrg = mock(InetAddress.class);
        when(issuesApacheOrg.getHostAddress()).thenReturn("192.168.1.1");
        when(issuesApacheOrg.toString()).thenReturn("issues.apache.org");
        when(issuesApacheOrg.getHostName()).thenReturn("issues.apache.org");

        StaticHostProvider.Resolver resolver = new StaticHostProvider.Resolver() {
            @Override
            public InetAddress[] getAllByName(String name) {
                return new InetAddress[] {
                        issuesApacheOrg
                };
            }
        };
        StaticHostProvider.Resolver spyResolver = spy(resolver);

        // Act
        StaticHostProvider hostProvider = new StaticHostProvider(list, spyResolver);
        for (int i = 0; i < 10; i++) {
            InetSocketAddress next = hostProvider.next(0);
            assertEquals(issuesApacheOrg, next.getAddress());
        }

        // Assert
        // Resolver called 10 times, because we shouldn't cache the resolved addresses
        verify(spyResolver, times(10)).getAllByName("issues.apache.org"); // resolution occurred
    }

    @Test
    public void testReResolvingMultiple() throws UnknownHostException {
        // Arrange
        byte size = 1;
        ArrayList<InetSocketAddress> list = new ArrayList<InetSocketAddress>(size);

        // Test a hostname that resolves to multiple addresses
        list.add(InetSocketAddress.createUnresolved("www.apache.org", 1234));

        final InetAddress apacheOrg1 = mock(InetAddress.class);
        when(apacheOrg1.getHostAddress()).thenReturn("192.168.1.1");
        when(apacheOrg1.toString()).thenReturn("www.apache.org");
        when(apacheOrg1.getHostName()).thenReturn("www.apache.org");

        final InetAddress apacheOrg2 = mock(InetAddress.class);
        when(apacheOrg2.getHostAddress()).thenReturn("192.168.1.2");
        when(apacheOrg2.toString()).thenReturn("www.apache.org");
        when(apacheOrg2.getHostName()).thenReturn("www.apache.org");

        final List<InetAddress> resolvedAddresses = new ArrayList<InetAddress>();
        resolvedAddresses.add(apacheOrg1);
        resolvedAddresses.add(apacheOrg2);
        StaticHostProvider.Resolver resolver = new StaticHostProvider.Resolver() {
            @Override
            public InetAddress[] getAllByName(String name) {
                return resolvedAddresses.toArray(new InetAddress[resolvedAddresses.size()]);
            }
        };
        StaticHostProvider.Resolver spyResolver = spy(resolver);

        // Act & Assert
        StaticHostProvider hostProvider = new StaticHostProvider(list, spyResolver);
        assertEquals(1, hostProvider.size()); // single address not extracted

        for (int i = 0; i < 10; i++) {
            InetSocketAddress next = hostProvider.next(0);
            assertThat("Bad IP address returned", next.getAddress().getHostAddress(), anyOf(equalTo(apacheOrg1.getHostAddress()), equalTo(apacheOrg2.getHostAddress())));
            assertEquals(1, hostProvider.size()); // resolve() call keeps the size of provider
        }
        // Resolver called 10 times, because we shouldn't cache the resolved addresses
        verify(spyResolver, times(10)).getAllByName("www.apache.org"); // resolution occurred
    }

    @Test
    public void testReResolveMultipleOneFailing() throws UnknownHostException {
        // Arrange
        final List<InetSocketAddress> list = new ArrayList<InetSocketAddress>();
        list.add(InetSocketAddress.createUnresolved("www.apache.org", 1234));
        final List<String> ipList = new ArrayList<String>();
        final List<InetAddress> resolvedAddresses = new ArrayList<InetAddress>();
        for (int i = 0; i < 3; i++) {
            ipList.add(String.format("192.168.1.%d", i+1));
            final InetAddress apacheOrg = mock(InetAddress.class);
            when(apacheOrg.getHostAddress()).thenReturn(String.format("192.168.1.%d", i+1));
            when(apacheOrg.toString()).thenReturn(String.format("192.168.1.%d", i+1));
            when(apacheOrg.getHostName()).thenReturn("www.apache.org");
            resolvedAddresses.add(apacheOrg);
        }

        StaticHostProvider.Resolver resolver = new StaticHostProvider.Resolver() {
            @Override
            public InetAddress[] getAllByName(String name) {
                return resolvedAddresses.toArray(new InetAddress[resolvedAddresses.size()]);
            }
        };
        StaticHostProvider.Resolver spyResolver = spy(resolver);
        StaticHostProvider hostProvider = new StaticHostProvider(list, spyResolver);

        // Act & Assert
        InetSocketAddress resolvedFirst = hostProvider.next(0);
        assertFalse("HostProvider should return resolved addresses", resolvedFirst.isUnresolved());
        assertThat("Bad IP address returned", ipList, hasItems(resolvedFirst.getAddress().getHostAddress()));

        hostProvider.onConnected(); // first address worked

        InetSocketAddress resolvedSecond = hostProvider.next(0);
        assertFalse("HostProvider should return resolved addresses", resolvedSecond.isUnresolved());
        assertThat("Bad IP address returned", ipList, hasItems(resolvedSecond.getAddress().getHostAddress()));

        // Second address doesn't work, so we don't call onConnected() this time
        // StaticHostProvider should try to re-resolve the address in this case

        InetSocketAddress resolvedThird = hostProvider.next(0);
        assertFalse("HostProvider should return resolved addresses", resolvedThird.isUnresolved());
        assertThat("Bad IP address returned", ipList, hasItems(resolvedThird.getAddress().getHostAddress()));

        verify(spyResolver, times(3)).getAllByName("www.apache.org");  // resolution occured every time
    }

    @Test
    public void testEmptyResolution() throws UnknownHostException {
        // Arrange
        final List<InetSocketAddress> list = new ArrayList<InetSocketAddress>();
        list.add(InetSocketAddress.createUnresolved("www.apache.org", 1234));
        list.add(InetSocketAddress.createUnresolved("www.google.com", 1234));
        final List<InetAddress> resolvedAddresses = new ArrayList<InetAddress>();

        final InetAddress apacheOrg1 = mock(InetAddress.class);
        when(apacheOrg1.getHostAddress()).thenReturn("192.168.1.1");
        when(apacheOrg1.toString()).thenReturn("www.apache.org");
        when(apacheOrg1.getHostName()).thenReturn("www.apache.org");

        resolvedAddresses.add(apacheOrg1);

        StaticHostProvider.Resolver resolver = new StaticHostProvider.Resolver() {
            @Override
            public InetAddress[] getAllByName(String name) {
                if ("www.apache.org".equalsIgnoreCase(name)) {
                    return resolvedAddresses.toArray(new InetAddress[resolvedAddresses.size()]);
                } else {
                    return new InetAddress[0];
                }
            }
        };
        StaticHostProvider.Resolver spyResolver = spy(resolver);
        StaticHostProvider hostProvider = new StaticHostProvider(list, spyResolver);

        // Act & Assert
        for (int i = 0; i < 10; i++) {
            InetSocketAddress resolved = hostProvider.next(0);
            hostProvider.onConnected();
            if (resolved.getHostName().equals("www.google.com")) {
                assertTrue("HostProvider should return unresolved address if host is unresolvable", resolved.isUnresolved());
            } else {
                assertFalse("HostProvider should return resolved addresses", resolved.isUnresolved());
                assertEquals("192.168.1.1", resolved.getAddress().getHostAddress());
            }
        }

        verify(spyResolver, times(5)).getAllByName("www.apache.org");
        verify(spyResolver, times(5)).getAllByName("www.google.com");
    }

    @Test
    public void testReResolvingLocalhost() {
        byte size = 2;
        ArrayList<InetSocketAddress> list = new ArrayList<InetSocketAddress>(size);

        // Test a hostname that resolves to multiple addresses
        list.add(InetSocketAddress.createUnresolved("localhost", 1234));
        list.add(InetSocketAddress.createUnresolved("localhost", 1235));
        StaticHostProvider hostProvider = new StaticHostProvider(list);
        int sizeBefore = hostProvider.size();
        InetSocketAddress next = hostProvider.next(0);
        next = hostProvider.next(0);
        assertTrue("Different number of addresses in the list: " + hostProvider.size() +
                " (after), " + sizeBefore + " (before)", hostProvider.size() == sizeBefore);
    }

    private StaticHostProvider getHostProviderUnresolved(byte size) {
        return new StaticHostProvider(getUnresolvedServerAddresses(size));
    }

    private Collection<InetSocketAddress> getUnresolvedServerAddresses(byte size) {
        ArrayList<InetSocketAddress> list = new ArrayList<InetSocketAddress>(size);
        while (size > 0) {
            list.add(InetSocketAddress.createUnresolved("192.0.2." + size, 1234 + size));
            --size;
        }
        return list;
    }

    private StaticHostProvider getHostProvider(byte size) {
        ArrayList<InetSocketAddress> list = new ArrayList<InetSocketAddress>(
                size);
        while (size > 0) {
            try {
                list.add(new InetSocketAddress(InetAddress.getByAddress(new byte[]{-64, 0, 2, size}), 1234 + size));
            } catch (UnknownHostException e) {
                LOG.error("Exception while resolving address", e);
                fail("Failed to resolve address");
            }
            --size;
        }
        return new StaticHostProvider(list);
    }
}
