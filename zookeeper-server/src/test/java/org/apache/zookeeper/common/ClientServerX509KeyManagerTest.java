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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.net.Socket;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ClientServerX509KeyManagerTest {

    private static KeyPair clientKeyPair;
    private static X509Certificate clientCert;
    private static KeyPair serverKeyPair;
    private static X509Certificate serverCert;

    @BeforeAll
    public static void setUp() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPair caKeyPair = X509TestHelpers.generateKeyPair(X509KeyType.RSA);
        X509Certificate caCert = X509TestHelpers.newSelfSignedCACert(
                new org.bouncycastle.asn1.x500.X500NameBuilder(org.bouncycastle.asn1.x500.style.BCStyle.INSTANCE)
                        .addRDN(org.bouncycastle.asn1.x500.style.BCStyle.CN, "Test CA")
                        .build(),
                caKeyPair, 86400000L);
        clientKeyPair = X509TestHelpers.generateKeyPair(X509KeyType.RSA);
        clientCert = X509TestHelpers.newClientOnlyCert(caCert, caKeyPair, "client", clientKeyPair.getPublic());
        serverKeyPair = X509TestHelpers.generateKeyPair(X509KeyType.RSA);
        serverCert = X509TestHelpers.newServerOnlyCert(caCert, caKeyPair, "server", serverKeyPair.getPublic());
    }

    @AfterAll
    public static void tearDown() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
    }

    @Test
    public void testChooseClientAliasDelegatesToClientKeyManager() {
        X509KeyManager clientKm = new StubKeyManager("clientAlias", clientCert, clientKeyPair.getPrivate());
        X509KeyManager serverKm = new StubKeyManager("serverAlias", serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        String alias = keyManager.chooseClientAlias(new String[]{"RSA"}, null, null);
        assertNotNull(alias);
        assertEquals("client:clientAlias", alias);
    }

    @Test
    public void testChooseServerAliasDelegatesToServerKeyManager() {
        X509KeyManager clientKm = new StubKeyManager("clientAlias", clientCert, clientKeyPair.getPrivate());
        X509KeyManager serverKm = new StubKeyManager("serverAlias", serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        String alias = keyManager.chooseServerAlias("RSA", null, null);
        assertNotNull(alias);
        assertEquals("server:serverAlias", alias);
    }

    @Test
    public void testGetCertificateChainRoutesToClientManager() {
        X509KeyManager clientKm = new StubKeyManager("myAlias", clientCert, clientKeyPair.getPrivate());
        X509KeyManager serverKm = new StubKeyManager("myAlias", serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        X509Certificate[] chain = keyManager.getCertificateChain("client:myAlias");
        assertNotNull(chain);
        assertEquals(1, chain.length);
        assertEquals(clientCert, chain[0]);
    }

    @Test
    public void testGetCertificateChainRoutesToServerManager() {
        X509KeyManager clientKm = new StubKeyManager("myAlias", clientCert, clientKeyPair.getPrivate());
        X509KeyManager serverKm = new StubKeyManager("myAlias", serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        X509Certificate[] chain = keyManager.getCertificateChain("server:myAlias");
        assertNotNull(chain);
        assertEquals(1, chain.length);
        assertEquals(serverCert, chain[0]);
    }

    @Test
    public void testGetPrivateKeyRoutesToClientManager() {
        X509KeyManager clientKm = new StubKeyManager("myAlias", clientCert, clientKeyPair.getPrivate());
        X509KeyManager serverKm = new StubKeyManager("myAlias", serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        PrivateKey key = keyManager.getPrivateKey("client:myAlias");
        assertEquals(clientKeyPair.getPrivate(), key);
    }

    @Test
    public void testGetPrivateKeyRoutesToServerManager() {
        X509KeyManager clientKm = new StubKeyManager("myAlias", clientCert, clientKeyPair.getPrivate());
        X509KeyManager serverKm = new StubKeyManager("myAlias", serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        PrivateKey key = keyManager.getPrivateKey("server:myAlias");
        assertEquals(serverKeyPair.getPrivate(), key);
    }

    @Test
    public void testGetClientAliasesPrefixed() {
        X509KeyManager clientKm = new StubKeyManager("a1", clientCert, clientKeyPair.getPrivate());
        X509KeyManager serverKm = new StubKeyManager("a2", serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        String[] aliases = keyManager.getClientAliases("RSA", null);
        assertNotNull(aliases);
        assertEquals(1, aliases.length);
        assertEquals("client:a1", aliases[0]);
    }

    @Test
    public void testGetServerAliasesPrefixed() {
        X509KeyManager clientKm = new StubKeyManager("a1", clientCert, clientKeyPair.getPrivate());
        X509KeyManager serverKm = new StubKeyManager("a2", serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        String[] aliases = keyManager.getServerAliases("RSA", null);
        assertNotNull(aliases);
        assertEquals(1, aliases.length);
        assertEquals("server:a2", aliases[0]);
    }

    @Test
    public void testNullAliasReturnsNull() {
        X509KeyManager clientKm = new StubKeyManager(null, clientCert, clientKeyPair.getPrivate());
        X509KeyManager serverKm = new StubKeyManager(null, serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        assertNull(keyManager.chooseClientAlias(new String[]{"RSA"}, null, null));
        assertNull(keyManager.chooseServerAlias("RSA", null, null));
        assertNull(keyManager.getCertificateChain(null));
        assertNull(keyManager.getPrivateKey(null));
    }

    @Test
    public void testUnprefixedAliasFallsBackToServerManager() {
        X509KeyManager clientKm = new StubKeyManager("myAlias", clientCert, clientKeyPair.getPrivate());
        X509KeyManager serverKm = new StubKeyManager("myAlias", serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        X509Certificate[] chain = keyManager.getCertificateChain("myAlias");
        assertNotNull(chain);
        assertEquals(serverCert, chain[0]);

        PrivateKey key = keyManager.getPrivateKey("myAlias");
        assertEquals(serverKeyPair.getPrivate(), key);
    }

    @Test
    public void testChooseEngineClientAliasDelegatesToExtendedKeyManager() {
        ExtendedStubKeyManager clientKm = new ExtendedStubKeyManager("engineClient", clientCert, clientKeyPair.getPrivate());
        X509KeyManager serverKm = new StubKeyManager("serverAlias", serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        String alias = keyManager.chooseEngineClientAlias(new String[]{"RSA"}, null, null);
        assertNotNull(alias);
        assertEquals("client:engineClient", alias);
    }

    @Test
    public void testChooseEngineServerAliasDelegatesToExtendedKeyManager() {
        X509KeyManager clientKm = new StubKeyManager("clientAlias", clientCert, clientKeyPair.getPrivate());
        ExtendedStubKeyManager serverKm = new ExtendedStubKeyManager("engineServer", serverCert, serverKeyPair.getPrivate());
        ClientServerX509KeyManager keyManager = new ClientServerX509KeyManager(clientKm, serverKm);

        String alias = keyManager.chooseEngineServerAlias("RSA", null, null);
        assertNotNull(alias);
        assertEquals("server:engineServer", alias);
    }

    private static class StubKeyManager implements X509KeyManager {
        private final String alias;
        private final X509Certificate cert;
        private final PrivateKey key;

        StubKeyManager(String alias, X509Certificate cert, PrivateKey key) {
            this.alias = alias;
            this.cert = cert;
            this.key = key;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return alias != null ? new String[]{alias} : null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return alias;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return alias != null ? new String[]{alias} : null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return alias;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            if (this.alias != null && this.alias.equals(alias)) {
                return new X509Certificate[]{cert};
            }
            return null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            if (this.alias != null && this.alias.equals(alias)) {
                return key;
            }
            return null;
        }
    }

    private static class ExtendedStubKeyManager extends X509ExtendedKeyManager {
        private final String alias;
        private final X509Certificate cert;
        private final PrivateKey key;

        ExtendedStubKeyManager(String alias, X509Certificate cert, PrivateKey key) {
            this.alias = alias;
            this.cert = cert;
            this.key = key;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return alias != null ? new String[]{alias} : null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return alias;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return alias;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return alias != null ? new String[]{alias} : null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return alias;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return alias;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            if (this.alias != null && this.alias.equals(alias)) {
                return new X509Certificate[]{cert};
            }
            return null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            if (this.alias != null && this.alias.equals(alias)) {
                return key;
            }
            return null;
        }
    }
}
