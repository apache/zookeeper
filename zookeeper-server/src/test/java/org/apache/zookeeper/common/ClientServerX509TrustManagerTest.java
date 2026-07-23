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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.Socket;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ClientServerX509TrustManagerTest {

    private static X509Certificate clientCaCert;
    private static X509Certificate clientCert;

    private static X509Certificate serverCaCert;
    private static X509Certificate serverCert;

    @BeforeAll
    public static void setUp() throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        KeyPair clientCaKeyPair = X509TestHelpers.generateKeyPair(X509KeyType.RSA);
        clientCaCert = X509TestHelpers.newSelfSignedCACert(
                new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "Client CA").build(),
                clientCaKeyPair, 86400000L);

        KeyPair clientKeyPair = X509TestHelpers.generateKeyPair(X509KeyType.RSA);
        clientCert = X509TestHelpers.newClientOnlyCert(clientCaCert, clientCaKeyPair, "client", clientKeyPair.getPublic());

        KeyPair serverCaKeyPair = X509TestHelpers.generateKeyPair(X509KeyType.RSA);
        serverCaCert = X509TestHelpers.newSelfSignedCACert(
                new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "Server CA").build(),
                serverCaKeyPair, 86400000L);

        KeyPair serverKeyPair = X509TestHelpers.generateKeyPair(X509KeyType.RSA);
        serverCert = X509TestHelpers.newServerOnlyCert(serverCaCert, serverCaKeyPair, "server", serverKeyPair.getPublic());
    }

    @AfterAll
    public static void tearDown() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
    }

    @Test
    public void testCheckServerTrustedDelegatesToClientTrustManager() {
        StubTrustManager clientTm = new StubTrustManager(serverCaCert);
        StubTrustManager serverTm = new StubTrustManager(clientCaCert);
        ClientServerX509TrustManager trustManager = new ClientServerX509TrustManager(clientTm, serverTm);

        assertDoesNotThrow(() -> trustManager.checkServerTrusted(
                new X509Certificate[]{serverCert, serverCaCert}, "RSA"));
        assertTrue(clientTm.checkServerTrustedCalled);
    }

    @Test
    public void testCheckClientTrustedDelegatesToServerTrustManager() {
        StubTrustManager clientTm = new StubTrustManager(serverCaCert);
        StubTrustManager serverTm = new StubTrustManager(clientCaCert);
        ClientServerX509TrustManager trustManager = new ClientServerX509TrustManager(clientTm, serverTm);

        assertDoesNotThrow(() -> trustManager.checkClientTrusted(
                new X509Certificate[]{clientCert, clientCaCert}, "RSA"));
        assertTrue(serverTm.checkClientTrustedCalled);
    }

    @Test
    public void testCheckServerTrustedFailsWithWrongTrustManager() {
        StubTrustManager clientTm = new StubTrustManager(clientCaCert);
        StubTrustManager serverTm = new StubTrustManager(serverCaCert);
        ClientServerX509TrustManager trustManager = new ClientServerX509TrustManager(clientTm, serverTm);

        assertThrows(CertificateException.class, () -> trustManager.checkServerTrusted(
                new X509Certificate[]{serverCert, serverCaCert}, "RSA"));
    }

    @Test
    public void testCheckClientTrustedFailsWithWrongTrustManager() {
        StubTrustManager clientTm = new StubTrustManager(clientCaCert);
        StubTrustManager serverTm = new StubTrustManager(serverCaCert);
        ClientServerX509TrustManager trustManager = new ClientServerX509TrustManager(clientTm, serverTm);

        assertThrows(CertificateException.class, () -> trustManager.checkClientTrusted(
                new X509Certificate[]{clientCert, clientCaCert}, "RSA"));
    }

    @Test
    public void testCheckServerTrustedWithSocket() {
        StubTrustManager clientTm = new StubTrustManager(serverCaCert);
        StubTrustManager serverTm = new StubTrustManager(clientCaCert);
        ClientServerX509TrustManager trustManager = new ClientServerX509TrustManager(clientTm, serverTm);

        assertDoesNotThrow(() -> trustManager.checkServerTrusted(
                new X509Certificate[]{serverCert, serverCaCert}, "RSA", (Socket) null));
        assertTrue(clientTm.checkServerTrustedSocketCalled);
    }

    @Test
    public void testCheckClientTrustedWithSocket() {
        StubTrustManager clientTm = new StubTrustManager(serverCaCert);
        StubTrustManager serverTm = new StubTrustManager(clientCaCert);
        ClientServerX509TrustManager trustManager = new ClientServerX509TrustManager(clientTm, serverTm);

        assertDoesNotThrow(() -> trustManager.checkClientTrusted(
                new X509Certificate[]{clientCert, clientCaCert}, "RSA", (Socket) null));
        assertTrue(serverTm.checkClientTrustedSocketCalled);
    }

    @Test
    public void testCheckServerTrustedWithEngine() {
        StubTrustManager clientTm = new StubTrustManager(serverCaCert);
        StubTrustManager serverTm = new StubTrustManager(clientCaCert);
        ClientServerX509TrustManager trustManager = new ClientServerX509TrustManager(clientTm, serverTm);

        assertDoesNotThrow(() -> trustManager.checkServerTrusted(
                new X509Certificate[]{serverCert, serverCaCert}, "RSA", (SSLEngine) null));
        assertTrue(clientTm.checkServerTrustedEngineCalled);
    }

    @Test
    public void testCheckClientTrustedWithEngine() {
        StubTrustManager clientTm = new StubTrustManager(serverCaCert);
        StubTrustManager serverTm = new StubTrustManager(clientCaCert);
        ClientServerX509TrustManager trustManager = new ClientServerX509TrustManager(clientTm, serverTm);

        assertDoesNotThrow(() -> trustManager.checkClientTrusted(
                new X509Certificate[]{clientCert, clientCaCert}, "RSA", (SSLEngine) null));
        assertTrue(serverTm.checkClientTrustedEngineCalled);
    }

    @Test
    public void testGetAcceptedIssuersReturnsCombined() {
        StubTrustManager clientTm = new StubTrustManager(serverCaCert);
        StubTrustManager serverTm = new StubTrustManager(clientCaCert);
        ClientServerX509TrustManager trustManager = new ClientServerX509TrustManager(clientTm, serverTm);

        X509Certificate[] issuers = trustManager.getAcceptedIssuers();
        assertEquals(2, issuers.length);
        assertEquals(serverCaCert, issuers[0]);
        assertEquals(clientCaCert, issuers[1]);
    }

    private static class StubTrustManager extends X509ExtendedTrustManager {
        private final X509Certificate trustedCa;
        boolean checkClientTrustedCalled;
        boolean checkServerTrustedCalled;
        boolean checkClientTrustedSocketCalled;
        boolean checkServerTrustedSocketCalled;
        boolean checkClientTrustedEngineCalled;
        boolean checkServerTrustedEngineCalled;

        StubTrustManager(X509Certificate trustedCa) {
            this.trustedCa = trustedCa;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            checkClientTrustedCalled = true;
            verifyChain(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            checkServerTrustedCalled = true;
            verifyChain(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            checkClientTrustedSocketCalled = true;
            verifyChain(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            checkServerTrustedSocketCalled = true;
            verifyChain(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            checkClientTrustedEngineCalled = true;
            verifyChain(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            checkServerTrustedEngineCalled = true;
            verifyChain(chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[]{trustedCa};
        }

        private void verifyChain(X509Certificate[] chain) throws CertificateException {
            for (X509Certificate cert : chain) {
                if (cert.equals(trustedCa)) {
                    return;
                }
                try {
                    cert.verify(trustedCa.getPublicKey());
                    return;
                } catch (Exception e) {
                    // continue checking
                }
            }
            throw new CertificateException("Certificate chain not trusted");
        }
    }
}
