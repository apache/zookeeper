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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.X509TrustManager;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;

public class ZKX509TrustManager implements X509TrustManager {
    private static final Logger LOG
            = LoggerFactory.getLogger(ZKX509TrustManager.class);
    private final X509Certificate rootCACert;

    public ZKX509TrustManager(final X509Certificate rootCACert) {
        this.rootCACert = rootCACert;
    }

    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[] { this.rootCACert };
    }

    public void checkClientTrusted(X509Certificate[] certs,
                                   String authType) throws CertificateException {
        try {
            validateCertChain(certs);
        } catch (CertificateVerificationException exp) {
            throw new CertificateException(exp);
        }
    }

    public void checkServerTrusted(X509Certificate[] certs,
                                   String authType) throws CertificateException {
        try {
            validateCertChain(certs);
        } catch (CertificateVerificationException exp) {
            throw new CertificateException(exp);
        }
    }

    private void validateCertChain(X509Certificate[] certs)
            throws CertificateVerificationException {
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

        restOfCerts.add(rootCACert);
        CertificateVerifier.verifyCertificate(clientCert, restOfCerts);
    }

    private void checkAllCerts(X509Certificate[] certs)
            throws CertificateException {
        for (X509Certificate cert : certs) {
            try {
                cert.verify(rootCACert.getPublicKey());
            } catch (NoSuchAlgorithmException
                    | InvalidKeyException | NoSuchProviderException
                    | SignatureException exp) {
                LOG.error("cert validation failed, exp: " + exp);
                throw new CertificateException(exp);
            }
        }
    }
}
