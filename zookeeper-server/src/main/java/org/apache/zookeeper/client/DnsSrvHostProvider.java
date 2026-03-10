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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 * <p><strong>Two-Phase Update Strategy:</strong></p>
 * <ul>
 * <li><strong>Phase 1 (Background):</strong> Timer thread detects DNS changes without caching results</li>
 * <li><strong>Phase 2 (Connection-time):</strong> Fresh DNS lookup during connect attempt if changes detected</li>
 * </ul>
 */
@InterfaceAudience.Public
public final class DnsSrvHostProvider implements HostProvider {
    private static final Logger LOG = LoggerFactory.getLogger(DnsSrvHostProvider.class);

    public interface DnsSrvResolver {
        SRVRecord[] lookupSrvRecords(String dnsSrvName) throws IOException;
    }

    private final String dnsSrvName;
    private final DnsSrvResolver dnsResolver;

    private final HostConnectionManager connectionManager;
    // Track the previous server list to detect changes
    private volatile Set<InetSocketAddress> previousServerSet;
    private Timer dnsRefreshTimer;

    // Track the current connected host for accurate load balancing decisions
    private final AtomicReference<InetSocketAddress> currentConnectedHost = new AtomicReference<>();
    private final AtomicBoolean serverListChanged = new AtomicBoolean(false);

    /**
     * Constructs a DnsSrvHostProvider with the given DNS name
     *
     * @param dnsSrvName the DNS name to query for SRV records
     * @throws IllegalArgumentException if dnsSrvName is null or empty or invalid
     *                                  or if no SRV records are found for the DNS name
     *                                  or if DNS lookup fails
     */
    public DnsSrvHostProvider(final String dnsSrvName) {
        this(dnsSrvName, null);
    }


    /**
     * Constructs a DnsSrvHostProvider with the given DNS name and ZKClientConfig
     *
     * @param dnsSrvName the DNS name to query for SRV records
     * @param clientConfig ZooKeeper client configuration
     * @throws IllegalArgumentException if dnsSrvName is null or empty or invalid
     *                                  or if no SRV records are found for the DNS name
     *                                  or if DNS lookup fails
     */
    public DnsSrvHostProvider(final String dnsSrvName, final ZKClientConfig clientConfig) {
        this(dnsSrvName, System.currentTimeMillis() ^ dnsSrvName.hashCode(), clientConfig);
    }

    /**
     * Constructs a DnsSrvHostProvider with the given DNS name, randomness seed and ZKClientConfig
     *
     * @param dnsSrvName the DNS name to query for SRV records
     * @param randomnessSeed seed for randomization
     * @param clientConfig ZooKeeper client configuration
     * @throws IllegalArgumentException if dnsSrvName is null or empty or invalid
     *                                  or if no SRV records are found for the DNS name
     *                                  or if DNS lookup fails
     */
    public DnsSrvHostProvider(final String dnsSrvName, final long randomnessSeed, final ZKClientConfig clientConfig) {
        this(dnsSrvName, randomnessSeed, new DefaultDnsResolver(), clientConfig);
    }

    /**
     * Constructs a DnsSrvHostProvider with the given DNS name, randomization seed, DNS resolver and ZKClientConfig
     *
     * @param dnsSrvName the DNS name to query for SRV records
     * @param randomnessSeed seed for randomization
     * @param dnsResolver custom DNS resolver
     * @param clientConfig ZooKeeper client configuration
     * @throws IllegalArgumentException if dnsSrvName is null or empty or invalid
     *                                  or if no SRV records are found for the DNS name
     *                                  or if DNS lookup fails
     */
    public DnsSrvHostProvider(final String dnsSrvName, final long randomnessSeed, final DnsSrvResolver dnsResolver, final ZKClientConfig clientConfig) {
        if (StringUtils.isBlank(dnsSrvName)) {
            throw new IllegalArgumentException("DNS name cannot be null or empty");
        }

        this.dnsSrvName = dnsSrvName;
        this.dnsResolver = dnsResolver;
        try {
            final List<InetSocketAddress> serverAddresses = lookupDnsSrvRecords();
            if (serverAddresses.isEmpty()) {
                LOG.error("No SRV records found for DNS name: {}", dnsSrvName);
                throw new IllegalArgumentException("No SRV records found for DNS name: " + dnsSrvName);
            }

            this.connectionManager = new HostConnectionManager(serverAddresses, randomnessSeed, clientConfig);
            this.previousServerSet = new HashSet<>(serverAddresses);


            final long refreshIntervalInSeconds = getRefreshInterval();
            if (refreshIntervalInSeconds > 0) {
                dnsRefreshTimer = new Timer("DnsSrvRefresh-" + dnsSrvName, true);
                dnsRefreshTimer.scheduleAtFixedRate(new TimerTask() {
                                                        @Override
                                                        public void run() {
                                                            refreshServerListInBackground();
                                                        }
                                                    },
                        refreshIntervalInSeconds * 1000,
                        refreshIntervalInSeconds * 1000);
            }
            LOG.info("DnsSrvHostProvider initialized with {} servers from DNS name: {} with refresh interval: {} seconds",
                    serverAddresses.size(), dnsSrvName, refreshIntervalInSeconds);
        } catch (final Exception e) {
            LOG.error("Failed to initialize DnsSrvHostProvider for DNS name: {}", dnsSrvName, e);

            if (dnsRefreshTimer != null) {
                dnsRefreshTimer.cancel();
            }

            if (e instanceof IllegalArgumentException) {
                throw e;
            } else {
                throw new IllegalArgumentException("Failed to initialize DnsSrvHostProvider for DNS name: " + dnsSrvName, e);
            }
        }
    }

