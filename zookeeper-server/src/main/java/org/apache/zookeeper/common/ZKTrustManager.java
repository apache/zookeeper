/*
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

import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.X509ExtendedTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A custom TrustManager that supports hostname verification via org.apache.http.conn.ssl.DefaultHostnameVerifier.
 *
 * We attempt to perform verification using just the IP address first and if that fails will attempt to perform a
 * reverse DNS lookup and verify using the hostname.
 */
public class ZKTrustManager extends X509ExtendedTrustManager {

    private static final Logger LOG = LoggerFactory.getLogger(ZKTrustManager.class);

    private final X509ExtendedTrustManager x509ExtendedTrustManager;
    private final boolean serverHostnameVerificationEnabled;
    private final boolean clientHostnameVerificationEnabled;

    private final ZKHostnameVerifier hostnameVerifier;

    /**
     * Instantiate a new ZKTrustManager.
     *
     * @param x509ExtendedTrustManager The trustmanager to use for checkClientTrusted/checkServerTrusted logic
     * @param serverHostnameVerificationEnabled  If true, this TrustManager should verify hostnames of servers that this
     *                                 instance connects to.
     * @param clientHostnameVerificationEnabled  If true, the hostname of a client connecting to this machine will be
     *                                           verified.
     */
    ZKTrustManager(
        X509ExtendedTrustManager x509ExtendedTrustManager,
        boolean serverHostnameVerificationEnabled,
        boolean clientHostnameVerificationEnabled) {
        this(x509ExtendedTrustManager,
                serverHostnameVerificationEnabled,
                clientHostnameVerificationEnabled,
                new ZKHostnameVerifier());
    }

    ZKTrustManager(
            X509ExtendedTrustManager x509ExtendedTrustManager,
            boolean serverHostnameVerificationEnabled,
            boolean clientHostnameVerificationEnabled,
            ZKHostnameVerifier hostnameVerifier) {
        this.x509ExtendedTrustManager = x509ExtendedTrustManager;
        this.serverHostnameVerificationEnabled = serverHostnameVerificationEnabled;
        this.clientHostnameVerificationEnabled = clientHostnameVerificationEnabled;
        this.hostnameVerifier = hostnameVerifier;
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return x509ExtendedTrustManager.getAcceptedIssuers();
    }

    @Override
    public void checkClientTrusted(
        X509Certificate[] chain,
        String authType,
        Socket socket) throws CertificateException {
        x509ExtendedTrustManager.checkClientTrusted(chain, authType, socket);
        if (clientHostnameVerificationEnabled) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Check client trusted socket.getInetAddress(): {}, {}", socket.getInetAddress(), socket);
            }
            performHostVerification(socket.getInetAddress(), chain[0]);
        }
    }

    @Override
    public void checkServerTrusted(
        X509Certificate[] chain,
        String authType,
        Socket socket) throws CertificateException {
        x509ExtendedTrustManager.checkServerTrusted(chain, authType, socket);
        if (serverHostnameVerificationEnabled) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Check server trusted socket.getInetAddress(): {}, {}", socket.getInetAddress(), socket);
            }
            performHostVerification(socket.getInetAddress(), chain[0]);
        }
    }

    @Override
    public void checkClientTrusted(
        X509Certificate[] chain,
        String authType,
        SSLEngine engine) throws CertificateException {
        x509ExtendedTrustManager.checkClientTrusted(chain, authType, engine);
        if (clientHostnameVerificationEnabled) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Check client trusted engine.getPeerHost(): {}, {}", engine.getPeerHost(), engine);
                }
                performHostVerification(InetAddress.getByName(engine.getPeerHost()), chain[0]);
            } catch (UnknownHostException e) {
                throw new CertificateException("Failed to verify host", e);
            }
        }
    }

    @Override
    public void checkServerTrusted(
        X509Certificate[] chain,
        String authType,
        SSLEngine engine
                                  ) throws CertificateException {
        x509ExtendedTrustManager.checkServerTrusted(chain, authType, engine);
        if (serverHostnameVerificationEnabled) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Check server trusted engine.getPeerHost(): {}, {}", engine.getPeerHost(), engine);
                }
                performHostVerification(InetAddress.getByName(engine.getPeerHost()), chain[0]);
            } catch (UnknownHostException e) {
                throw new CertificateException("Failed to verify host", e);
            }
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        x509ExtendedTrustManager.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        x509ExtendedTrustManager.checkServerTrusted(chain, authType);
    }

    /**
     * Compares peer's hostname with the one stored in the provided certificate. Performs verification
     * with the help of provided HostnameVerifier.
     *
     * Attempts to verify the IP address first, if it fails, check the hostname. Performs reverse DNS lookup
     * if hostname is not available. (Mostly the case in client verifications.)
     *
     * @param inetAddress Peer's inet address.
     * @param certificate Peer's certificate
     * @throws CertificateException Thrown if the provided certificate doesn't match the peer hostname.
     */
    private void performHostVerification(
        InetAddress inetAddress,
        X509Certificate certificate
    ) throws CertificateException {
        String hostAddress = "";
        String hostName = "";
        try {
            hostAddress = inetAddress.getHostAddress();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Trying to verify host address first: {}", hostAddress);
            }
            hostnameVerifier.verify(hostAddress, certificate);
        } catch (SSLException addressVerificationException) {
            try {
                hostName = inetAddress.getHostName();
                if (LOG.isDebugEnabled()) {
                    LOG.debug(
                        "Failed to verify host address: {}, trying to verify host name: {}",
                        hostAddress, hostName);
                }
                hostnameVerifier.verify(hostName, certificate);
            } catch (SSLException hostnameVerificationException) {
                LOG.error("Failed to verify host address: {}", hostAddress, addressVerificationException);
                LOG.error("Failed to verify hostname: {}", hostName, hostnameVerificationException);
                throw new CertificateException("Failed to verify both host address and host name", hostnameVerificationException);
            }
        }
    }
}
