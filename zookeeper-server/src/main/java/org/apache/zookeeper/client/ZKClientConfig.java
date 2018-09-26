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

package org.apache.zookeeper.client;

import java.io.File;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;

/**
 * Handles client specific properties
 * @since 3.5.2
 */
@InterfaceAudience.Public
public class ZKClientConfig extends ZKConfig {
    public static final String ZK_SASL_CLIENT_USERNAME = "zookeeper.sasl.client.username";
    public static final String ZK_SASL_CLIENT_USERNAME_DEFAULT = "zookeeper";
    public static final String ZK_SASL_CLIENT_CANONICALIZE_HOSTNAME =
        "zookeeper.sasl.client.canonicalize.hostname";
    public static final String ZK_SASL_CLIENT_CANONICALIZE_HOSTNAME_DEFAULT = "true";
    @SuppressWarnings("deprecation")
    public static final String LOGIN_CONTEXT_NAME_KEY = ZooKeeperSaslClient.LOGIN_CONTEXT_NAME_KEY;;
    public static final String LOGIN_CONTEXT_NAME_KEY_DEFAULT = "Client";
    @SuppressWarnings("deprecation")
    public static final String ENABLE_CLIENT_SASL_KEY = ZooKeeperSaslClient.ENABLE_CLIENT_SASL_KEY;
    @SuppressWarnings("deprecation")
    public static final String ENABLE_CLIENT_SASL_DEFAULT = ZooKeeperSaslClient.ENABLE_CLIENT_SASL_DEFAULT;
    public static final String ZOOKEEPER_SERVER_REALM = "zookeeper.server.realm";
    /**
     * This controls whether automatic watch resetting is enabled. Clients
     * automatically reset watches during session reconnect, this option allows
     * the client to turn off this behavior by setting the property
     * "zookeeper.disableAutoWatchReset" to "true"
     */
    public static final String DISABLE_AUTO_WATCH_RESET = "zookeeper.disableAutoWatchReset";
    @SuppressWarnings("deprecation")
    public static final String ZOOKEEPER_CLIENT_CNXN_SOCKET = ZooKeeper.ZOOKEEPER_CLIENT_CNXN_SOCKET;
    /**
     * Setting this to "true" will enable encrypted client-server communication.
     */
    @SuppressWarnings("deprecation")
    public static final String SECURE_CLIENT = ZooKeeper.SECURE_CLIENT;
    public static final int CLIENT_MAX_PACKET_LENGTH_DEFAULT = 4096 * 1024; /* 4 MB */
    public static final String ZOOKEEPER_REQUEST_TIMEOUT = "zookeeper.request.timeout";
    /**
     * Feature is disabled by default.
     */
    public static final long ZOOKEEPER_REQUEST_TIMEOUT_DEFAULT = 0;

    public ZKClientConfig() {
        super();
        initFromJavaSystemProperties();
    }

    public ZKClientConfig(File configFile) throws ConfigException {
        super(configFile);
    }

    public ZKClientConfig(String configPath) throws ConfigException {
        super(configPath);
    }

    /**
     * Initialize all the ZooKeeper client properties which are configurable as
     * java system property
     */
    private void initFromJavaSystemProperties() {
        setProperty(ZOOKEEPER_REQUEST_TIMEOUT,
                System.getProperty(ZOOKEEPER_REQUEST_TIMEOUT));
    }

    @Override
    protected void handleBackwardCompatibility() {
        /**
         * backward compatibility for properties which are common to both client
         * and server
         */
        super.handleBackwardCompatibility();

        /**
         * backward compatibility for client specific properties
         */
        setProperty(ZK_SASL_CLIENT_USERNAME, System.getProperty(ZK_SASL_CLIENT_USERNAME));
        setProperty(LOGIN_CONTEXT_NAME_KEY, System.getProperty(LOGIN_CONTEXT_NAME_KEY));
        setProperty(ENABLE_CLIENT_SASL_KEY, System.getProperty(ENABLE_CLIENT_SASL_KEY));
        setProperty(ZOOKEEPER_SERVER_REALM, System.getProperty(ZOOKEEPER_SERVER_REALM));
        setProperty(DISABLE_AUTO_WATCH_RESET, System.getProperty(DISABLE_AUTO_WATCH_RESET));
        setProperty(ZOOKEEPER_CLIENT_CNXN_SOCKET, System.getProperty(ZOOKEEPER_CLIENT_CNXN_SOCKET));
        setProperty(SECURE_CLIENT, System.getProperty(SECURE_CLIENT));
    }

    /**
     * Returns true if the SASL client is enabled. By default, the client is
     * enabled but can be disabled by setting the system property
     * <code>zookeeper.sasl.client</code> to <code>false</code>. See
     * ZOOKEEPER-1657 for more information.
     *
     * @return true if the SASL client is enabled.
     */
    public boolean isSaslClientEnabled() {
        return Boolean.valueOf(getProperty(ENABLE_CLIENT_SASL_KEY, ENABLE_CLIENT_SASL_DEFAULT));
    }

    /**
     * Get the value of the <code>key</code> property as an <code>long</code>.
     * If property is not set, the provided <code>defaultValue</code> is
     * returned
     *
     * @param key
     *            property key.
     * @param defaultValue
     *            default value.
     * @throws NumberFormatException
     *             when the value is invalid
     * @return return property value as an <code>long</code>, or
     *         <code>defaultValue</code>
     */
    public long getLong(String key, long defaultValue) {
        String value = getProperty(key);
        if (value != null) {
            return Long.parseLong(value.trim());
        }
        return defaultValue;
    }
}
