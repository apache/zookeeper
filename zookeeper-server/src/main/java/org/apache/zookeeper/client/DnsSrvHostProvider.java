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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.common.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.Type;

/**
 * DNS SRV-based HostProvider that dynamically resolves host port names from DNS SRV records.
 *
 * <p>This implementation periodically refreshes the server list by querying DNS SRV records
 * and uses HostConnectionManager for all connection management and reconfiguration logic.</p>
 *
 *
 * <p><strong>Two-Phase Update Strategy:</strong></p>
 * <ul>
 * <li><strong>Phase 1 (Background):</strong> Timer thread detects DNS changes without blocking connection request</li>
 * <li><strong>Phase 2 (Connection-time):</strong> Changes applied during actual connection attempts</li>
 * </ul>
 */
@InterfaceAudience.Public
public final class DnsSrvHostProvider implements HostProvider {

    public interface DnsResolver {
        /**
         * Performs a DNS SRV lookup for the specified name.
         *
         * @param dnsName the DNS name to look up
         * @return array of SRV records, empty array if no records found
         * @throws IOException if DNS lookup encounters an error
         */
        SRVRecord[] lookupSrvRecords(String dnsName) throws IOException;
    }

    private static final Logger LOG = LoggerFactory.getLogger(DnsSrvHostProvider.class);

    private final String dnsName;
    private final DnsResolver dnsResolver;
    private final long refreshIntervalMs;
    private final Timer dnsRefreshTimer;
    private HostConnectionManager connectionManager;

    // Track the current connected host for accurate load balancing decisions
    private final AtomicReference<InetSocketAddress> currentConnectedHost = new AtomicReference<>();
    // Track the previous server list to detect changes
    private volatile List<InetSocketAddress> previousServerList;
    private volatile List<InetSocketAddress> latestServerList;
    private final AtomicBoolean serverListChanged = new AtomicBoolean(false);

    /**
     * Constructs a DnsSrvHostProvider with the given DNS name
     *
     * @param dnsName the DNS name to query for SRV records
     * @throws IllegalArgumentException if dnsName is null or empty or invalid
     */
    public DnsSrvHostProvider(final String dnsName) {
        this(dnsName, null);
    }

    /**
     * Constructs a DnsSrvHostProvider with the given DNS name and ZKClientConfig
     *
     * @param dnsName the DNS name to query for SRV records
     * @param clientConfig ZooKeeper client configuration
     * @throws IllegalArgumentException if dnsName is null or empty or invalid
     */
    public DnsSrvHostProvider(final String dnsName, final ZKClientConfig clientConfig) {
        this(dnsName, System.currentTimeMillis() ^ dnsName.hashCode(), clientConfig);
    }

    /**
     * Constructs a DnsSrvHostProvider with the given DNS name, randomness seed and ZKClientConfig
     *
     * @param dnsName the DNS name to query for SRV records
     * @param randomnessSeed seed for randomization
     * @param clientConfig ZooKeeper client configuration
     * @throws IllegalArgumentException if dnsName is null or empty or invalid
     */
    DnsSrvHostProvider(final String dnsName, final long randomnessSeed, final ZKClientConfig clientConfig) {
        this(dnsName, randomnessSeed, new DefaultDnsResolver(), clientConfig);
    }

    /**
     * Constructs a DnsSrvHostProvider with the given DNS name, randomization seed, DNS resolver and ZKClientConfig
     *
     * @param dnsName the DNS name to query for SRV records
     * @param randomnessSeed seed for randomization
     * @param dnsResolver custom DNS resolver
     * @param clientConfig ZooKeeper client configuration
     * @throws IllegalArgumentException if dnsName is null or empty or invalid
     */
    public DnsSrvHostProvider(final String dnsName, final long randomnessSeed, final DnsResolver dnsResolver, final ZKClientConfig clientConfig) {
        if (StringUtils.isBlank(dnsName)) {
            throw new IllegalArgumentException("DNS name cannot be null or empty");
        }

        this.dnsName = dnsName;
        this.dnsResolver = dnsResolver;
        this.refreshIntervalMs = validateRefreshInterval();
        this.dnsRefreshTimer = initializeDnsRefreshTimer();

        init(randomnessSeed, clientConfig);
    }

