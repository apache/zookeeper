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
package org.apache.zookeeper.server.quorum.util;

import java.io.IOException;
import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Given a TrustStore load all the certificate chains in it and verify
 * the given certificate chain. This will be used by all modules if a
 * TrustStore is indeed available.
 * If a TrustStore is unavailable or no certificates are avaiable in
 * TrustStore then verificate will fail.
 */
public class ZKX509TrustManager extends X509ExtendedTrustManager {
    private static final Logger LOG
            = LoggerFactory.getLogger(ZKX509TrustManager.class);
    private final Collection<X509Certificate> trustedCertList;

    public ZKX509TrustManager(final String trustStoreLocation,
                              final String trustStorePassword)
            throws X509Exception.TrustManagerException{
        this.trustedCertList = loadTrustAnchors(trustStoreLocation,
                trustStorePassword);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] x509Certificates,
                                   final String s) throws CertificateException {
        try {
            validateCertChain(x509Certificates);
        } catch (CertificateVerificationException exp) {
            throw new CertificateException(exp);
        }
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s) throws
            CertificateException {
        try {
            validateCertChain(x509Certificates);
        } catch (CertificateVerificationException exp) {
            throw new CertificateException(exp);
        }
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final Socket socket)
            throws CertificateException {
        try {
            validateCertChain(x509Certificates);
        } catch (CertificateVerificationException exp) {
            throw new CertificateException(exp);
        }
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final Socket socket)
            throws CertificateException {
        try {
            validateCertChain(x509Certificates);
        } catch (CertificateVerificationException exp) {
            throw new CertificateException(exp);
        }
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final SSLEngine sslEngine)
            throws CertificateException {
        try {
            validateCertChain(x509Certificates);
        } catch (CertificateVerificationException exp) {
            throw new CertificateException(exp);
        }
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final SSLEngine sslEngine)
            throws CertificateException {
        try {
            validateCertChain(x509Certificates);
        } catch (CertificateVerificationException exp) {
            throw new CertificateException(exp);
        }
    }

    private void validateCertChain(X509Certificate[] certs)
            throws CertificateVerificationException {
        if (trustedCertList == null) {
            throw new CertificateVerificationException("Failed to verify " +
                    "certificate due to lack of truststore.");
        }
        if (trustedCertList.size() == 0) {
            throw new CertificateVerificationException("Failed to verify " +
                    "no trusted certificates provided.");
        }
        X509Certificate clientCert = null;
        Set<X509Certificate> restOfCerts = new HashSet<>();
        for (final X509Certificate cert: certs) {
            // skip self signed cert
            if (CertificateVerifier.isSelfSigned(cert)) {
                continue;
            }

            // first non self signed cert will be client cert?
            // TODO: how do we get the first cert?.
            if (clientCert == null) {
                clientCert = cert;
            } else {
                restOfCerts.add(cert);
            }
        }

        restOfCerts.addAll(trustedCertList);
        CertificateVerifier.verifyCertificate(clientCert, restOfCerts);
    }

    private Collection<X509Certificate> loadTrustAnchors(
            final String storeLocation, final String storePassword)
            throws X509Exception.TrustManagerException {
        if (storeLocation == null) {
            return null;
        }

        if (storePassword == null) {
            final String errStr = "Truststore password is required for the " +
                    "given truststore location: " + storeLocation;
            LOG.error(errStr);
            throw new X509Exception.TrustManagerException(errStr);
        }

        final Collection<X509Certificate> trustedCertList = new ArrayList<>();
        try {
            final KeyStore ts = X509Util.loadKeyStore(storeLocation,
                    storePassword);
            final TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance("SunX509");
            tmf.init(ts);
            for (Enumeration<String> e = ts.aliases(); e.hasMoreElements();) {
                final String alias = e.nextElement();
                for (final Certificate cert : ts.getCertificateChain(alias)) {
                    trustedCertList.add((X509Certificate)cert);
                }
            }
        } catch (IOException | KeyStoreException | NoSuchAlgorithmException
                | CertificateException e) {
            final String errStr = "Could not load truststore: " + storeLocation;
            LOG.error("{}", errStr, e);
            throw new X509Exception.TrustManagerException(errStr, e);
        }
        return trustedCertList;
    }
}
