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
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.util.Arrays;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
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

    @Override
    protected boolean shouldAllowReverseDnsLookup() {
        return false;
    }

    public String getSslAuthProviderProperty() {
        return sslAuthProviderProperty;
    }

    public String getSslProviderProperty() {
        return sslProviderProperty;
    }

    public SslContext createNettySslContextForClient(ZKConfig config)
        throws X509Exception.SSLContextException, X509Exception.KeyManagerException,
               X509Exception.TrustManagerException, SSLException {
        SSLContext suppliedSSLContext = loadSuppliedSSLContext(config);
        if (suppliedSSLContext != null) {
            return createNettyJdkSslContext(config, suppliedSSLContext, true);
        }

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

        TrustManager tm = getTrustManager(config);
        if (tm != null) {
            sslContextBuilder.trustManager(tm);
        }

        handleTcnativeOcspStapling(sslContextBuilder, config);
        String[] enabledProtocols = getEnabledProtocols(config);
        if (enabledProtocols != null) {
            sslContextBuilder.protocols(enabledProtocols);
        }
        Iterable<String> enabledCiphers = getCipherSuites(config);
        if (enabledCiphers != null) {
            sslContextBuilder.ciphers(enabledCiphers);
        }
        sslContextBuilder.sslProvider(getSslProvider(config));

        SslContext sslContext1 = sslContextBuilder.build();

        if ((getFipsMode(config) || tm == null) && isServerHostnameVerificationEnabled(config)) {
            return addHostnameVerification(sslContext1, "Server");
        } else {
            return sslContext1;
        }
    }

    public SslContext createNettySslContextForServer(ZKConfig config)
        throws X509Exception.SSLContextException, X509Exception.KeyManagerException, X509Exception.TrustManagerException, SSLException {
        SSLContext suppliedSSLContext = loadSuppliedSSLContext(config);
        if (suppliedSSLContext != null) {
            return createNettyJdkSslContext(config, suppliedSSLContext, false);
        }

        String keyStoreLocation = config.getProperty(getSslKeystoreLocationProperty(), "");
        String keyStorePassword = getPasswordFromConfigPropertyOrFile(config, getSslKeystorePasswdProperty(),
            getSslKeystorePasswdPathProperty());
        String keyStoreType = config.getProperty(getSslKeystoreTypeProperty());

        if (keyStoreLocation.isEmpty()) {
            throw new X509Exception.SSLContextException(
                "Keystore is required for SSL server: " + getSslKeystoreLocationProperty());
        }

        KeyManager km = createKeyManager(keyStoreLocation, keyStorePassword, keyStoreType);

        return createNettySslContextForServer(config, km, getTrustManager(config));
    }

    public SslContext createNettySslContextForServer(ZKConfig config, KeyManager keyManager, TrustManager trustManager) throws SSLException {
        SslContextBuilder sslContextBuilder = SslContextBuilder.forServer(keyManager);

        if (trustManager != null) {
            sslContextBuilder.trustManager(trustManager);
        }

        handleTcnativeOcspStapling(sslContextBuilder, config);
        String[] enabledProtocols = getEnabledProtocols(config);
        if (enabledProtocols != null) {
            sslContextBuilder.protocols(enabledProtocols);
        }
        sslContextBuilder.clientAuth(getClientAuth(config).toNettyClientAuth());
        Iterable<String> enabledCiphers = getCipherSuites(config);
        if (enabledCiphers != null) {
            sslContextBuilder.ciphers(enabledCiphers);
        }
        sslContextBuilder.sslProvider(getSslProvider(config));

        SslContext sslContext1 = sslContextBuilder.build();

        if ((getFipsMode(config) || trustManager == null) && isClientHostnameVerificationEnabled(config)) {
            return addHostnameVerification(sslContext1, "Client");
        } else {
            return sslContext1;
        }
    }

    /**
     * Wraps a user supplied {@link SSLContext} in a Netty {@link SslContext}, applying the configured
     * protocols, cipher suites, client auth mode and hostname verification on top of it.
     *
     * <p>A supplied SSLContext carries its own key and trust managers, so it can only be used with the
     * JDK SSL provider: the OpenSSL providers build their own native context and cannot delegate to it.
     *
     * <p>Unlike the file based path, hostname verification is applied whenever it is enabled. The file
     * based path relies on {@link ZKTrustManager} to verify hostnames and only falls back to endpoint
     * identification when no trust manager is available, which is never the case for a supplied context.
     *
     * @param config     the configuration to read the SSL options from.
     * @param sslContext the user supplied SSLContext.
     * @param isClient   {@code true} to create a client side context, {@code false} for server side.
     * @return the Netty SslContext.
     * @throws X509Exception.SSLContextException if a non JDK SSL provider is configured.
     */
    private SslContext createNettyJdkSslContext(ZKConfig config, SSLContext sslContext, boolean isClient)
        throws X509Exception.SSLContextException {
        SslProvider sslProvider = getSslProvider(config);
        if (sslProvider != SslProvider.JDK) {
            throw new X509Exception.SSLContextException("An SSLContext supplied through "
                                                       + getSslContextSupplierClassProperty()
                                                       + " can only be used with the JDK SSL provider, but "
                                                       + getSslProviderProperty()
                                                       + " is set to "
                                                       + sslProvider);
        }

        SslContext nettySslContext = new JdkSslContext(
            sslContext,
            isClient,
            getCipherSuites(config),
            IdentityCipherSuiteFilter.INSTANCE,
            null,
            isClient ? X509Util.ClientAuth.NONE.toNettyClientAuth() : getClientAuth(config).toNettyClientAuth(),
            getEnabledProtocols(config),
            false);

        boolean hostnameVerificationEnabled = isClient
            ? isServerHostnameVerificationEnabled(config)
            : isClientHostnameVerificationEnabled(config);
        if (hostnameVerificationEnabled) {
            return addHostnameVerification(nettySslContext, isClient ? "Server" : "Client");
        }
        return nettySslContext;
    }

    private SslContextBuilder handleTcnativeOcspStapling(SslContextBuilder builder, ZKConfig config) {
        SslProvider sslProvider = getSslProvider(config);
        boolean tcnative = sslProvider == SslProvider.OPENSSL || sslProvider == SslProvider.OPENSSL_REFCNT;
        boolean ocspEnabled = config.getBoolean(getSslOcspEnabledProperty());

        if (tcnative && ocspEnabled && OpenSsl.isOcspSupported()) {
            builder.enableOcsp(ocspEnabled);
        }
        return builder;
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
            return null;
        }
        return enabledProtocolsInput.split(",");
    }

    private X509Util.ClientAuth getClientAuth(final ZKConfig config) {
        return X509Util.ClientAuth.fromPropertyValue(config.getProperty(getSslClientAuthProperty()));
    }

    private Iterable<String> getCipherSuites(final ZKConfig config) {
        String cipherSuitesInput = config.getProperty(getSslCipherSuitesProperty());
        if (cipherSuitesInput == null) {
            return null;
        } else {
            return Arrays.asList(cipherSuitesInput.split(","));
        }
    }

    public SslProvider getSslProvider(ZKConfig config) {
        return SslProvider.valueOf(config.getProperty(getSslProviderProperty(), "JDK"));
    }

    private TrustManager getTrustManager(ZKConfig config) throws X509Exception.TrustManagerException {
        String trustStoreLocation = config.getProperty(getSslTruststoreLocationProperty(), "");
        String trustStorePassword = getPasswordFromConfigPropertyOrFile(config, getSslTruststorePasswdProperty(),
            getSslTruststorePasswdPathProperty());
        String trustStoreType = config.getProperty(getSslTruststoreTypeProperty());

        boolean sslCrlEnabled = config.getBoolean(getSslCrlEnabledProperty());
        boolean sslOcspEnabled = config.getBoolean(getSslOcspEnabledProperty());
        boolean sslServerHostnameVerificationEnabled = isServerHostnameVerificationEnabled(config);
        boolean sslClientHostnameVerificationEnabled = isClientHostnameVerificationEnabled(config);
        boolean allowReverseDnsLookup = allowReverseDnsLookup(config);

        if (trustStoreLocation.isEmpty()) {
            LOG.warn("{} not specified", getSslTruststoreLocationProperty());
            return null;
        } else {
            return createTrustManager(trustStoreLocation, trustStorePassword, trustStoreType,
                sslCrlEnabled, sslOcspEnabled, sslServerHostnameVerificationEnabled,
                sslClientHostnameVerificationEnabled, allowReverseDnsLookup,
                getFipsMode(config));
        }
    }
}
