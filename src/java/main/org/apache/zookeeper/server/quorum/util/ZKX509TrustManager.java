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
import java.security.GeneralSecurityException;
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

import org.apache.zookeeper.common.FatalCertificateException;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Given a TrustStore load all the certificate chains in it and verify
 * the given certificate chain. This will be used by all modules if a
 * TrustStore is indeed available.
 * If a TrustStore is unavailable or no certificates are available in
 * TrustStore then verification will fail but this failure can be considered as
 * not fatal.
 * If a TrustStore has at-least one cert and verification fails then it is
 * considered a fatal failure.
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
        validateCertChain(x509Certificates);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s) throws
            CertificateException {
        validateCertChain(x509Certificates);
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final Socket socket)
            throws CertificateException {
        validateCertChain(x509Certificates);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final Socket socket)
            throws CertificateException {
        validateCertChain(x509Certificates);
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final SSLEngine sslEngine)
            throws CertificateException {
        validateCertChain(x509Certificates);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final SSLEngine sslEngine)
            throws CertificateException {
        validateCertChain(x509Certificates);
    }

    private void validateCertChain(X509Certificate[] certs)
            throws CertificateException {
        if (trustedCertList == null) {
            throw new CertificateException("Failed to verify " +
                    "certificate due to lack of truststore.");
        }
        if (trustedCertList.size() == 0) {
            throw new CertificateException("Failed to verify " +
                    "no trusted certificates provided.");
        }

        final X509Certificate clientCert = certs[0];
        if (CertificateVerifier.isSelfSigned(clientCert)) {
            try {
                CertificateVerifier.verifyCertificate(clientCert,
                        new HashSet<>(trustedCertList),
                        new HashSet<X509Certificate>());
            } catch (GeneralSecurityException exp) {
                final String errStr = "verification with few trust anchors failed" +
                        ". Error: " + exp.getMessage();
                LOG.error("{}", errStr, exp);
                throw new FatalCertificateException(errStr, exp);
            }
            return;
        }

        final Set<X509Certificate> restOfCerts = new HashSet<>();
        for (final X509Certificate cert: certs) {
            // skip self signed cert
            if (CertificateVerifier.isSelfSigned(cert) ||
                    clientCert.equals(cert)) {
                continue;
            }

            restOfCerts.add(cert);
        }

        restOfCerts.addAll(trustedCertList);
        try {
            CertificateVerifier.verifyCertificate(clientCert, restOfCerts);
        } catch (CertificateVerificationException exp) {
            final String errStr = "verification with few trust anchors failed" +
                    ". Error: " + exp.getMessage();
            LOG.error("{}", errStr, exp);
            throw new FatalCertificateException(errStr, exp);
        }
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
                final Certificate[] certs = ts.getCertificateChain(alias);
                if (certs == null) {
                    final Certificate cert = ts.getCertificate(alias);
                    if (cert != null) {
                        trustedCertList.add((X509Certificate) cert);
                    }
                } else {
                    for (final Certificate cert : certs) {
                        trustedCertList.add((X509Certificate) cert);
                    }
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