    @Override
    public int size() {
        return connectionManager.size();
    }

    @Override
    public InetSocketAddress next(long spinDelay) {
        applyPendingServerListUpdate();
        return connectionManager.next(spinDelay);
    }

    @Override
    public void onConnected() {
        currentConnectedHost.set(connectionManager.getServerAtCurrentIndex());
        connectionManager.onConnected();
    }

    @Override
    public boolean updateServerList(Collection<InetSocketAddress> serverAddresses, InetSocketAddress currentHost) {
        return connectionManager.updateServerList(serverAddresses, currentHost);
    }

    @Override
    public void close() {
        if (dnsRefreshTimer != null) {
            dnsRefreshTimer.cancel();
        }
    }

    @Override
    public ConnectionType getSupportedConnectionType() {
        return ConnectionType.DNS_SRV;
    }


    /**
     * Validates and returns the refresh interval
     *
     * @return the validated refresh interval in milliseconds
     */
    private long validateRefreshInterval() {
        final long defaultInterval = ZKClientConfig.DNS_SRV_REFRESH_INTERVAL_MS_DEFAULT;

        final long interval = Long.getLong(ZKClientConfig.DNS_SRV_REFRESH_INTERVAL_MS, defaultInterval);
        if (interval < 0) {
            LOG.warn("Invalid DNS SRV refresh interval {}, using default {}", interval, defaultInterval);
            return defaultInterval;
        }
        if (interval == 0) {
            LOG.info("DNS SRV refresh disabled (interval = 0) for {}", dnsName);
        }
        return interval;
    }


    /**
     * Initializes the DNS refresh timer if refresh interval is greater than 0.
     *
     * @return the initialized Timer or null if refresh interval is 0 or negative
     */
    private Timer initializeDnsRefreshTimer() {
        if (refreshIntervalMs > 0) {
            return new Timer("DnsSrvRefresh-" + dnsName, true);
        }
        return null;
    }

    /**
     * Initializes the DNS SRV host provider.
     *
     * @param randomnessSeed seed for randomization
     * @param clientConfig ZooKeeper client configuration
     * @throws IllegalArgumentException if no SRV records are found or initialization fails
     */
    private void init(final long randomnessSeed, final ZKClientConfig clientConfig) {
        try {
            final List<InetSocketAddress> serverAddresses = queryDnsSrvRecords();
            if (serverAddresses.isEmpty()) {
                throw new IllegalArgumentException("No SRV records found for DNS name: " + dnsName);
            }

            this.connectionManager = new HostConnectionManager(serverAddresses, randomnessSeed, clientConfig);
            this.previousServerList = new ArrayList<>(serverAddresses);
            this.latestServerList = new ArrayList<>(serverAddresses);

            setupBackgroundDnsRefreshTimer();

            LOG.info("DnsSrvHostProvider initialized with {} servers from DNS name: {} with refresh interval: {} ms",
                    serverAddresses.size(), dnsName, refreshIntervalMs);
        } catch (final Exception e) {
            LOG.error("Failed to initialize DnsSrvHostProvider for DNS name: {}", dnsName, e);
            throw new IllegalArgumentException("Failed to initialize DnsSrvHostProvider for DNS name: " + dnsName, e);
        }
    }

    /**
     * Queries DNS SRV records and returns a list of server addresses.
     *
     * @return list of server addresses from SRV records, empty list on failure
     */
    private List<InetSocketAddress> queryDnsSrvRecords() {
        final List<InetSocketAddress> addresses = new ArrayList<>();

        try {
            final SRVRecord[] srvRecords = dnsResolver.lookupSrvRecords(dnsName);
            if (srvRecords.length == 0) {
                LOG.warn("No SRV records found for DNS name: {}", dnsName);
                return addresses;
            }

            for (final SRVRecord srvRecord : srvRecords) {
                try {
                    final InetSocketAddress address = createAddressFromSrvRecord(srvRecord);
                    if (address != null) {
                        addresses.add(address);
                    }
                } catch (final Exception e) {
                    LOG.warn("Failed to create address from SRV record {}: {}", srvRecord, e.getMessage());
                }
            }
        } catch (final Exception e) {
            LOG.error("DNS SRV lookup failed for {}", dnsName, e);
        }
        return addresses;
    }

