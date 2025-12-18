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

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.common.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating appropriate HostProvider instances based on connection string format.
 * This factory enables zero-code-change migration by automatically detecting the connection
 * string format and creating the appropriate HostProvider implementation.
 *
 * Supported formats:
 * - Host:Port: "host1:port1,host2:port2,host3:port3" (StaticHostProvider)
 * - DNS SRV: "dns-srv://service.domain.com"  (DnsSrvHostProvider)
 * - Future formats can be easily added by extending the factory
 */
@InterfaceAudience.Public
public class HostProviderFactory {

    private static final Logger LOG = LoggerFactory.getLogger(HostProviderFactory.class);

    /**
     * Creates a HostProvider based on the connection string format.
     *
     * @param connectString the connection string (host:port or DNS SRV format)
     * @param clientConfig ZooKeeper client configuration
     *
     * @return appropriate HostProvider implementation
     * @throws IllegalArgumentException if the connection string format is not supported
     */
    public static HostProvider createHostProvider(final String connectString, final ZKClientConfig clientConfig) {
        if (StringUtils.isBlank(connectString)) {
            throw new IllegalArgumentException("Connection string cannot be null or empty");
        }

        final String trimmedConnectString = connectString.trim();
        final ConnectionType connectionType = ConnectStringParser.getConnectionType(trimmedConnectString);
        if (connectionType == ConnectionType.DNS_SRV) {
            LOG.info("Detected DNS SRV connection string format: {}", trimmedConnectString);
            return createDnsSrvHostProvider(trimmedConnectString, clientConfig);
        }
        final ConnectStringParser parser = new ConnectStringParser(trimmedConnectString);
        return new StaticHostProvider(parser.getServerAddresses(), clientConfig);
    }

    private static DnsSrvHostProvider createDnsSrvHostProvider(final String connectString, final ZKClientConfig clientConfig) {
        final ConnectStringParser parser = new ConnectStringParser(connectString);

        if (parser.getServerAddresses().isEmpty()) {
            throw new IllegalArgumentException("No DNS service name found in connect string: " + connectString);
        }

        final String dnsServiceName = parser.getServerAddresses().get(0).getHostString();
        return new DnsSrvHostProvider(dnsServiceName, clientConfig);
    }
}
