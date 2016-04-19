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

import org.apache.zookeeper.common.X509Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class ZKPeerX509TrustManager implements X509TrustManager {
    private static final Logger LOG
            = LoggerFactory.getLogger(ZKPeerX509TrustManager.class);

    private final InetSocketAddress peerAddr;  // TODO: Host verification?
    private final MessageDigest peerCertFingerPrint;

    public ZKPeerX509TrustManager(
            final InetSocketAddress peerAddr,
            final MessageDigest peerCertFingerPrint) {
        this.peerAddr = peerAddr;
        this.peerCertFingerPrint = peerCertFingerPrint;
    }

    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[]{};
    }

    public void checkClientTrusted(
            final X509Certificate[] certs, final String authType)
            throws CertificateException {
        LOG.error("Not supported!");
        throw new IllegalAccessError("Not Implemented!");
    }

    public void checkServerTrusted(
            final X509Certificate[] certs, final String authType)
            throws CertificateException {
        if (certs.length == 0) {
            final String errStr = "Invalid server, did not send any cert";
            LOG.error(errStr);
            throw new CertificateException(errStr);
        }

        validatePeerCert(certs[0]);
    }

    private void validatePeerCert(final X509Certificate cert)
            throws CertificateException {
        // Verify that server presented a self-signed cert.
        X509Util.verifySelfSigned(cert);

        try {
            X509Util.validateCert(peerCertFingerPrint, cert);
        } catch (NoSuchAlgorithmException exp) {
            throw new CertificateException(exp);
        }
    }
}