    @Override
    public int size() {
        return connectionManager.size();
    }

    @Override
    public InetSocketAddress next(long spinDelay) {
        applyServerListUpdate();
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

    private long getRefreshInterval() {
        final long defaultInterval = ZKClientConfig.DNS_SRV_REFRESH_INTERVAL_SECONDS_DEFAULT;
        final long interval = Long.getLong(ZKClientConfig.DNS_SRV_REFRESH_INTERVAL_SECONDS, defaultInterval);
        if (interval < 0) {
            LOG.error("Invalid DNS SRV refresh interval {} seconds", interval);
            throw new IllegalArgumentException("Invalid DNS SRV refresh interval: " + interval);
        }
        if (interval == 0) {
            LOG.info("DNS SRV refresh disabled (interval = 0) for {}", dnsSrvName);
        }
        return interval;
    }

    private List<InetSocketAddress> lookupDnsSrvRecords() {
        final List<InetSocketAddress> addresses = new ArrayList<>();

        try {
            final SRVRecord[] srvRecords = dnsResolver.lookupSrvRecords(dnsSrvName);
            for (final SRVRecord srvRecord : srvRecords) {
                final InetSocketAddress address = createAddressFromSrvRecord(srvRecord);
                if (address != null) {
                    addresses.add(address);
                }
            }
        } catch (final Exception e) {
            LOG.error("DNS SRV lookup failed for {}", dnsSrvName, e);
            throw new RuntimeException("DNS SRV lookup failed for " + dnsSrvName, e);
        }
        return addresses;
    }

    private InetSocketAddress createAddressFromSrvRecord(final SRVRecord srvRecord) {
        if (srvRecord == null) {
            LOG.error("Null SRV record encountered from DnsSrvResolver implementation");
            return null;
        }

        try {
            final String target = srvRecord.getTarget().toString(true);
            final int port = srvRecord.getPort();

            if (port <= 0 || port > 65535) {
                LOG.error("Invalid port {} in SRV record for target {}", port, target);
                return null;
            }

            if (StringUtils.isBlank(target)) {
                LOG.error("Empty or blank target in SRV record {}", srvRecord);
                return null;
            }

            return new InetSocketAddress(target, port);
        } catch (final Exception e) {
            LOG.error("Failed to create InetSocketAddress from SRV record {}", srvRecord, e);
            return null;
        }
    }

    /**
     * Performs background DNS refresh to detect server list changes without caching DNS responses.
     *
     * <p><strong>Important:</strong> This method only detects changes, the actual fresh DNS query happens
     * in {@link #applyServerListUpdate()} when needed.</p>
     */
    private void refreshServerListInBackground() {
        try {
            // Refresh the server list
            final List<InetSocketAddress> newAddresses = lookupDnsSrvRecords();
            if (newAddresses.isEmpty()) {
                LOG.warn("DNS SRV lookup returned no records for {}, will retry on next refresh", dnsSrvName);
                return;
            }

            // Check if server list has changed
            final Set<InetSocketAddress> newServerSet = new HashSet<>(newAddresses);
            if (!Objects.equals(previousServerSet, newServerSet)) {
                serverListChanged.set(true);
                LOG.info("Server list change detected from DNS SRV: {} servers", newAddresses.size());
            }
        } catch (final Exception e) {
            LOG.warn("Failed to refresh server list from DNS SRV records for {}: {}", dnsSrvName, e.getMessage());
        }
    }

    /**
     * Apply pending server list updates when connection is actually needed.
     * It is called from next() to ensure server list changes are applied with proper connection context.
     */
    private synchronized void applyServerListUpdate() {
        if (serverListChanged.get()) {
            try {
                // Query DNS to get the updated server list
                final List<InetSocketAddress> latestServerList = lookupDnsSrvRecords();
                if (latestServerList.isEmpty()) {
                    LOG.warn("DNS SRV lookup returned no records for {}, will use the existing ones", dnsSrvName);
                    return;
                }

                final boolean needReconnect = connectionManager.updateServerList(latestServerList, currentConnectedHost.get());
                previousServerSet = new HashSet<>(latestServerList);
                serverListChanged.set(false);

                LOG.info("Applied server list update during connection attempt. servers size: {}, need reconnection: {}",
                        latestServerList.size(), needReconnect);
            } catch (final Exception e) {
                LOG.warn("Failed to apply server list update", e);
            }
        }
    }

    private static class DefaultDnsResolver implements DnsSrvResolver {
        @Override
        public SRVRecord[] lookupSrvRecords(final String dnsSrvName) throws IOException {
            final Lookup lookup = new Lookup(dnsSrvName, Type.SRV);
            final Record[] records = lookup.run();
            if (lookup.getResult() != Lookup.SUCCESSFUL) {
                final String errorMsg = lookup.getErrorString();
                throw new IOException("DNS SRV lookup failed for " + dnsSrvName + ": " + errorMsg);
            }

            if (records == null) {
                return new SRVRecord[0];
            }
            return Arrays.stream(records).map(SRVRecord.class::cast).toArray(SRVRecord[]::new);
        }
    }
}

