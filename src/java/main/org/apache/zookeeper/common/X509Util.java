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


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
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
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;

import org.apache.zookeeper.common.X509Exception.KeyManagerException;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.common.X509Exception.TrustManagerException;

/**
 * Utility code for X509 handling
 */
public abstract class X509Util {
    private static final Logger LOG = LoggerFactory.getLogger(X509Util.class);

    static final String DEFAULT_PROTOCOL = "TLSv1";

    private String sslProtocolProperty = getConfigPrefix() + "protocol";
    private String cipherSuitesProperty = getConfigPrefix() + "ciphersuites";
    private String sslKeystoreLocationProperty = getConfigPrefix() + "keyStore.location";
    private String sslKeystorePasswdProperty = getConfigPrefix() + "keyStore.password";
    private String sslTruststoreLocationProperty = getConfigPrefix() + "trustStore.location";
    private String sslTruststorePasswdProperty = getConfigPrefix() + "trustStore.password";
    private String sslHostnameVerificationEnabledProperty = getConfigPrefix() + "hostnameVerification";
    private String sslCrlEnabledProperty = getConfigPrefix() + "crl";
    private String sslOcspEnabledProperty = getConfigPrefix() + "ocsp";

    private String[] cipherSuites;

    private volatile SSLContext defaultSSLContext;

    public X509Util() {
        String cipherSuitesInput = System.getProperty(cipherSuitesProperty);
        if (cipherSuitesInput == null) {
            cipherSuites = null;
        } else {
            cipherSuites = cipherSuitesInput.split(",");
        }
    }

    protected abstract String getConfigPrefix();
    protected abstract boolean shouldVerifyClientHostname();

    public String getSslProtocolProperty() {
        return sslProtocolProperty;
    }

    public String getCipherSuitesProperty() {
        return cipherSuitesProperty;
    }

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

    public synchronized SSLContext getDefaultSSLContext() throws X509Exception.SSLContextException {
        if (defaultSSLContext == null) {
            defaultSSLContext = createSSLContext();
        }
        return defaultSSLContext;
    }

    private SSLContext createSSLContext() throws SSLContextException {
        /*
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
            } catch (KeyManagerException keyManagerException) {
                throw new SSLContextException("Failed to create KeyManager", keyManagerException);
            }
        }

        String trustStoreLocationProp = config.getProperty(sslTruststoreLocationProperty);
        String trustStorePasswordProp = config.getProperty(sslTruststorePasswdProperty);

        boolean sslCrlEnabled = config.getBoolean(this.sslCrlEnabledProperty);
        boolean sslOcspEnabled = config.getBoolean(this.sslOcspEnabledProperty);
        boolean sslServerHostnameVerificationEnabled = config.getBoolean(this.getSslHostnameVerificationEnabledProperty(), true);

        if (trustStoreLocationProp == null) {
            LOG.warn(getSslTruststoreLocationProperty() + " not specified");
        } else {
            try {
                trustManagers = new TrustManager[]{
                        createTrustManager(trustStoreLocationProp, trustStorePasswordProp, sslCrlEnabled, sslOcspEnabled,
                                sslServerHostnameVerificationEnabled, shouldVerifyClientHostname())};
            } catch (TrustManagerException trustManagerException) {
                throw new SSLContextException("Failed to create TrustManager", trustManagerException);
            }
        }

        String protocol = System.getProperty(sslProtocolProperty, DEFAULT_PROTOCOL);
        try {
            SSLContext sslContext = SSLContext.getInstance(protocol);
            sslContext.init(keyManagers, trustManagers, null);
            return sslContext;
        } catch (NoSuchAlgorithmException|KeyManagementException sslContextInitException) {
            throw new SSLContextException(sslContextInitException);
        }
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
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
            kmf.init(ks, keyStorePasswordChars);

            for (KeyManager km : kmf.getKeyManagers()) {
                if (km instanceof X509KeyManager) {
                    return (X509KeyManager) km;
                }
            }
            throw new KeyManagerException("Couldn't find X509KeyManager");

        } catch (IOException|CertificateException|UnrecoverableKeyException|NoSuchAlgorithmException|KeyStoreException
                keyManagerCreationException) {
            throw new KeyManagerException(keyManagerCreationException);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ioException) {
                    LOG.info("Failed to close key store input stream", ioException);
                }
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
            File trustStoreFile = new File(trustStoreLocation);
            KeyStore ts = KeyStore.getInstance("JKS");
            inputStream = new FileInputStream(trustStoreFile);
            if (trustStorePassword != null) {
                char[] trustStorePasswordChars = trustStorePassword.toCharArray();
                ts.load(inputStream, trustStorePasswordChars);
            } else {
                ts.load(inputStream, null);
            }

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

            // Revocation checking is only supported with the PKIX algorithm
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
            tmf.init(new CertPathTrustManagerParameters(pbParams));

            for (final TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager) {
                    return new ZKTrustManager((X509ExtendedTrustManager) tm, hostnameVerificationEnabled, shouldVerifyClientHostname);
                }
            }
            throw new TrustManagerException("Couldn't find X509TrustManager");
        } catch (IOException|CertificateException|NoSuchAlgorithmException|InvalidAlgorithmParameterException|KeyStoreException
                 trustManagerCreationException) {
            throw new TrustManagerException(trustManagerCreationException);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ioException) {
                    LOG.info("failed to close TrustStore input stream", ioException);
                }
            }
        }
    }

    public SSLSocket createSSLSocket() throws X509Exception, IOException {
        SSLSocket sslSocket = (SSLSocket) getDefaultSSLContext().getSocketFactory().createSocket();
        configureSSLSocket(sslSocket);

        return sslSocket;
    }

    public SSLSocket createSSLSocket(Socket socket) throws X509Exception, IOException {
        SSLSocket sslSocket = (SSLSocket) getDefaultSSLContext().getSocketFactory().createSocket(socket, null, socket.getPort(), true);
        configureSSLSocket(sslSocket);

        return sslSocket;
    }

    private void configureSSLSocket(SSLSocket sslSocket) {
        if (cipherSuites != null) {
            SSLParameters sslParameters = sslSocket.getSSLParameters();
            sslParameters.setCipherSuites(cipherSuites);
            sslSocket.setSSLParameters(sslParameters);
        }
    }


    public SSLServerSocket createSSLServerSocket() throws X509Exception, IOException {
        SSLServerSocket sslServerSocket = (SSLServerSocket) getDefaultSSLContext().getServerSocketFactory().createServerSocket();
        configureSSLServerSocket(sslServerSocket);

        return sslServerSocket;
    }

    public SSLServerSocket createSSLServerSocket(int port) throws X509Exception, IOException {
        SSLServerSocket sslServerSocket = (SSLServerSocket) getDefaultSSLContext().getServerSocketFactory().createServerSocket(port);
        configureSSLServerSocket(sslServerSocket);

        return sslServerSocket;
    }

    private void configureSSLServerSocket(SSLServerSocket sslServerSocket) {
        SSLParameters sslParameters = sslServerSocket.getSSLParameters();
        sslParameters.setNeedClientAuth(true);
        if (cipherSuites != null) {
            sslParameters.setCipherSuites(cipherSuites);
        }

        sslServerSocket.setSSLParameters(sslParameters);
    }
}
