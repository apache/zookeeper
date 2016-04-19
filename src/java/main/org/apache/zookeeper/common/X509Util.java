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


import org.apache.zookeeper.server.quorum.util.ZKX509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.X509Certificate;

import static org.apache.zookeeper.common.X509Exception.KeyManagerException;
import static org.apache.zookeeper.common.X509Exception.SSLContextException;
import static org.apache.zookeeper.common.X509Exception.TrustManagerException;

/**
 * Utility code for X509 handling
 */
public class X509Util {
    private static final Logger LOG = LoggerFactory.getLogger(X509Util.class);

    public static final String SSL_VERSION_DEFAULT = "TLSv1";
    public static final String SSL_VERSION = "zookeeper.ssl.version";
    public static final String SSL_KEYSTORE_LOCATION = "zookeeper.ssl.keyStore.location";
    public static final String SSL_KEYSTORE_PASSWD = "zookeeper.ssl.keyStore.password";
    public static final String SSL_TRUSTSTORE_LOCATION = "zookeeper.ssl.trustStore.location";
    public static final String SSL_TRUSTSTORE_PASSWD = "zookeeper.ssl.trustStore.password";
    public static final String SSL_AUTHPROVIDER = "zookeeper.ssl.authProvider";
    public static final String SSL_TRUSTSTORE_CA_ALIAS =
            "zookeeper.ssl.trustStore.rootCA.alias";

    public static SSLContext createSSLContext() throws SSLContextException {
        KeyManager[] keyManagers = null;
        TrustManager[] trustManagers = null;

        String keyStoreLocationProp = System.getProperty(SSL_KEYSTORE_LOCATION);
        String keyStorePasswordProp = System.getProperty(SSL_KEYSTORE_PASSWD);

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

        String trustStoreLocationProp = System.getProperty(SSL_TRUSTSTORE_LOCATION);
        String trustStorePasswordProp = System.getProperty(SSL_TRUSTSTORE_PASSWD);

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
                        createTrustManager(trustStoreLocationProp, trustStorePasswordProp)};
            } catch (TrustManagerException e) {
                throw new SSLContextException("Failed to create KeyManager", e);
            }
        }

        String sslVersion = System.getProperty(SSL_VERSION);
        if (sslVersion == null) {
            sslVersion = SSL_VERSION_DEFAULT;
        }
        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance(sslVersion);
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

    public static X509TrustManager createTrustManager(String trustStoreLocation, String trustStorePassword)
            throws TrustManagerException {
        FileInputStream inputStream = null;
        try {
            char[] trustStorePasswordChars = trustStorePassword.toCharArray();
            File trustStoreFile = new File(trustStoreLocation);
            KeyStore ts = KeyStore.getInstance("JKS");
            inputStream = new FileInputStream(trustStoreFile);
            ts.load(inputStream, trustStorePasswordChars);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ts);

            // XXX: Lets create Root CA base verification if root CA alias is
            // present
            String trustStoreCAAlias = System.getProperty(
                    SSL_TRUSTSTORE_CA_ALIAS);
            if (trustStoreCAAlias != null) {
                X509Certificate rootCA =
                        getCertWithAlias(ts, trustStoreCAAlias);
                if (rootCA == null) {
                    final String str = "failed to find root CA from: " +
                            trustStoreLocation + " with alias: " +
                            trustStoreCAAlias;
                    LOG.error(str);
                    throw new TrustManagerException(str);
                }

                return createTrustManager(rootCA);
            }

            LOG.info("No root CA using standard TrustManager");
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
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

    private static X509TrustManager createTrustManager(
            final X509Certificate rootCA) {
        return new ZKX509TrustManager(rootCA);
    }

    private static X509Certificate getCertWithAlias(
            final KeyStore trustStore, final String alias)
            throws KeyStoreException {
        X509Certificate cert;
        try {
            cert = (X509Certificate) trustStore.getCertificate(alias);
        } catch (KeyStoreException exp) {
            LOG.error("failed to load CA cert, exp: " + exp);
            throw exp;
        }

        return cert;
    }
}