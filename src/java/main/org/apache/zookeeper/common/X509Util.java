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


import org.apache.zookeeper.server.quorum.BufferedSocket;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.security.util.HostnameChecker;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;

import static org.apache.zookeeper.common.X509Exception.KeyManagerException;
import static org.apache.zookeeper.common.X509Exception.SSLContextException;
import static org.apache.zookeeper.common.X509Exception.TrustManagerException;

/**
 * Utility code for X509 handling
 */
public class X509Util {
    private static final Logger LOG = LoggerFactory.getLogger(X509Util.class);

    /**
     * @deprecated Use {@link ZKConfig#SSL_KEYSTORE_LOCATION}
     *             instead.
     */
    @Deprecated
    public static final String SSL_KEYSTORE_LOCATION = "zookeeper.ssl.keyStore.location";
    /**
     * @deprecated Use {@link ZKConfig#SSL_KEYSTORE_PASSWD}
     *             instead.
     */
    @Deprecated
    public static final String SSL_KEYSTORE_PASSWD = "zookeeper.ssl.keyStore.password";
    /**
     * @deprecated Use {@link ZKConfig#SSL_TRUSTSTORE_LOCATION}
     *             instead.
     */
    @Deprecated
    public static final String SSL_TRUSTSTORE_LOCATION = "zookeeper.ssl.trustStore.location";
    /**
     * @deprecated Use {@link ZKConfig#SSL_TRUSTSTORE_PASSWD}
     *             instead.
     */
    @Deprecated
    public static final String SSL_TRUSTSTORE_PASSWD = "zookeeper.ssl.trustStore.password";
    /**
     * @deprecated Use {@link ZKConfig#SSL_AUTHPROVIDER}
     *             instead.
     */
    @Deprecated
    public static final String SSL_AUTHPROVIDER = "zookeeper.ssl.authProvider";

    public static SSLContext createSSLContext() throws SSLContextException {
        /**
         * Since Configuration initializes the key store and trust store related
         * configuration from system property. Reading property from
         * configuration will be same reading from system property
         */
        ZKConfig config=new ZKConfig();
        return createSSLContext(config);
    }

