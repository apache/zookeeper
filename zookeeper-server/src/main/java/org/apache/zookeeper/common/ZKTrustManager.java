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
    private boolean serverHostnameVerificationEnabled;
    private boolean clientHostnameVerificationEnabled;

    private ZKHostnameVerifier hostnameVerifier;

    /**
     * Instantiate a new ZKTrustManager.
     *
     * @param x509ExtendedTrustManager The trustmanager to use for checkClientTrusted/checkServerTrusted logic
     * @param serverHostnameVerificationEnabled  If true, this TrustManager should verify hostnames of servers that this
     *                                 instance connects to.
     * @param clientHostnameVerificationEnabled  If true, the hostname of a client connecting to this machine will be
     *                                           verified.
     */
    ZKTrustManager(X509ExtendedTrustManager x509ExtendedTrustManager, boolean serverHostnameVerificationEnabled,
                   boolean clientHostnameVerificationEnabled) {
        this.x509ExtendedTrustManager = x509ExtendedTrustManager;
        this.serverHostnameVerificationEnabled = serverHostnameVerificationEnabled;
        this.clientHostnameVerificationEnabled = clientHostnameVerificationEnabled;
        hostnameVerifier = new ZKHostnameVerifier();
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return x509ExtendedTrustManager.getAcceptedIssuers();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        x509ExtendedTrustManager.checkClientTrusted(chain, authType, socket);
        if (clientHostnameVerificationEnabled) {
            performHostVerification(socket.getInetAddress(), chain[0]);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        x509ExtendedTrustManager.checkServerTrusted(chain, authType, socket);
        if (serverHostnameVerificationEnabled) {
            performHostVerification(socket.getInetAddress(), chain[0]);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        x509ExtendedTrustManager.checkClientTrusted(chain, authType, engine);
        if (clientHostnameVerificationEnabled) {
            try {
                performHostVerification(InetAddress.getByName(engine.getPeerHost()), chain[0]);
            } catch (UnknownHostException e) {
                throw new CertificateException("Failed to verify host", e);
            }
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        x509ExtendedTrustManager.checkServerTrusted(chain, authType, engine);
        if (serverHostnameVerificationEnabled) {
            try {
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
     * Compares peer's hostname with the one stored in the provided client certificate. Performs verification
     * with the help of provided HostnameVerifier.
     *
     * @param inetAddress Peer's inet address.
     * @param certificate Peer's certificate
     * @throws CertificateException Thrown if the provided certificate doesn't match the peer hostname.
     */
    private void performHostVerification(InetAddress inetAddress, X509Certificate certificate)
            throws CertificateException {
        String hostAddress = "";
        String hostName = "";
        try {
            hostAddress = inetAddress.getHostAddress();
            hostnameVerifier.verify(hostAddress, certificate);
        } catch (SSLException addressVerificationException) {
            try {
                LOG.debug("Failed to verify host address: {} attempting to verify host name with reverse dns lookup",
                        hostAddress, addressVerificationException);
                hostName = inetAddress.getHostName();
                hostnameVerifier.verify(hostName, certificate);
            } catch (SSLException hostnameVerificationException) {
                LOG.error("Failed to verify host address: {}", hostAddress, addressVerificationException);
                LOG.error("Failed to verify hostname: {}", hostName, hostnameVerificationException);
                throw new CertificateException("Failed to verify both host address and host name",
                        hostnameVerificationException);
            }
        }
    }
}
