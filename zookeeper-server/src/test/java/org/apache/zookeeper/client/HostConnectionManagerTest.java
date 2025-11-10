/*
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.Test;

public class HostConnectionManagerTest extends ZKTestCase {

    @Test
    public void testStaticHostProviderAfterRefactoring() throws UnknownHostException {
        final List<InetSocketAddress> servers = Arrays.asList(
                createAddress(1),
                createAddress(2)
        );

        final HostProvider provider = new StaticHostProvider(servers);

        assertEquals(2, provider.size());
        assertNotNull(provider.next(0));
        provider.onConnected();

        final Collection<InetSocketAddress> newServers = Collections.singletonList(createAddress(3));

        final InetSocketAddress currentHost = createAddress(1);
        provider.updateServerList(newServers, currentHost);

        assertEquals(1, provider.size());
    }

    @Test
    public void testStaticHostProviderWithCustomResolver() {
        final List<InetSocketAddress> servers = Collections.singletonList(
                InetSocketAddress.createUnresolved("test.example.com", 2181)
        );

        final StaticHostProvider.Resolver customResolver =
        name -> new InetAddress[]{InetAddress.getByAddress("test.example.com", new byte[]{127, 0, 0, 1})};

        final StaticHostProvider provider = new StaticHostProvider(servers, customResolver);
        assertEquals(1, provider.size());

        final InetSocketAddress resolved = provider.next(0);
        assertNotNull(resolved);
        assertArrayEquals(new byte[]{127, 0, 0, 1}, resolved.getAddress().getAddress());
        assertEquals(2181, resolved.getPort());
    }

    @Test
    public void testStaticHostProviderUpdateServerList() throws UnknownHostException {
        final List<InetSocketAddress> initialServers = Arrays.asList(
            createAddress(1),
            createAddress(2)
        );

        final StaticHostProvider provider = new StaticHostProvider(initialServers);
        assertEquals(2, provider.size());

        // Update server list
        final List<InetSocketAddress> newServers = Arrays.asList(
            createAddress(1),  // Keep existing
            createAddress(3),  // New server
            createAddress(4)   // New server
        );

        final InetSocketAddress currentHost = createAddress(1);
        provider.updateServerList(newServers, currentHost);

        // Verify the server list was updated
        assertEquals(3, provider.size());
    }

    @Test
    public void testStaticHostProviderOnConnected() throws UnknownHostException {
        final List<InetSocketAddress> servers = Arrays.asList(
            createAddress(1),
            createAddress(2)
        );

        final StaticHostProvider provider = new StaticHostProvider(servers);

        // Get first server
        InetSocketAddress first = provider.next(0);
        assertNotNull(first);

        // Call onConnected()
        provider.onConnected();

        // After onConnected(), the next() call should move to the next server in round-robin
        final InetSocketAddress second = provider.next(0);
        assertNotNull(second);
        assertNotSame(first, second);

        // Verify round-robin behavior continues properly
        final InetSocketAddress third = provider.next(0);
        assertNotNull(third);

        // After going through both servers, should cycle back to first
        assertEquals(first, third);
    }

    private static InetSocketAddress createAddress(final int lastOctet) throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 10, 10, (byte) lastOctet}), 2181);
    }
}
