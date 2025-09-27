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


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Name;
import org.xbill.DNS.SRVRecord;

public class DnsSrvHostProviderTest {

    private static final String TEST_DNS_NAME = "_zookeeper._tcp.example.com.";
    private static final long TEST_SEED = 12345L;

    private DnsSrvHostProvider.DnsResolver mockDnsResolver;

    @BeforeEach
    public void setUp() {
        mockDnsResolver = mock(DnsSrvHostProvider.DnsResolver.class);
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(ZKClientConfig.DNS_SRV_REFRESH_INTERVAL_MS);
    }

    @Test
    public void testBasic() throws Exception {
        final SRVRecord[] srvRecords = createMockSrvRecords();
        when(mockDnsResolver.lookupSrvRecords(TEST_DNS_NAME)).thenReturn(srvRecords);

        try (final DnsSrvHostProvider hostProvider = new DnsSrvHostProvider(TEST_DNS_NAME, TEST_SEED, mockDnsResolver, null)) {
            assertEquals(3, hostProvider.size());
            assertNotNull(hostProvider.next(0));
        }
    }

    @Test
    public void testServerIteration() throws Exception {
        final SRVRecord[] srvRecords = createMockSrvRecords();
        when(mockDnsResolver.lookupSrvRecords(TEST_DNS_NAME)).thenReturn(srvRecords);

        try (final DnsSrvHostProvider hostProvider = new DnsSrvHostProvider(TEST_DNS_NAME, TEST_SEED, mockDnsResolver, null)) {
            final InetSocketAddress addr1 = hostProvider.next(0);
            final InetSocketAddress addr2 = hostProvider.next(0);
            final InetSocketAddress addr3 = hostProvider.next(0);

            assertNotNull(addr1);
            assertNotNull(addr2);
            assertNotNull(addr3);

            // cycle back to first server
            final InetSocketAddress addr4 = hostProvider.next(0);
            assertNotNull(addr4);
        }
    }

    @Test
    public void testEmptyDnsName() {
        assertThrows(IllegalArgumentException.class,
            () -> new DnsSrvHostProvider("", TEST_SEED, mockDnsResolver, null));

        assertThrows(IllegalArgumentException.class,
            () -> new DnsSrvHostProvider(null, TEST_SEED, mockDnsResolver, null));

        assertThrows(IllegalArgumentException.class,
            () -> new DnsSrvHostProvider("   ", TEST_SEED, mockDnsResolver, null));
    }

    @Test
    public void testNoSrvRecords() throws Exception {
        when(mockDnsResolver.lookupSrvRecords(TEST_DNS_NAME)).thenReturn(new SRVRecord[0]);

        assertThrows(IllegalArgumentException.class,
            () -> new DnsSrvHostProvider(TEST_DNS_NAME, TEST_SEED, mockDnsResolver, null));
    }

    @Test
    public void testDnsLookupFailure() throws Exception {
        when(mockDnsResolver.lookupSrvRecords(TEST_DNS_NAME))
                .thenThrow(new java.io.IOException("DNS lookup failed"));

        assertThrows(IllegalArgumentException.class,
                () -> new DnsSrvHostProvider(TEST_DNS_NAME, TEST_SEED, mockDnsResolver, null));
    }

    @Test
    public void testInvalidPortFiltering() throws Exception {
        // Create SRV record with invalid port (0)
        final SRVRecord invalidPortRecord = createMockSrvRecord("server1.example.com.", 0);
        final SRVRecord[] srvRecords = new SRVRecord[]{invalidPortRecord};

        when(mockDnsResolver.lookupSrvRecords(TEST_DNS_NAME)).thenReturn(srvRecords);

        assertThrows(IllegalArgumentException.class,
            () -> new DnsSrvHostProvider(TEST_DNS_NAME, TEST_SEED, mockDnsResolver, null));
    }

    @Test
    public void testTrailingDotRemoval() throws Exception {
        final SRVRecord recordWithDot = createMockSrvRecord("server1.example.com.", 2181);
        final SRVRecord[] srvRecords = new SRVRecord[]{recordWithDot};

        when(mockDnsResolver.lookupSrvRecords(TEST_DNS_NAME)).thenReturn(srvRecords);

        try (final DnsSrvHostProvider hostProvider = new DnsSrvHostProvider(TEST_DNS_NAME, TEST_SEED, mockDnsResolver, null)) {
            assertEquals(1, hostProvider.size());
            final InetSocketAddress addr = hostProvider.next(0);

            // validate trailing dot is removed
            assertEquals("server1.example.com", addr.getHostString());
        }
    }

    @Test
    public void testRefreshIntervalZeroDisablesPeriodicRefresh() throws Exception {
        // Set system property to disable refresh
        System.setProperty(ZKClientConfig.DNS_SRV_REFRESH_INTERVAL_MS, "0");

        final SRVRecord[] srvRecords = createMockSrvRecords();
        when(mockDnsResolver.lookupSrvRecords(TEST_DNS_NAME)).thenReturn(srvRecords);

        try (final DnsSrvHostProvider hostProvider = new DnsSrvHostProvider(TEST_DNS_NAME, TEST_SEED, mockDnsResolver, null)) {
            // Verify initial setup works
            assertEquals(3, hostProvider.size());

            // Wait to ensure no background refresh occurs
            Thread.sleep(100);

            // Verify DNS resolver was only called once during initialization (no periodic refresh)
            verify(mockDnsResolver, times(1)).lookupSrvRecords(TEST_DNS_NAME);

            // Verify host provider still works normally
            assertNotNull(hostProvider.next(0));

            // Test multiple next() calls to ensure functionality is not affected
            for (int i = 0; i < 5; i++) {
                assertNotNull(hostProvider.next(0));
            }

            // Verify no additional DNS calls were made
            verify(mockDnsResolver, times(1)).lookupSrvRecords(TEST_DNS_NAME);
        }
    }

    @Test
    public void testRefreshIntervalNegativeUsesDefault() throws Exception {
        // Set system property to negative value (should use default)
        System.setProperty(ZKClientConfig.DNS_SRV_REFRESH_INTERVAL_MS, "-1");

        final SRVRecord[] srvRecords = createMockSrvRecords();
        when(mockDnsResolver.lookupSrvRecords(TEST_DNS_NAME)).thenReturn(srvRecords);

        try (final DnsSrvHostProvider hostProvider = new DnsSrvHostProvider(TEST_DNS_NAME, TEST_SEED, mockDnsResolver, null)) {
            // Verify initial setup works (negative value should be handled gracefully)
            assertEquals(3, hostProvider.size());
            assertNotNull(hostProvider.next(0));
        }
    }

    private SRVRecord[] createMockSrvRecords() {
        return new SRVRecord[]{
            createMockSrvRecord("server1.example.com.", 2181),
            createMockSrvRecord("server2.example.com.", 2181),
            createMockSrvRecord("server3.example.com.", 2181)
        };
    }

    private SRVRecord createMockSrvRecord(final String target, int port) {
        try {
            Name targetName = Name.fromString(target);
            Name serviceName = Name.fromString(TEST_DNS_NAME);
            return new SRVRecord(serviceName, 1, 300, 1, 1, port, targetName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create mock SRV record", e);
        }
    }
}
