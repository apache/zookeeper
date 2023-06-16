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

import io.netty.handler.ssl.DelegatingSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.util.Arrays;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * X509 utilities specific for client-server communication framework.
 */
public class ClientX509Util extends X509Util {

    private static final Logger LOG = LoggerFactory.getLogger(ClientX509Util.class);

    private final String sslAuthProviderProperty = getConfigPrefix() + "authProvider";
    private final String sslProviderProperty = getConfigPrefix() + "sslProvider";

    @Override
    protected String getConfigPrefix() {
        return "zookeeper.ssl.";
    }

    @Override
    protected boolean shouldVerifyClientHostname() {
        return false;
    }

    public String getSslAuthProviderProperty() {
        return sslAuthProviderProperty;
    }

    @Override
    public String getSslProviderProperty() {
        return sslProviderProperty;
    }

    public SslContext createNettySslContextForClient(ZKConfig config)
        throws X509Exception.KeyManagerException, X509Exception.TrustManagerException, SSLException {
        String keyStoreLocation = config.getProperty(getSslKeystoreLocationProperty(), "");
        String keyStorePassword = getPasswordFromConfigPropertyOrFile(config, getSslKeystorePasswdProperty(),
            getSslKeystorePasswdPathProperty());
        String keyStoreType = config.getProperty(getSslKeystoreTypeProperty());

        SslContextBuilder sslContextBuilder = SslContextBuilder.forClient();

        if (keyStoreLocation.isEmpty()) {
            LOG.warn("{} not specified", getSslKeystoreLocationProperty());
        } else {
            sslContextBuilder.keyManager(createKeyManager(keyStoreLocation, keyStorePassword, keyStoreType));
        }

        String trustStoreLocation = config.getProperty(getSslTruststoreLocationProperty(), "");
        String trustStorePassword = getPasswordFromConfigPropertyOrFile(config, getSslTruststorePasswdProperty(),
            getSslTruststorePasswdPathProperty());
        String trustStoreType = config.getProperty(getSslTruststoreTypeProperty());

        boolean sslCrlEnabled = config.getBoolean(getSslCrlEnabledProperty());
        boolean sslOcspEnabled = config.getBoolean(getSslOcspEnabledProperty());
        boolean sslServerHostnameVerificationEnabled = isServerHostnameVerificationEnabled(config);
        boolean sslClientHostnameVerificationEnabled = isClientHostnameVerificationEnabled(config);

        if (trustStoreLocation.isEmpty()) {
            LOG.warn("{} not specified", getSslTruststoreLocationProperty());
        } else {
            sslContextBuilder.trustManager(createTrustManager(trustStoreLocation, trustStorePassword, trustStoreType,
                sslCrlEnabled, sslOcspEnabled, sslServerHostnameVerificationEnabled,
                sslClientHostnameVerificationEnabled, getFipsMode(config)));
        }

        sslContextBuilder.enableOcsp(sslOcspEnabled);
        sslContextBuilder.protocols(getEnabledProtocols(config));
        sslContextBuilder.ciphers(getCipherSuites(config));

        SslProvider sslProvider = getSslProvider(config);
        if (sslProvider != null) {
            sslContextBuilder.sslProvider(getSslProvider(config));
        }

        SslContext sslContext1 = sslContextBuilder.build();

        if (getFipsMode(config) && isServerHostnameVerificationEnabled(config)) {
            return addHostnameVerification(sslContext1, "Server");
        } else {
            return sslContext1;
        }
    }

