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


import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
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

import static org.apache.zookeeper.common.X509Exception.*;

/**
 * Utility code for X509 handling
 */
public abstract class X509Util {
    private static final Logger LOG = LoggerFactory.getLogger(X509Util.class);

    private String sslKeystoreLocationProperty = getConfigPrefix() + "keyStore.location";
    private String sslKeystorePasswdProperty = getConfigPrefix() + "keyStore.password";
    private String sslTruststoreLocationProperty = getConfigPrefix() + "trustStore.location";
    private String sslTruststorePasswdProperty = getConfigPrefix() + "trustStore.password";
    private String sslHostnameVerificationEnabledProperty = getConfigPrefix() + "hostnameVerification";
    private String sslCrlEnabledProperty = getConfigPrefix() + "ssl.crl";
    private String sslOcspEnabledProperty = getConfigPrefix() + "ssl.ocsp";

    private volatile SSLContext defaultSSLContext;

    protected abstract String getConfigPrefix();
    protected abstract boolean shouldVerifyClientHostname();

    public String getSslKeystoreLocationProperty() {
        return sslKeystoreLocationProperty;
    }

    public String getSslKeystorePasswdProperty() {
        return sslKeystorePasswdProperty;
    }

    public String getSslTruststoreLocationProperty() {
        return sslTruststoreLocationProperty;
    }

    public String getSslTruststorePasswdProperty() {
        return sslTruststorePasswdProperty;
    }

    public String getSslHostnameVerificationEnabledProperty() {
        return sslHostnameVerificationEnabledProperty;
    }
    public String getSslCrlEnabledProperty() {
        return sslCrlEnabledProperty;
    }

    public String getSslOcspEnabledProperty() {
        return sslOcspEnabledProperty;
    }

    public synchronized SSLContext getDefaultSSLContext() throws SSLContextException {
        if (defaultSSLContext == null) {
            defaultSSLContext = createSSLContext();
        }
        return defaultSSLContext;
    }

    public SSLContext createSSLContext() throws SSLContextException {
        /**
         * Since Configuration initializes the key store and trust store related
         * configuration from system property. Reading property from
         * configuration will be same reading from system property
         */
        ZKConfig config=new ZKConfig();
        return createSSLContext(config);
    }

