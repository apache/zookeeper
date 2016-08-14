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

package org.apache.zookeeper.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.zookeeper.Environment;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.server.util.VerifyingFileFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is a base class for the configurations of both client and server.
 * It supports reading client configuration from both system properties and
 * configuration file. A user can override any system property by calling
 * {@link #setProperty(String, String)}.
 * @since 3.5.2
 */
public abstract class ZKConfig {
    private static final Logger LOG = LoggerFactory.getLogger(ZKConfig.class);

    public static final String SSL_VERSION_DEFAULT = "TLSv1";
    public static final String SSL_VERSION = "ssl.version";
    public static final String SSL_KEYSTORE_LOCATION = "ssl.keyStore.location";
    public static final String SSL_KEYSTORE_PASSWD = "ssl.keyStore.password";
    public static final String SSL_TRUSTSTORE_LOCATION =
            "ssl.trustStore.location";
    public static final String SSL_TRUSTSTORE_PASSWD =
            "ssl.trustStore.password";
    public static final String SSL_AUTHPROVIDER = "ssl.authProvider";

    protected final Map<String, String> properties = new HashMap<String, String>();
    private final SslConfig sslConfig;

    /**
     * properties, which are common to both client and server, are initialized
     * from system properties
     */
    public ZKConfig(final SslConfig sslConfig) {
        this.sslConfig = sslConfig;
        init();
    }

    /**
     * @param configPath
     *            Configuration file path
     * @throws ConfigException
     *             if failed to load configuration properties
     */

    public ZKConfig(final String configPath, final SslConfig sslConfig)
            throws ConfigException {
        this(new File(configPath), sslConfig);
    }

    /**
     *
     * @param configFile
     *            Configuration file
     * @throws ConfigException
     *             if failed to load configuration properties
     */
    public ZKConfig(final File configFile, final SslConfig sslConfig)
            throws ConfigException {
        this(sslConfig);
        addConfiguration(configFile);
    }

    private void init() {
        /**
         * backward compatibility for all currently available client properties
         */
        handleBackwardCompatibility();
    }

    /**
     * Now onwards client code will use properties from this class but older
     * clients still be setting properties through system properties. So to make
     * this change backward compatible we should set old system properties in
     * this configuration.
     */
    protected void handleBackwardCompatibility() {
        properties.put(SSL_VERSION, systemPropDefault(
                getSslConfig().getSslVersion(),
                getSslConfig().getSslVersionDefault()));
        LOG.info("registering: " + getSslConfig().getSslKeyStoreLocation());
        properties.put(SSL_KEYSTORE_LOCATION,
                System.getProperty(getSslConfig().getSslKeyStoreLocation()));
        properties.put(SSL_KEYSTORE_PASSWD,
                System.getProperty(getSslConfig().getSslKeyStorePassword()));
        properties.put(SSL_TRUSTSTORE_LOCATION,
                System.getProperty(getSslConfig().getSslTrustStoreLocation()));
        properties.put(SSL_TRUSTSTORE_PASSWD,
                System.getProperty(getSslConfig().getSslTrustStorePassword()));
        properties.put(SSL_AUTHPROVIDER,
                System.getProperty(getSslConfig().getSslAuthProvider()));
    }

    private SslConfig getSslConfig() {
        return sslConfig;
    }

    protected static String systemPropDefault(final String key,
                                              final String defaultValue) {
        return System.getProperty(key) != null ? System.getProperty(key) :
                defaultValue;
    }

    /**
     * Get the property value
     *
     * @param key
     * @return property value
     */
    public String getProperty(String key) {
        return properties.get(key);
    }

    /**
     * Get the property value, if it is null return default value
     *
     * @param key
     *            property key
     * @param defaultValue
     * @return property value or default value
     */
    public String getProperty(String key, String defaultValue) {
        String value = properties.get(key);
        return (value == null) ? defaultValue : value;
    }

    /**
     * Return the value of "java.security.auth.login.config" system property
     *
     * @return value
     */
    public String getJaasConfKey() {
        return System.getProperty(Environment.JAAS_CONF_KEY);
    }

    /**
     * Maps the specified <code>key</code> to the specified <code>value</code>.
     * key can not be <code>null</code>. If key is already mapped then the old
     * value of the <code>key</code> is replaced by the specified
     * <code>value</code>.
     * 
     * @param key
     * @param value
     */
    public void setProperty(String key, String value) {
        if (null == key) {
            throw new IllegalArgumentException("property key is null.");
        }
        String oldValue = properties.put(key, value);
        if (LOG.isDebugEnabled()) {
            if (null != oldValue && !oldValue.equals(value)) {
                LOG.debug("key {}'s value {} is replaced with new value {}", key, oldValue, value);
            }
        }
    }

    /**
     * Add a configuration resource. The properties form this configuration will
     * overwrite corresponding already loaded property and system property
     *
     * @param configFile
     *            Configuration file.
     */
    public void addConfiguration(File configFile) throws ConfigException {
        LOG.info("Reading configuration from: {}", configFile.getAbsolutePath());
        try {
            configFile = (new VerifyingFileFactory.Builder(LOG).warnForRelativePath().failForNonExistingPath().build())
                    .validate(configFile);
            Properties cfg = new Properties();
            FileInputStream in = new FileInputStream(configFile);
            try {
                cfg.load(in);
            } finally {
                in.close();
            }
            parseProperties(cfg);
        } catch (IOException | IllegalArgumentException e) {
            LOG.error("Error while configuration from: {}", configFile.getAbsolutePath(), e);
            throw new ConfigException("Error while processing " + configFile.getAbsolutePath(), e);
        }
    }

    /**
     * Add a configuration resource. The properties form this configuration will
     * overwrite corresponding already loaded property and system property
     *
     * @param configPath
     *            Configuration file path.
     */
    public void addConfiguration(String configPath) throws ConfigException {
        addConfiguration(new File(configPath));
    }

    private void parseProperties(Properties cfg) {
        for (Entry<Object, Object> entry : cfg.entrySet()) {
            String key = entry.getKey().toString().trim();
            String value = entry.getValue().toString().trim();
            setProperty(key, value);
        }
    }

    /**
     * Returns {@code true} if and only if the property named by the argument
     * exists and is equal to the string {@code "true"}.
     */
    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(getProperty(key));
    }

    /**
     * Get the value of the <code>key</code> property as an <code>int</code>. If
     * property is not set, the provided <code>defaultValue</code> is returned
     * 
     * @param key
     *            property key.
     * @param defaultValue
     *            default value.
     * @throws NumberFormatException
     *             when the value is invalid
     * @return return property value as an <code>int</code>, or
     *         <code>defaultValue</code>
     */
    public int getInt(String key, int defaultValue) {
        String value = getProperty(key);
        if (value != null) {
            return Integer.parseInt(value.trim());
        }
        return defaultValue;
    }

}
