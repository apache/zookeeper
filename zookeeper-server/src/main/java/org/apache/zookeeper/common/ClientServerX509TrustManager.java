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

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * An {@link X509ExtendedTrustManager} that delegates to separate trust managers for
 * client and server certificate validation. This allows configuring different truststores
 * for validating client certificates (when acting as a server) vs. server certificates
 * (when acting as a client).
 *
 * <ul>
 *   <li>{@code checkServerTrusted} — delegates to the <b>client trust manager</b>
 *       (validates server certs when this node connects as a client)</li>
 *   <li>{@code checkClientTrusted} — delegates to the <b>server trust manager</b>
 *       (validates client certs when this node accepts connections as a server)</li>
 * </ul>
 */
public class ClientServerX509TrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager clientTrustManager;
    private final X509ExtendedTrustManager serverTrustManager;

    /**
     * @param clientTrustManager used to validate server certificates (when acting as TLS client)
     * @param serverTrustManager used to validate client certificates (when acting as TLS server)
     */
    public ClientServerX509TrustManager(X509ExtendedTrustManager clientTrustManager,
                                        X509ExtendedTrustManager serverTrustManager) {
        this.clientTrustManager = clientTrustManager;
        this.serverTrustManager = serverTrustManager;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        serverTrustManager.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        serverTrustManager.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        serverTrustManager.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        clientTrustManager.checkServerTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        clientTrustManager.checkServerTrusted(chain, authType, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        clientTrustManager.checkServerTrusted(chain, authType, engine);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        X509Certificate[] clientIssuers = clientTrustManager.getAcceptedIssuers();
        X509Certificate[] serverIssuers = serverTrustManager.getAcceptedIssuers();
        List<X509Certificate> combined = new ArrayList<>(clientIssuers.length + serverIssuers.length);
        combined.addAll(Arrays.asList(clientIssuers));
        combined.addAll(Arrays.asList(serverIssuers));
        return combined.toArray(new X509Certificate[0]);
    }
}
