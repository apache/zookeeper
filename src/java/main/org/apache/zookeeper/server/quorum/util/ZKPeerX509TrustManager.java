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

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.common.ZKConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TrustManager used by client to verify that the server it is connecting to
 * is the same as the given digest provided in the connection string provided
 * to it.
 */
public class ZKPeerX509TrustManager extends X509ExtendedTrustManager {
    private static final Logger LOG
            = LoggerFactory.getLogger(ZKPeerX509TrustManager.class);

    private final ZKConfig zkConfig;
    private final InetSocketAddress peerAddr;  // TODO: Host verification?
    private final String peerCertFingerPrintStr;

    public ZKPeerX509TrustManager(
            final ZKConfig zkConfig,
            final InetSocketAddress peerAddr,
            final String peerCertFingerPrintStr) {
        this.zkConfig = zkConfig;
        this.peerAddr = peerAddr;
        this.peerCertFingerPrintStr = peerCertFingerPrintStr;
    }

    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }


    @Override
    public void checkClientTrusted(final X509Certificate[] x509Certificates,
                                   final String s) throws CertificateException {
        LOG.error("Not supported!");
        throw new IllegalAccessError("Not Implemented!");
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s) throws CertificateException {
        if (x509Certificates.length == 0) {
            final String errStr = "Invalid server, did not send any cert";
            LOG.error(errStr);
            throw new CertificateException(errStr);
        }

        validatePeerCert(x509Certificates[0]);
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final Socket socket)
            throws CertificateException {
        LOG.error("Not supported!");
        throw new IllegalAccessError("Not Implemented!");
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final Socket socket)
            throws CertificateException {
        if (x509Certificates.length == 0) {
            final String errStr = "Invalid server, did not send any cert";
            LOG.error(errStr);
            throw new CertificateException(errStr);
        }

        validatePeerCert(x509Certificates[0]);
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final SSLEngine sslEngine)
            throws CertificateException {
        LOG.error("Not supported!");
        throw new IllegalAccessError("Not Implemented!");
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final SSLEngine sslEngine)
            throws CertificateException {
        if (x509Certificates.length == 0) {
            final String errStr = "Invalid server, did not send any cert";
            LOG.error(errStr);
            throw new CertificateException(errStr);
        }

        validatePeerCert(x509Certificates[0]);
    }

    private void validatePeerCert(final X509Certificate cert)
            throws CertificateException {
        // Verify that server presented a self-signed cert.
        X509Util.verifySelfSigned(cert);

        try {
            X509Util.validateCert(zkConfig, peerCertFingerPrintStr, cert);
        } catch (NoSuchAlgorithmException exp) {
            throw new CertificateException(exp);
        }
    }
}
