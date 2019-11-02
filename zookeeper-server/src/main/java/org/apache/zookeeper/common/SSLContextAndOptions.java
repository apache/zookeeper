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

import static java.util.Objects.requireNonNull;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper class for an SSLContext + some config options that can't be set on the context when it is created but
 * must be set on a secure socket created by the context after the socket creation. By wrapping the options in this
 * class we avoid reading from global system properties during socket configuration. This makes testing easier
 * since we can create different X509Util instances with different configurations in a single test process, and
 * unit test interactions between them.
 */
public class SSLContextAndOptions {

    private static final Logger LOG = LoggerFactory.getLogger(SSLContextAndOptions.class);

    private final X509Util x509Util;
    private final String[] enabledProtocols;
    private final String[] cipherSuites;
    private final List<String> cipherSuitesAsList;
    private final X509Util.ClientAuth clientAuth;
    private final SSLContext sslContext;
    private final int handshakeDetectionTimeoutMillis;

    /**
     * Note: constructor is intentionally package-private, only the X509Util class should be creating instances of this
     * class.
     * @param x509Util the X509Util that created this object.
     * @param config a ZKConfig that holds config properties.
     * @param sslContext the SSLContext.
     */
    SSLContextAndOptions(final X509Util x509Util, final ZKConfig config, final SSLContext sslContext) {
        this.x509Util = requireNonNull(x509Util);
        this.sslContext = requireNonNull(sslContext);
        this.enabledProtocols = getEnabledProtocols(requireNonNull(config), sslContext);
        String[] ciphers = getCipherSuites(config);
        this.cipherSuites = ciphers;
        this.cipherSuitesAsList = Collections.unmodifiableList(Arrays.asList(ciphers));
        this.clientAuth = getClientAuth(config);
        this.handshakeDetectionTimeoutMillis = getHandshakeDetectionTimeoutMillis(config);
    }

    public SSLContext getSSLContext() {
        return sslContext;
    }

    public SSLSocket createSSLSocket() throws IOException {
        return configureSSLSocket((SSLSocket) sslContext.getSocketFactory().createSocket(), true);
    }

    public SSLSocket createSSLSocket(Socket socket, byte[] pushbackBytes) throws IOException {
        SSLSocket sslSocket;
        if (pushbackBytes != null && pushbackBytes.length > 0) {
            sslSocket = (SSLSocket) sslContext.getSocketFactory()
                                              .createSocket(socket, new ByteArrayInputStream(pushbackBytes), true);
        } else {
            sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(socket, null, socket.getPort(), true);
        }
        return configureSSLSocket(sslSocket, false);
    }

    public SSLServerSocket createSSLServerSocket() throws IOException {
        SSLServerSocket sslServerSocket = (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket();
        return configureSSLServerSocket(sslServerSocket);
    }

    public SSLServerSocket createSSLServerSocket(int port) throws IOException {
        SSLServerSocket sslServerSocket = (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(port);
        return configureSSLServerSocket(sslServerSocket);
    }

    public SslContext createNettyJdkSslContext(SSLContext sslContext, boolean isClientSocket) {
        return new JdkSslContext(
                sslContext,
                isClientSocket,
                cipherSuitesAsList,
                IdentityCipherSuiteFilter.INSTANCE,
                null,
                isClientSocket
                        ? X509Util.ClientAuth.NONE.toNettyClientAuth()
                        : clientAuth.toNettyClientAuth(),
                enabledProtocols,
                false);
    }

    public int getHandshakeDetectionTimeoutMillis() {
        return handshakeDetectionTimeoutMillis;
    }

    private SSLSocket configureSSLSocket(SSLSocket socket, boolean isClientSocket) {
        SSLParameters sslParameters = socket.getSSLParameters();
        configureSslParameters(sslParameters, isClientSocket);
        socket.setSSLParameters(sslParameters);
        socket.setUseClientMode(isClientSocket);
        return socket;
    }

    private SSLServerSocket configureSSLServerSocket(SSLServerSocket socket) {
        SSLParameters sslParameters = socket.getSSLParameters();
        configureSslParameters(sslParameters, false);
        socket.setSSLParameters(sslParameters);
        socket.setUseClientMode(false);
        return socket;
    }

    private void configureSslParameters(SSLParameters sslParameters, boolean isClientSocket) {
        if (cipherSuites != null) {
            LOG.debug(
                "Setup cipher suites for {} socket: {}",
                isClientSocket ? "client" : "server",
                Arrays.toString(cipherSuites));
            sslParameters.setCipherSuites(cipherSuites);
        }

        if (enabledProtocols != null) {
            LOG.debug(
                "Setup enabled protocols for {} socket: {}",
                isClientSocket ? "client" : "server",
                Arrays.toString(enabledProtocols));
            sslParameters.setProtocols(enabledProtocols);
        }

        if (!isClientSocket) {
            switch (clientAuth) {
            case NEED:
                sslParameters.setNeedClientAuth(true);
                break;
            case WANT:
                sslParameters.setWantClientAuth(true);
                break;
            default:
                sslParameters.setNeedClientAuth(false); // also clears the wantClientAuth flag according to docs
                break;
            }
        }
    }

    private String[] getEnabledProtocols(final ZKConfig config, final SSLContext sslContext) {
        String enabledProtocolsInput = config.getProperty(x509Util.getSslEnabledProtocolsProperty());
        if (enabledProtocolsInput == null) {
            return new String[]{sslContext.getProtocol()};
        }
        return enabledProtocolsInput.split(",");
    }

    private String[] getCipherSuites(final ZKConfig config) {
        String cipherSuitesInput = config.getProperty(x509Util.getSslCipherSuitesProperty());
        if (cipherSuitesInput == null) {
            return X509Util.getDefaultCipherSuites();
        } else {
            return cipherSuitesInput.split(",");
        }
    }

    private X509Util.ClientAuth getClientAuth(final ZKConfig config) {
        return X509Util.ClientAuth.fromPropertyValue(config.getProperty(x509Util.getSslClientAuthProperty()));
    }

    private int getHandshakeDetectionTimeoutMillis(final ZKConfig config) {
        String propertyString = config.getProperty(x509Util.getSslHandshakeDetectionTimeoutMillisProperty());
        int result;
        if (propertyString == null) {
            result = X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS;
        } else {
            result = Integer.parseInt(propertyString);
            if (result < 1) {
                // Timeout of 0 is not allowed, since an infinite timeout can permanently lock up an
                // accept() thread.
                LOG.warn(
                    "Invalid value for {}: {}, using the default value of {}",
                    x509Util.getSslHandshakeDetectionTimeoutMillisProperty(),
                    result,
                    X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS);
                result = X509Util.DEFAULT_HANDSHAKE_DETECTION_TIMEOUT_MILLIS;
            }
        }
        return result;
    }

}