    /**
     * Creates an InetSocketAddress from an SRV record.
     *
     * @param srvRecord the SRV record to process
     * @return InetSocketAddress or null if invalid
     */
    private InetSocketAddress createAddressFromSrvRecord(final SRVRecord srvRecord) {
        if (srvRecord == null) {
            return null;
        }

        try {
            final String target = srvRecord.getTarget().toString(true);
            final int port = srvRecord.getPort();

            if (port <= 0 || port > 65535) {
                LOG.warn("Invalid port {} in SRV record for target {}", port, target);
                return null;
            }

            if (StringUtils.isBlank(target)) {
                LOG.warn("Empty or blank target in SRV record");
                return null;
            }

            return new InetSocketAddress(target, port);
        } catch (final Exception e) {
            LOG.warn("Failed to create InetSocketAddress from SRV record {}: {}", srvRecord, e.getMessage());
            return null;
        }
    }

    /**
     * Sets up the background DNS refresh timer task if needed.
     */
    private void setupBackgroundDnsRefreshTimer() {
        if (dnsRefreshTimer != null) {
            dnsRefreshTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    refreshServerListInBackground();
                }
            },
            refreshIntervalMs,
            refreshIntervalMs);
        }
    }

    /**
     * Performs background DNS refresh to detect server list changes without blocking connection attempts.
     *
     * <p><strong>Important:</strong> This method only detects changes; the actual server list update
     * is deferred until the next connection attempt via {@link #applyPendingServerListUpdate()}.</p>
     */
    private void refreshServerListInBackground() {
        try {
            // Refresh the server list
            final List<InetSocketAddress> newAddresses = queryDnsSrvRecords();
            if (newAddresses.isEmpty()) {
                LOG.warn("DNS SRV lookup returned no records for {}, will retry on next refresh", dnsName);
                return;
            }

            // Check if server list has changed
            if (!Objects.equals(previousServerList, newAddresses)) {
                latestServerList = new ArrayList<>(newAddresses);
                serverListChanged.set(true);
                LOG.info("New server list detected from DNS SRV: {} servers", newAddresses.size());
            }
        } catch (final Exception e) {
            LOG.warn("Failed to refresh server list from DNS SRV records for {}: {}", dnsName, e.getMessage());
        }
    }

    /**
     * Apply pending server list updates when connection is actually needed.
     * It is called from next() to ensure server list changes are applied with proper connection context.
     */
    private synchronized void applyPendingServerListUpdate() {
        if (serverListChanged.get() && latestServerList != null) {
            try {
                final boolean needReconnect = connectionManager.updateServerList(latestServerList, currentConnectedHost.get());

                previousServerList = new ArrayList<>(latestServerList);
                serverListChanged.set(false);

                LOG.info("Applied server list update during connection attempt. servers size: {}, need reconnection: {}",
                        latestServerList.size(), needReconnect);
            } catch (final Exception e) {
                LOG.error("Failed to apply server list update", e);
            }
        }
    }

    /**
     * Default implementation of DnsResolver that uses the real DNS system.
     */
    private static class DefaultDnsResolver implements DnsResolver {
        @Override
        public SRVRecord[] lookupSrvRecords(final String name) throws IOException {
            final Lookup lookup = new Lookup(name, Type.SRV);
            final Record[] records = lookup.run();

            if (lookup.getResult() != Lookup.SUCCESSFUL) {
                final String errorMsg = lookup.getErrorString();
                throw new IOException("DNS SRV lookup failed for " + name + ": " + errorMsg);
            }

            if (records == null) {
                return new SRVRecord[0];
            }
            return Arrays.stream(records).map(SRVRecord.class::cast).toArray(SRVRecord[]::new);
        }
    }
}

