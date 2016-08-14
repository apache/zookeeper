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
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509KeyManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.zookeeper.common.X509Exception.KeyManagerException;
import static org.apache.zookeeper.common.X509Exception.SSLContextException;

/**
 * Utility code for X509 handling
 */
public class X509Util {
    protected static final Logger LOG = LoggerFactory.getLogger(X509Util.class);

    public static SSLContext createSSLContext(
            final ZKConfig config,
            final X509ExtendedTrustManager trustManager)
        throws SSLContextException {
        final KeyManager[] keyManagers = createKeyManagers(config);
        return createSSLContext(config, keyManagers,
                new TrustManager[]{trustManager});
    }

    protected static KeyManager[] createKeyManagers(final ZKConfig config)
            throws SSLContextException {
        LOG.info("keystore key: " + ZKConfig.SSL_KEYSTORE_LOCATION);
        LOG.info("keystore pwd: " + ZKConfig.SSL_KEYSTORE_PASSWD);

        final String keyStoreLocationProp =
                config.getProperty(ZKConfig.SSL_KEYSTORE_LOCATION);
        final String keyStorePasswordProp =
                config.getProperty(ZKConfig.SSL_KEYSTORE_PASSWD);

        if (keyStoreLocationProp == null && keyStorePasswordProp == null) {
            LOG.warn("keystore not specified for client connection");
            return null;
        } else {
            if (keyStoreLocationProp == null) {
                throw new SSLContextException("keystore location not " +
                        "specified for client connection");
            }
            if (keyStorePasswordProp == null) {
                throw new SSLContextException("keystore password not " +
                        "specified for client connection");
            }
            try {
                return new KeyManager[]{
                        createKeyManager(keyStoreLocationProp,
                                keyStorePasswordProp)};
            } catch (KeyManagerException e) {
                throw new SSLContextException("Failed to create KeyManager", e);
            }
        }
    }


    protected static SSLContext createSSLContext(
            final ZKConfig config,
            final KeyManager[] keyManagers,
            final TrustManager[] trustManagers)
            throws SSLContextException {
        String sslVersion = config.getProperty(ZKConfig.SSL_VERSION);
        if (sslVersion == null) {
            sslVersion = ZKConfig.SSL_VERSION_DEFAULT;
        }
        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance(sslVersion);
            sslContext.init(keyManagers, trustManagers, null);
        } catch (Exception e) {
            throw new SSLContextException(e);
        }
        return sslContext;
    }

    public static X509KeyManager createKeyManager(
            final String keyStoreLocation, final String keyStorePassword)
            throws KeyManagerException {
        try {

            KeyStore ks = loadKeyStore(keyStoreLocation, keyStorePassword);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, keyStorePassword.toCharArray());

            for (KeyManager km : kmf.getKeyManagers()) {
                if (km instanceof X509KeyManager) {
                    return (X509KeyManager) km;
                }
            }
            throw new KeyManagerException("Couldn't find X509KeyManager");

        } catch (KeyStoreException | NoSuchAlgorithmException |
                CertificateException | UnrecoverableKeyException |
                IOException e) {
            throw new KeyManagerException(e);
        }
    }

    public static KeyStore loadKeyStore(final String keyStoreLocation,
                                        final String keyStorePassword)
            throws KeyStoreException, NoSuchAlgorithmException,
            CertificateException, IOException {
        final char[] keyStorePasswordChars = keyStorePassword.toCharArray();
        final File keyStoreFile = new File(keyStoreLocation);
        final KeyStore ks = KeyStore.getInstance("JKS");
        try (final FileInputStream inputStream = new FileInputStream
                (keyStoreFile)) {
            ks.load(inputStream, keyStorePasswordChars);
        }
        return ks;
    }
}
