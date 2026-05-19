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
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.security.Security;
import java.util.Arrays;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extends {@link ClientX509Util} with Netty-specific SSL context creation
 * methods. This class is only loaded when Netty is present on the classpath.
 * Code that only needs SSL property names should use {@link ClientX509Util}
 * directly so that Netty remains an optional dependency.
 */
public class ClientNettyX509Util extends ClientX509Util {

    private static final Logger LOG = LoggerFactory.getLogger(ClientNettyX509Util.class);

    public SslContext createNettySslContextForClient(ZKConfig config)
        throws X509Exception.KeyManagerException, X509Exception.TrustManagerException, SSLException {
        SslContextBuilder sslContextBuilder = SslContextBuilder.forClient();

        KeyManager km = buildKeyManager(config);
        if (km != null) {
            sslContextBuilder.keyManager(km);
        }

        TrustManager tm = buildTrustManager(config);
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
        KeyManager km = buildKeyManager(config);
        if (km == null) {
            throw new X509Exception.SSLContextException(
                "Keystore is required for SSL server: " + getSslKeystoreLocationProperty());
        }
        return createNettySslContextForServer(config, km, buildTrustManager(config));
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
        sslContextBuilder.clientAuth(toNettyClientAuth(getClientAuth(config)));
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

    private SslContextBuilder handleTcnativeOcspStapling(SslContextBuilder builder, ZKConfig config) {
        SslProvider sslProvider = getSslProvider(config);
        boolean tcnative = sslProvider == SslProvider.OPENSSL || sslProvider == SslProvider.OPENSSL_REFCNT;
        boolean ocspEnabled = config.getBoolean(getSslOcspEnabledProperty(), Boolean.parseBoolean(Security.getProperty("ocsp.enable")));

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

    private static io.netty.handler.ssl.ClientAuth toNettyClientAuth(X509Util.ClientAuth clientAuth) {
        switch (clientAuth) {
            case NONE: return io.netty.handler.ssl.ClientAuth.NONE;
            case WANT: return io.netty.handler.ssl.ClientAuth.OPTIONAL;
            case NEED: return io.netty.handler.ssl.ClientAuth.REQUIRE;
            default: throw new IllegalArgumentException("Unknown ClientAuth: " + clientAuth);
        }
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
}