    public SslContext createNettySslContextForServer(ZKConfig config)
        throws X509Exception.SSLContextException, X509Exception.KeyManagerException, X509Exception.TrustManagerException, SSLException {
        String keyStoreLocation = config.getProperty(getSslKeystoreLocationProperty(), "");
        String keyStorePassword = getPasswordFromConfigPropertyOrFile(config, getSslKeystorePasswdProperty(),
            getSslKeystorePasswdPathProperty());
        String keyStoreType = config.getProperty(getSslKeystoreTypeProperty());

        if (keyStoreLocation.isEmpty()) {
            throw new X509Exception.SSLContextException(
                "Keystore is required for SSL server: " + getSslKeystoreLocationProperty());
        }

        KeyManager km = createKeyManager(keyStoreLocation, keyStorePassword, keyStoreType);

        String trustStoreLocation = config.getProperty(getSslTruststoreLocationProperty(), "");
        String trustStorePassword = getPasswordFromConfigPropertyOrFile(config, getSslTruststorePasswdProperty(),
            getSslTruststorePasswdPathProperty());
        String trustStoreType = config.getProperty(getSslTruststoreTypeProperty());

        boolean sslCrlEnabled = config.getBoolean(getSslCrlEnabledProperty());
        boolean sslOcspEnabled = config.getBoolean(getSslOcspEnabledProperty());
        boolean sslServerHostnameVerificationEnabled = isServerHostnameVerificationEnabled(config);
        boolean sslClientHostnameVerificationEnabled = isClientHostnameVerificationEnabled(config);

        TrustManager tm = null;

        if (trustStoreLocation.isEmpty()) {
            LOG.warn("{} not specified", getSslTruststoreLocationProperty());
        } else {
            tm = createTrustManager(trustStoreLocation, trustStorePassword, trustStoreType,
                sslCrlEnabled, sslOcspEnabled, sslServerHostnameVerificationEnabled,
                sslClientHostnameVerificationEnabled, getFipsMode(config));
        }

        return createNettySslContextForServer(config, km, tm);
    }

    public SslContext createNettySslContextForServer(ZKConfig config, KeyManager keyManager, TrustManager trustManager) throws SSLException {
        SslContextBuilder sslContextBuilder = SslContextBuilder.forServer(keyManager);

        if (trustManager != null) {
            sslContextBuilder.trustManager(trustManager);
        }

        sslContextBuilder.enableOcsp(config.getBoolean(getSslOcspEnabledProperty()));
        sslContextBuilder.protocols(getEnabledProtocols(config));
        sslContextBuilder.clientAuth(getClientAuth(config).toNettyClientAuth());
        sslContextBuilder.ciphers(getCipherSuites(config));

        SslProvider sslProvider = getSslProvider(config);
        if (sslProvider != null) {
            sslContextBuilder.sslProvider(getSslProvider(config));
        }

        SslContext sslContext1 = sslContextBuilder.build();

        if (getFipsMode(config) && isClientHostnameVerificationEnabled(config)) {
            return addHostnameVerification(sslContext1, "Client");
        } else {
            return sslContext1;
        }
    }

    private SslContext addHostnameVerification(SslContext sslContext, String clientOrServer) {
        return new DelegatingSslContext(sslContext) {
            @Override
            protected void initEngine(SSLEngine sslEngine) {
                SSLParameters sslParameters = sslEngine.getSSLParameters();
                sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
                sslEngine.setSSLParameters(sslParameters);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{} hostname verification: enabled HTTPS style endpoint identification algorithm", clientOrServer);
                }
            }
        };
    }

    private String[] getEnabledProtocols(final ZKConfig config) {
        String enabledProtocolsInput = config.getProperty(getSslEnabledProtocolsProperty());
        if (enabledProtocolsInput == null) {
            return new String[]{ config.getProperty(getSslProtocolProperty(), DEFAULT_PROTOCOL) };
        }
        return enabledProtocolsInput.split(",");
    }

    private X509Util.ClientAuth getClientAuth(final ZKConfig config) {
        return X509Util.ClientAuth.fromPropertyValue(config.getProperty(getSslClientAuthProperty()));
    }

    private Iterable<String> getCipherSuites(final ZKConfig config) {
        String cipherSuitesInput = config.getProperty(getSslCipherSuitesProperty());
        if (cipherSuitesInput == null) {
            return Arrays.asList(X509Util.getDefaultCipherSuites());
        } else {
            return Arrays.asList(cipherSuitesInput.split(","));
        }
    }

    public SslProvider getSslProvider(ZKConfig config) {
        String propertyValue = config.getProperty(getSslProviderProperty());
        if (propertyValue != null && !propertyValue.isEmpty()) {
            return SslProvider.valueOf(propertyValue);
        } else {
            return null;
        }
    }
}
