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

/**
 * Factory for creating appropriate HostProvider instances based on connect string format.
 * This factory enables zero-code-change migration by automatically detecting the connect
 * string format and creating the appropriate HostProvider implementation.
 *
 * Supported formats:
 * - Host:Port: "host1:port1,host2:port2,host3:port3" (StaticHostProvider)
 * - DNS SRV: "dns-srv://service.domain.com"  (DnsSrvHostProvider)
 *
 * - Future formats can be easily added by extending the factory
 */
@InterfaceAudience.Public
public class HostProviderFactory {
    /**
     * Creates a HostProvider based on the connect string format.
     * This is a convenience method for zero-code-change migration.
     *
     * @param connectString the connect string
     * @return appropriate HostProvider
     */
    public static HostProvider create(final String connectString) {
        final ConnectStringParser connectStringParser = new ConnectStringParser(connectString);
        return create(connectStringParser, null);
    }

    /**
     * Creates a HostProvider based on the connect string format.
     *
     * @param connectStringParser the connect string parser
     * @param clientConfig ZooKeeper client configuration
     *
     * @return appropriate HostProvider
     */
    @InterfaceAudience.Private
    public static HostProvider create(final ConnectStringParser connectStringParser, final ZKClientConfig clientConfig) {
        switch (connectStringParser.getConnectionType()) {
            case DNS_SRV:
                final String dnsSrvName = connectStringParser.getServerAddresses().get(0).getHostString();
                return new DnsSrvHostProvider(dnsSrvName, clientConfig);
            default:
                return new StaticHostProvider(connectStringParser.getServerAddresses(), clientConfig);
        }
    }
}
