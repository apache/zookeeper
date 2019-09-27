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
package org.apache.zookeeper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.apache.zookeeper.client.ZKClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes the Server Principal for a SASL client.
 */
public class SaslServerPrincipal {
    private static final Logger LOG = LoggerFactory.getLogger(SaslServerPrincipal.class);

    /**
     * Get the name of the server principal for a SASL client.
     * @param addr the address of the host.
     * @param clientConfig the configuration for the client.
     * @return the name of the principal.
     */
    static String getServerPrincipal(InetSocketAddress addr, ZKClientConfig clientConfig) {
        return getServerPrincipal(new WrapperInetSocketAddress(addr), clientConfig);
    }

    /**
     * Get the name of the server principal for a SASL client.  This is visible for testing purposes.
     * @param addr the address of the host.
     * @param clientConfig the configuration for the client.
     * @return the name of the principal.
     */
    static String getServerPrincipal(WrapperInetSocketAddress addr, ZKClientConfig clientConfig) {
        String configuredServerPrincipal = clientConfig.getProperty(ZKClientConfig.ZOOKEEPER_SERVER_PRINCIPAL);
        if (configuredServerPrincipal != null) {
            // If server principal is already configured then return it
            return configuredServerPrincipal;
        }
        String principalUserName = clientConfig.getProperty(ZKClientConfig.ZK_SASL_CLIENT_USERNAME,
            ZKClientConfig.ZK_SASL_CLIENT_USERNAME_DEFAULT);
        String hostName = addr.getHostName();

        boolean canonicalize = true;
        String canonicalizeText = clientConfig.getProperty(ZKClientConfig.ZK_SASL_CLIENT_CANONICALIZE_HOSTNAME,
            ZKClientConfig.ZK_SASL_CLIENT_CANONICALIZE_HOSTNAME_DEFAULT);
        try {
            canonicalize = Boolean.parseBoolean(canonicalizeText);
        } catch (IllegalArgumentException ea) {
            LOG.warn("Could not parse config {} \"{}\" into a boolean using default {}", ZKClientConfig
                .ZK_SASL_CLIENT_CANONICALIZE_HOSTNAME, canonicalizeText, canonicalize);
        }

        if (canonicalize) {
            WrapperInetAddress ia = addr.getAddress();
            if (ia == null) {
                throw new IllegalArgumentException("Unable to canonicalize address " + addr + " because it's not resolvable");
            }

            String canonicalHostName = ia.getCanonicalHostName();
            //avoid using literal IP address when security check fails
            if (!canonicalHostName.equals(ia.getHostAddress())) {
                hostName = canonicalHostName;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Canonicalized address to {}", hostName);
            }
        }
        String serverPrincipal = principalUserName + "/" + hostName;
        return serverPrincipal;
    }

    /**
     * This is here to provide a way to unit test the core logic as the methods for
     * InetSocketAddress are marked as final.
     */
    static class WrapperInetSocketAddress {
        private final InetSocketAddress addr;

        WrapperInetSocketAddress(InetSocketAddress addr) {
            this.addr = addr;
        }

        public String getHostName() {
            return addr.getHostName();
        }

        public WrapperInetAddress getAddress() {
            InetAddress ia = addr.getAddress();
            return ia == null ? null : new WrapperInetAddress(ia);
        }

        @Override
        public String toString() {
            return addr.toString();
        }
    }

    /**
     * This is here to provide a way to unit test the core logic as the methods for
     * InetAddress are marked as final.
     */
    static class WrapperInetAddress {
        private final InetAddress ia;

        WrapperInetAddress(InetAddress ia) {
            this.ia = ia;
        }

        public String getCanonicalHostName() {
            return ia.getCanonicalHostName();
        }

        public String getHostAddress() {
            return ia.getHostAddress();
        }

        @Override
        public String toString() {
            return ia.toString();
        }
    }
}