    public static SSLContext createSSLContext(ZKConfig config) throws SSLContextException {
        KeyManager[] keyManagers = null;
        TrustManager[] trustManagers = null;

        String keyStoreLocationProp = config.getProperty(ZKConfig.SSL_KEYSTORE_LOCATION);
        String keyStorePasswordProp = config.getProperty(ZKConfig.SSL_KEYSTORE_PASSWD);

        // There are legal states in some use cases for null KeyManager or TrustManager.
        // But if a user wanna specify one, location and password are required.

        if (keyStoreLocationProp == null && keyStorePasswordProp == null) {
            LOG.warn("keystore not specified for client connection");
        } else {
            if (keyStoreLocationProp == null) {
                throw new SSLContextException("keystore location not specified for client connection");
            }
            if (keyStorePasswordProp == null) {
                throw new SSLContextException("keystore password not specified for client connection");
            }
            try {
                keyManagers = new KeyManager[]{
                        createKeyManager(keyStoreLocationProp, keyStorePasswordProp)};
            } catch (KeyManagerException e) {
                throw new SSLContextException("Failed to create KeyManager", e);
            }
        }

        String trustStoreLocationProp = config.getProperty(ZKConfig.SSL_TRUSTSTORE_LOCATION);
        String trustStorePasswordProp = config.getProperty(ZKConfig.SSL_TRUSTSTORE_PASSWD);

        if (trustStoreLocationProp == null && trustStorePasswordProp == null) {
            LOG.warn("keystore not specified for client connection");
        } else {
            if (trustStoreLocationProp == null) {
                throw new SSLContextException("keystore location not specified for client connection");
            }
            if (trustStorePasswordProp == null) {
                throw new SSLContextException("keystore password not specified for client connection");
            }
            try {
                trustManagers = new TrustManager[]{
                        createTrustManager(trustStoreLocationProp, trustStorePasswordProp, config.getBoolean(ZKConfig.SSL_CRL_ENABLED), config.getBoolean(ZKConfig.SSL_OCSP_ENABLED))};
            } catch (TrustManagerException e) {
                throw new SSLContextException("Failed to create KeyManager", e);
            }
        }

        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance("TLSv1");
            sslContext.init(keyManagers, trustManagers, null);
        } catch (Exception e) {
            throw new SSLContextException(e);
        }
        return sslContext;
    }

    public static X509KeyManager createKeyManager(String keyStoreLocation, String keyStorePassword)
            throws KeyManagerException {
        FileInputStream inputStream = null;
        try {
            char[] keyStorePasswordChars = keyStorePassword.toCharArray();
            File keyStoreFile = new File(keyStoreLocation);
            KeyStore ks = KeyStore.getInstance("JKS");
            inputStream = new FileInputStream(keyStoreFile);
            ks.load(inputStream, keyStorePasswordChars);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, keyStorePasswordChars);

            for (KeyManager km : kmf.getKeyManagers()) {
                if (km instanceof X509KeyManager) {
                    return (X509KeyManager) km;
                }
            }
            throw new KeyManagerException("Couldn't find X509KeyManager");

        } catch (Exception e) {
            throw new KeyManagerException(e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {}
            }
        }
    }

    public static X509TrustManager createTrustManager(String trustStoreLocation, String trustStorePassword, boolean crlEnabled, boolean ocspEnabled)
            throws TrustManagerException {
        FileInputStream inputStream = null;
        try {
            char[] trustStorePasswordChars = trustStorePassword.toCharArray();
            File trustStoreFile = new File(trustStoreLocation);
            KeyStore ts = KeyStore.getInstance("JKS");
            inputStream = new FileInputStream(trustStoreFile);
            ts.load(inputStream, trustStorePasswordChars);

            PKIXBuilderParameters pbParams = new PKIXBuilderParameters(ts, new X509CertSelector());
            if (crlEnabled || ocspEnabled) {
                pbParams.setRevocationEnabled(true);
                System.setProperty("com.sun.net.ssl.checkRevocation", "true");
                System.setProperty("com.sun.security.enableCRLDP", "true");
                if (ocspEnabled) {
                    Security.setProperty("ocsp.enable", "true");
                }

            } else {
                pbParams.setRevocationEnabled(false);
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
            tmf.init(new CertPathTrustManagerParameters(pbParams));

            for (final TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return new X509ExtendedTrustManager() {
                        HostnameChecker hostnameChecker = HostnameChecker.getInstance(HostnameChecker.TYPE_TLS);

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return ((X509ExtendedTrustManager) tm).getAcceptedIssuers();
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] x509Certificates, String s, Socket socket) throws CertificateException {
                            hostnameChecker.match(socket.getInetAddress().getHostName(), x509Certificates[0]);
                            ((X509ExtendedTrustManager) tm).checkClientTrusted(x509Certificates, s, socket);
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] x509Certificates, String s, Socket socket) throws CertificateException {
                            hostnameChecker.match(((SSLSocket) socket).getHandshakeSession().getPeerHost(), x509Certificates[0]);
                            ((X509ExtendedTrustManager) tm).checkServerTrusted(x509Certificates, s, socket);
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine) throws CertificateException {
                            throw new RuntimeException("sslengine should not be in use");
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine) throws CertificateException {
                            throw new RuntimeException("sslengine should not be in use");
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
                            throw new RuntimeException("expecting a socket");
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
                            throw new RuntimeException("expecting a socket");
                        }
                    };
                }
            }
            throw new TrustManagerException("Couldn't find X509TrustManager");
        } catch (Exception e) {
            throw new TrustManagerException(e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {}
            }
        }
    }

    public static SSLSocket createSSLSocket() throws X509Exception, IOException {
        SSLSocket sslSocket = (SSLSocket) createSSLContext().getSocketFactory().createSocket();
        SSLParameters sslParameters = sslSocket.getSSLParameters();
        sslParameters.setNeedClientAuth(true);

        sslSocket.setSSLParameters(sslParameters);

        return sslSocket;
    }


    public static SSLServerSocket createSSLServerSocket() throws X509Exception, IOException {
        SSLServerSocket sslServerSocket = (SSLServerSocket) createSSLContext().getServerSocketFactory().createServerSocket();
        SSLParameters sslParameters = sslServerSocket.getSSLParameters();
        sslParameters.setNeedClientAuth(true);
        sslServerSocket.setSSLParameters(sslParameters);
        return sslServerSocket;
    }

    public static SSLServerSocket createSSLServerSocket(int port) throws X509Exception, IOException {
        SSLServerSocket sslServerSocket = (SSLServerSocket) createSSLContext().getServerSocketFactory().createServerSocket(port);
        SSLParameters sslParameters = sslServerSocket.getSSLParameters();
        sslParameters.setNeedClientAuth(true);

        sslServerSocket.setSSLParameters(sslParameters);

        return sslServerSocket;
    }

    public static Socket createUnifiedSocket(BufferedSocket socket) throws X509Exception, IOException {
        socket.getInputStream().mark(6);

        byte[] litmus = new byte[5];
        socket.getInputStream().read(litmus, 0, 5);

        socket.getInputStream().reset();

        boolean isSsl = SslHandler.isEncrypted(ChannelBuffers.wrappedBuffer(litmus));
        if (isSsl) {
            LOG.info(socket.getInetAddress() + " attempting to connect over ssl");
            SSLSocket sslSocket = (SSLSocket) createSSLContext().getSocketFactory().createSocket(socket, null, socket.getPort(), false);
            sslSocket.setUseClientMode(false);
            return sslSocket;
        } else {
            LOG.info(socket.getInetAddress() + " attempting to connect without ssl");
            return socket;
        }

    }
}