    public SSLContext createSSLContext(ZKConfig config) throws SSLContextException {
        KeyManager[] keyManagers = null;
        TrustManager[] trustManagers = null;

        String keyStoreLocationProp = config.getProperty(sslKeystoreLocationProperty);
        String keyStorePasswordProp = config.getProperty(sslKeystorePasswdProperty);

        // There are legal states in some use cases for null KeyManager or TrustManager.
        // But if a user wanna specify one, location and password are required.

        if (keyStoreLocationProp == null && keyStorePasswordProp == null) {
            LOG.warn(getSslKeystoreLocationProperty() + " not specified");
        } else {
            if (keyStoreLocationProp == null) {
                throw new SSLContextException(getSslKeystoreLocationProperty() + " not specified");
            }
            if (keyStorePasswordProp == null) {
                throw new SSLContextException(getSslKeystorePasswdProperty() + " not specified");
            }
            try {
                keyManagers = new KeyManager[]{
                        createKeyManager(keyStoreLocationProp, keyStorePasswordProp)};
            } catch (KeyManagerException e) {
                throw new SSLContextException("Failed to create KeyManager", e);
            }
        }

        String trustStoreLocationProp = config.getProperty(sslTruststoreLocationProperty);
        String trustStorePasswordProp = config.getProperty(sslTruststorePasswdProperty);

        boolean sslCrlEnabled = config.getBoolean(this.sslCrlEnabledProperty);
        boolean sslOcspEnabled = config.getBoolean(this.sslOcspEnabledProperty);
        boolean sslServerHostnameVerificationEnabled = config.getBoolean(this.getSslHostnameVerificationEnabledProperty());

        if (trustStoreLocationProp == null) {
            LOG.warn(getSslTruststoreLocationProperty() + " not specified");
        } else {
            if (trustStoreLocationProp == null) {
                throw new SSLContextException(getSslTruststoreLocationProperty() + " not specified for client connection");
            }
            try {

                trustManagers = new TrustManager[]{
                        createTrustManager(trustStoreLocationProp, trustStorePasswordProp, sslCrlEnabled, sslOcspEnabled, sslServerHostnameVerificationEnabled, shouldVerifyClientHostname())};
            } catch (TrustManagerException e) {
                throw new SSLContextException("Failed to create TrustManager", e);
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

    public static X509TrustManager createTrustManager(String trustStoreLocation, String trustStorePassword,
                                                      boolean crlEnabled, boolean ocspEnabled,
                                                      final boolean hostnameVerificationEnabled,
                                                      final boolean shouldVerifyClientHostname)
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
                if (tm instanceof X509ExtendedTrustManager) {
                    return new X509ExtendedTrustManager() {
                        X509ExtendedTrustManager x509ExtendedTrustManager = (X509ExtendedTrustManager) tm;
                        DefaultHostnameVerifier hostnameVerifier = new DefaultHostnameVerifier();

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return x509ExtendedTrustManager.getAcceptedIssuers();
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
                            if (hostnameVerificationEnabled && shouldVerifyClientHostname) {
                                performHostnameVerification(socket.getInetAddress().getHostName(), chain[0]);
                            }
                            x509ExtendedTrustManager.checkClientTrusted(chain, authType, socket);
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
                            if (hostnameVerificationEnabled) {
                                performHostnameVerification(socket.getInetAddress().getHostName(), chain[0]);
                            }
                            x509ExtendedTrustManager.checkServerTrusted(chain, authType, socket);
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
                            if (hostnameVerificationEnabled && shouldVerifyClientHostname) {
                                performHostnameVerification(engine.getPeerHost(), chain[0]);
                            }
                            x509ExtendedTrustManager.checkServerTrusted(chain, authType, engine);
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
                            if (hostnameVerificationEnabled) {
                                performHostnameVerification(engine.getPeerHost(), chain[0]);
                            }
                            x509ExtendedTrustManager.checkServerTrusted(chain, authType, engine);
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                            x509ExtendedTrustManager.checkClientTrusted(chain, authType);
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                            x509ExtendedTrustManager.checkServerTrusted(chain, authType);
                        }

                        private void performHostnameVerification(String hostname, X509Certificate certificate) throws CertificateException {
                            try {
                                hostnameVerifier.verify(hostname, certificate);
                            } catch (SSLException e) {
                                throw new CertificateException("Failed to verify hostname", e);
                            }
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

    public SSLSocket createSSLSocket() throws X509Exception, IOException {
        SSLSocket sslSocket = (SSLSocket) getDefaultSSLContext().getSocketFactory().createSocket();
        SSLParameters sslParameters = sslSocket.getSSLParameters();
        sslParameters.setNeedClientAuth(true);

        sslSocket.setSSLParameters(sslParameters);

        return sslSocket;
    }


    public SSLServerSocket createSSLServerSocket() throws X509Exception, IOException {
        SSLServerSocket sslServerSocket = (SSLServerSocket) getDefaultSSLContext().getServerSocketFactory().createServerSocket();
        SSLParameters sslParameters = sslServerSocket.getSSLParameters();
        sslParameters.setNeedClientAuth(true);
        sslServerSocket.setSSLParameters(sslParameters);
        return sslServerSocket;
    }

    public SSLServerSocket createSSLServerSocket(int port) throws X509Exception, IOException {
        SSLServerSocket sslServerSocket = (SSLServerSocket) getDefaultSSLContext().getServerSocketFactory().createServerSocket(port);
        SSLParameters sslParameters = sslServerSocket.getSSLParameters();
        sslParameters.setNeedClientAuth(true);

        sslServerSocket.setSSLParameters(sslParameters);

        return sslServerSocket;
    }
}
