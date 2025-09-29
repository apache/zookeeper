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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.client.DnsSrvHostProvider;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.client.StaticHostProvider;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Name;
import org.xbill.DNS.SRVRecord;

public class CustomHostProviderTest extends ZKTestCase {

    private AtomicInteger counter = new AtomicInteger(3);

    private class SpecialHostProvider implements HostProvider {

        // ignores its connectstring, and next() always returns localhost:2181
        // it will count down when updateServerList() is called
        @Override
        public int size() {
            return 1;
        }
        @Override
        public InetSocketAddress next(long spinDelay) {
            return new InetSocketAddress("127.0.0.1", 2181);
        }
        @Override
        public void onConnected() {
        }
        @Override
        public boolean updateServerList(Collection<InetSocketAddress> serverAddresses, InetSocketAddress currentHost) {
            counter.decrementAndGet();
            return false;
        }

    }

    @Test
    public void testZooKeeperWithCustomHostProvider() throws IOException, InterruptedException {
        final int CLIENT_PORT = PortAssignment.unique();
        final HostProvider specialHostProvider = new SpecialHostProvider();
        int expectedCounter = 3;
        counter.set(expectedCounter);

        ZooKeeper zkDefaults = new ZooKeeper(
            "127.0.0.1:" + CLIENT_PORT,
            ClientBase.CONNECTION_TIMEOUT,
            DummyWatcher.INSTANCE,
            false);

        ZooKeeper zkSpecial = new ZooKeeper(
                "127.0.0.1:" + CLIENT_PORT,
                ClientBase.CONNECTION_TIMEOUT,
                DummyWatcher.INSTANCE,
                false,
                specialHostProvider);

        assertTrue(counter.get() == expectedCounter);
        zkDefaults.updateServerList("127.0.0.1:" + PortAssignment.unique());
        assertTrue(counter.get() == expectedCounter);

        zkSpecial.updateServerList("127.0.0.1:" + PortAssignment.unique());
        expectedCounter--;
        assertTrue(counter.get() == expectedCounter);
    }

    @Test
    public void testConfigurationMismatchValidation() throws Exception {
        final String hostPortConnectString = "zk1:2181,zk2:2181/myapp";
        try (final DnsSrvHostProvider dnsSrvHostProvider = createTestDnsSrvHostProvider()) {
            assertThrows(IllegalArgumentException.class, () -> new ZooKeeper(
                hostPortConnectString,
                ClientBase.CONNECTION_TIMEOUT,
                DummyWatcher.INSTANCE,
                false,
                dnsSrvHostProvider));
        }

        final String dnsConnectString = "dns-srv://zookeeper.example.com/myapp";
        final StaticHostProvider staticHostProvider = createTestStaticHostProvider();
        assertThrows(IllegalArgumentException.class, () -> new ZooKeeper(
                dnsConnectString,
                ClientBase.CONNECTION_TIMEOUT,
                DummyWatcher.INSTANCE,
                false,
                staticHostProvider));
    }

    @Test
    public void testValidConfigurations() throws Exception {
        // host port connect string with StaticHostProvider
        final StaticHostProvider staticHostProvider = createTestStaticHostProvider();
        final ZooKeeper zkTraditional = new ZooKeeper(
            "localhost:2181,localhost:2182/myapp",
            ClientBase.CONNECTION_TIMEOUT,
            DummyWatcher.INSTANCE,
            false,
            staticHostProvider);

        zkTraditional.close();

        // DNS SRV connect string with DnsSrvHostProvider
        try (final DnsSrvHostProvider dnsSrvProvider = createTestDnsSrvHostProvider()) {
            final ZooKeeper zkDnsSrv = new ZooKeeper(
                "dns-srv://zookeeper.example.com/myapp",
                ClientBase.CONNECTION_TIMEOUT,
                DummyWatcher.INSTANCE,
                false,
                dnsSrvProvider);

            zkDnsSrv.close();
        }
    }

    private SRVRecord[] createMockSrvRecords(String dnsName) {
        try {
            Name targetName1 = Name.fromString("server1.example.com.");
            Name targetName2 = Name.fromString("server2.example.com.");

            Name serviceName = Name.fromString(dnsName + ".");

            return new SRVRecord[]{
                new SRVRecord(serviceName, 1, 300, 1, 1, 2181, targetName1),
                new SRVRecord(serviceName, 1, 300, 1, 1, 2181, targetName2)
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to create mock SRV records", e);
        }
    }

    private DnsSrvHostProvider createTestDnsSrvHostProvider() throws Exception {
        final DnsSrvHostProvider.DnsResolver mockDnsResolver = mock(DnsSrvHostProvider.DnsResolver.class);
        final SRVRecord[] mockSrvRecords = createMockSrvRecords("zookeeper._tcp.example.com");
        when(mockDnsResolver.lookupSrvRecords("zookeeper._tcp.example.com.")).thenReturn(mockSrvRecords);

        return new DnsSrvHostProvider("zookeeper._tcp.example.com", 12345L, mockDnsResolver);
    }

    private StaticHostProvider createTestStaticHostProvider() throws Exception {
        final List<InetSocketAddress> initialServers = Collections.singletonList(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{10, 10, 10, 1}), 2181)
        );
        return new StaticHostProvider(initialServers);
    }
}
