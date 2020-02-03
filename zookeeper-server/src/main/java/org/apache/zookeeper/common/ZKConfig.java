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
public class ZKConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ZKConfig.class);

    public static final String JUTE_MAXBUFFER = "jute.maxbuffer";

    /**
     * Path to a kinit binary: {@value}. Defaults to
     * <code>"/usr/bin/kinit"</code>
     */
    public static final String KINIT_COMMAND = "zookeeper.kinit";
    public static final String JGSS_NATIVE = "sun.security.jgss.native";

    private final Map<String, String> properties = new HashMap<String, String>();

    /**
     * properties, which are common to both client and server, are initialized
     * from system properties
     */
    public ZKConfig() {
        init();
    }

    /**
     * @param configPath
     *            Configuration file path
     * @throws ConfigException
     *             if failed to load configuration properties
     */

    public ZKConfig(String configPath) throws ConfigException {
        this(new File(configPath));
    }

    /**
     *
     * @param configFile
     *            Configuration file
     * @throws ConfigException
     *             if failed to load configuration properties
     */
    public ZKConfig(File configFile) throws ConfigException {
        this();
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
        properties.put(JUTE_MAXBUFFER, System.getProperty(JUTE_MAXBUFFER));
        properties.put(KINIT_COMMAND, System.getProperty(KINIT_COMMAND));
        properties.put(JGSS_NATIVE, System.getProperty(JGSS_NATIVE));

        try (ClientX509Util clientX509Util = new ClientX509Util()) {
            putSSLProperties(clientX509Util);
            properties.put(clientX509Util.getSslAuthProviderProperty(), System.getProperty(clientX509Util.getSslAuthProviderProperty()));
        }

        try (X509Util x509Util = new QuorumX509Util()) {
            putSSLProperties(x509Util);
        }
    }

    private void putSSLProperties(X509Util x509Util) {
        properties.put(x509Util.getSslProtocolProperty(), System.getProperty(x509Util.getSslProtocolProperty()));
        properties.put(x509Util.getSslEnabledProtocolsProperty(), System.getProperty(x509Util.getSslEnabledProtocolsProperty()));
        properties.put(x509Util.getSslCipherSuitesProperty(), System.getProperty(x509Util.getSslCipherSuitesProperty()));
        properties.put(x509Util.getSslKeystoreLocationProperty(), System.getProperty(x509Util.getSslKeystoreLocationProperty()));
        properties.put(x509Util.getSslKeystorePasswdProperty(), System.getProperty(x509Util.getSslKeystorePasswdProperty()));
        properties.put(x509Util.getSslKeystoreTypeProperty(), System.getProperty(x509Util.getSslKeystoreTypeProperty()));
        properties.put(x509Util.getSslTruststoreLocationProperty(), System.getProperty(x509Util.getSslTruststoreLocationProperty()));
        properties.put(x509Util.getSslTruststorePasswdProperty(), System.getProperty(x509Util.getSslTruststorePasswdProperty()));
        properties.put(x509Util.getSslTruststoreTypeProperty(), System.getProperty(x509Util.getSslTruststoreTypeProperty()));
        properties.put(x509Util.getSslContextSupplierClassProperty(), System.getProperty(x509Util.getSslContextSupplierClassProperty()));
        properties.put(x509Util.getSslHostnameVerificationEnabledProperty(), System.getProperty(x509Util.getSslHostnameVerificationEnabledProperty()));
        properties.put(x509Util.getSslCrlEnabledProperty(), System.getProperty(x509Util.getSslCrlEnabledProperty()));
        properties.put(x509Util.getSslOcspEnabledProperty(), System.getProperty(x509Util.getSslOcspEnabledProperty()));
        properties.put(x509Util.getSslClientAuthProperty(), System.getProperty(x509Util.getSslClientAuthProperty()));
        properties.put(x509Util.getSslHandshakeDetectionTimeoutMillisProperty(), System.getProperty(x509Util.getSslHandshakeDetectionTimeoutMillisProperty()));
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
        if (null != oldValue && !oldValue.equals(value)) {
            LOG.debug("key {}'s value {} is replaced with new value {}", key, oldValue, value);
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
            configFile = (new VerifyingFileFactory.Builder(LOG).warnForRelativePath()
                                                               .failForNonExistingPath()
                                                               .build()).validate(configFile);
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
        return getBoolean(key, false);
    }

    /**
     * Get the value of the <code>key</code> property as a <code>boolean</code>. Returns
     * {@code true} if and only if the property named by the argument exists and is equal
     * to the string {@code "true"}. If the property is not set, the provided
     * <code>defaultValue</code> is returned.
     *
     * @param key
     *            property key.
     * @param defaultValue
     *            default value.
     * @return return property value as an <code>boolean</code>, or
     *         <code>defaultValue</code>
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String propertyValue = getProperty(key);
        if (propertyValue == null) {
            return defaultValue;
        } else {
            return Boolean.parseBoolean(propertyValue.trim());
        }
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
            return Integer.decode(value.trim());
        }
        return defaultValue;
    }

}
