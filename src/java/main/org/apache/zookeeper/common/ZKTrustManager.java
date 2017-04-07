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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * A custom TrustManager that supports hostname verification via org.apache.http.conn.ssl.DefaultHostnameVerifier.
 *
 * We attempt to perform verification using just the IP address first and if that fails will attempt to perform a
 * reverse DNS lookup and verify using the hostname.
 */
public class ZKTrustManager extends X509ExtendedTrustManager {

    private static final Logger LOG = LoggerFactory.getLogger(ZKTrustManager.class);

    private X509ExtendedTrustManager x509ExtendedTrustManager;
    private boolean hostnameVerificationEnabled;
    private boolean shouldVerifyClientHostname;

    private DefaultHostnameVerifier hostnameVerifier;

    /**
     * Instantiate a new ZKTrustManager.
     *
     * @param x509ExtendedTrustManager    The trustmanager to use for checkClientTrusted/checkServerTrusted logic
     * @param hostnameVerificationEnabled If true, this TrustManager should verify hostnames.
     * @param shouldVerifyClientHostname  If true, and hostnameVerificationEnabled is true, the hostname of a client
     *                                    connecting to this machine will be verified in addition to the servers that this
     *                                    instance connects to. If false, and hostnameVerificationEnabled is true, only
     *                                    the hostnames of servers that this instance connects to will be verified. If
     *                                    hostnameVerificationEnabled is false, this argument is ignored.
     */
    public ZKTrustManager(X509ExtendedTrustManager x509ExtendedTrustManager, boolean hostnameVerificationEnabled, boolean shouldVerifyClientHostname) {
        this.x509ExtendedTrustManager = x509ExtendedTrustManager;
        this.hostnameVerificationEnabled = hostnameVerificationEnabled;
        this.shouldVerifyClientHostname = shouldVerifyClientHostname;

        hostnameVerifier = new DefaultHostnameVerifier();
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return x509ExtendedTrustManager.getAcceptedIssuers();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        if (hostnameVerificationEnabled && shouldVerifyClientHostname) {
            performHostVerification(socket.getInetAddress(), chain[0]);
        }
        x509ExtendedTrustManager.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        if (hostnameVerificationEnabled) {
            performHostVerification(socket.getInetAddress(), chain[0]);
        }
        x509ExtendedTrustManager.checkServerTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        if (hostnameVerificationEnabled && shouldVerifyClientHostname) {
            try {
                performHostVerification(InetAddress.getByName(engine.getPeerHost()), chain[0]);
            } catch (UnknownHostException e) {
                throw new CertificateException("failed to verify host", e);
            }
        }
        x509ExtendedTrustManager.checkServerTrusted(chain, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        if (hostnameVerificationEnabled) {
            try {
                performHostVerification(InetAddress.getByName(engine.getPeerHost()), chain[0]);
            } catch (UnknownHostException e) {
                throw new CertificateException("failed to verify host", e);
            }
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

    private void performHostVerification(InetAddress inetAddress, X509Certificate certificate) throws CertificateException {
        try {
            hostnameVerifier.verify(inetAddress.getHostAddress(), certificate);
        } catch (SSLException addressVerificationException) {
            try {
                LOG.debug("Failed to verify host address, attempting to verify host name with reverse dns lookup", addressVerificationException);
                hostnameVerifier.verify(inetAddress.getHostName(), certificate);
            } catch (SSLException hostnameVerificationException) {
                LOG.error("Failed to verify host address", addressVerificationException);
                LOG.error("Failed to verify hostname", hostnameVerificationException);
                throw new CertificateException("Failed to verify both host address and host name", hostnameVerificationException);
            }
        }
    }
}
