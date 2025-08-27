/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.Properties;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ssl.Ca;
import org.apache.zookeeper.common.ssl.Cert;
import org.apache.zookeeper.server.embedded.ExitHandler;
import org.apache.zookeeper.server.embedded.ZooKeeperServerEmbedded;
import org.apache.zookeeper.test.ClientBase;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class ClientSSLRevocationTest {
    @BeforeEach
    public void setup() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
    }

    @AfterEach
    public void cleanup() throws Exception {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);

        Security.setProperty("ocsp.enable", "false");
        System.clearProperty("com.sun.net.ssl.checkRevocation");
        System.clearProperty("zookeeper.ssl.crl");
        System.clearProperty("zookeeper.ssl.ocsp");
    }

    @Test
    public void testRevocationDisabled(@TempDir Path tmpDir) throws Exception {
        // given: crl not enabled
        try (Ca ca = Ca.builder(tmpDir).withOcsp().build()) {
            Cert serverCert = ca.sign_with_ocsp("server");
            final Properties config = serverCert.buildServerProperties(ca);
            // given: revoked server cert
            ca.revoke_through_ocsp(serverCert.cert);
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                Cert client1Cert = ca.sign_with_crldp("client1");
                ca.revoke_through_crldp(client1Cert);

                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                // when: connect with revoked cert.
                // then: connected
                ZKClientConfig client1Config = client1Cert.buildClientConfig(ca);
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client1Config));
            }
        }
    }

    @ParameterizedTest(name = "clientRevoked = {0}")
    @ValueSource(booleans = {true, false})
    public void testRevocationInClientUsingCrldp(boolean clientRevoked, @TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            // given: server cert with crldp
            Cert server1Cert = ca.sign_with_crldp("server1");
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(server1Cert.buildServerProperties(ca))
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign_with_crldp("client1");
                if (clientRevoked) {
                    // crl in server side is disabled, so it does not matter whether
                    // client cert is revoked or not.
                    ca.revoke_through_crldp(clientCert);
                }

                // then: ssl authentication succeed when crl is disabled
                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, clientConfig));

                // when: valid server cert
                // then: ssl authentication succeed when crl is enabled
                clientConfig.setProperty("zookeeper.ssl.crl", "true");
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, clientConfig));
            }

            // crldp check is not realtime, so we have to start a new server with revoked cert

            // given: revoked server cert with crldp
            Cert server2Cert = ca.sign_with_crldp("server2");
            ca.revoke_through_crldp(server2Cert);
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server2.data"))
                    .configuration(server2Cert.buildServerProperties(ca))
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign_with_crldp("client1");
                if (clientRevoked) {
                    // crl in server side is disabled, so it does not matter whether
                    // client cert is revoked or not.
                    ca.revoke_through_crldp(clientCert);
                }

                // then: ssl authentication succeed when crl is disabled
                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, clientConfig));

                // then: ssl authentication failed when crl is enabled
                clientConfig.setProperty("zookeeper.ssl.crl", "true");
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, clientConfig));
            }
        }
    }

    @ParameterizedTest(name = "clientRevoked = {0}")
    @ValueSource(booleans = {true, false})
    public void testRevocationInClientUsingOCSP(boolean clientRevoked, @TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.builder(tmpDir).withOcsp().build()) {
            // given: server cert with ocsp
            Cert serverCert = ca.sign_with_ocsp("server1");
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(serverCert.buildServerProperties(ca))
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign_with_ocsp("client");
                if (clientRevoked) {
                    // crl in server side is disabled, so it does not matter whether
                    // client cert is revoked or not.
                    ca.revoke_through_ocsp(clientCert.cert);
                }

                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);

                // when: connect to serve with valid cert
                // then: connected
                //
                // we can't config crl using jvm properties as server will access them also
                // see: https://issues.apache.org/jira/browse/ZOOKEEPER-4875
                clientConfig.setProperty("zookeeper.ssl.crl", "true");
                clientConfig.setProperty("zookeeper.ssl.ocsp", "true");
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, clientConfig));

                // when: server cert get revoked
                ca.revoke_through_ocsp(serverCert.cert);

                // then: ssl authentication failed when crl is enabled
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, clientConfig));

                // then: ssl authentication succeed when crl is disabled
                clientConfig.setProperty("zookeeper.ssl.crl", "false");
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, clientConfig));
            }
        }
    }

    @ParameterizedTest(name = "serverRevoked = {0}")
    @ValueSource(booleans = {true, false})
    public void testRevocationInServerUsingCrldp(boolean serverRevoked, @TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            // given: server with crl enabled
            System.setProperty("zookeeper.ssl.crl", "true");
            Cert serverCert = ca.sign_with_crldp("server1");
            if (serverRevoked) {
                // crl in client side will be disabled, so it does not matter whether
                // server cert is revoked or not.
                ca.revoke_through_crldp(serverCert);
            }
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(serverCert.buildServerProperties(ca))
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                // when: valid client cert with crldp
                // then: ssl authentication failed when crl is enabled
                Cert client1Cert = ca.sign_with_crldp("client1");
                ZKClientConfig client1Config = client1Cert.buildClientConfig(ca);
                client1Config.setProperty("zookeeper.ssl.crl", "false");
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client1Config));

                Cert client2Cert = ca.sign_with_crldp("client2");
                ca.revoke_through_crldp(client2Cert);

                // when: revoked client cert with crldp
                // then: ssl authentication failed when crl is enabled
                ZKClientConfig client2Config = client2Cert.buildClientConfig(ca);
                client2Config.setProperty("zookeeper.ssl.crl", "false");
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client2Config));
            }
        }
    }

    @ParameterizedTest(name = "serverRevoked = {0}")
    @ValueSource(booleans = {true, false})
    public void testRevocationInServerUsingOCSP(boolean serverRevoked, @TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.builder(tmpDir).withOcsp().build()) {
            // given: server with crl and ocsp enabled
            System.setProperty("com.sun.net.ssl.checkRevocation", "true");
            System.setProperty("zookeeper.ssl.ocsp", "true");
            Cert serverCert = ca.sign("server1");
            if (serverRevoked) {
                // crl in client side will be disabled, so it does not matter whether
                // server cert is revoked or not.
                ca.revoke_through_ocsp(serverCert.cert);
            }
            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(serverCert.buildServerProperties(ca))
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                // when: valid client cert with crldp
                // then: ssl authentication failed when crl is enabled
                Cert client1Cert = ca.sign_with_ocsp("client1");
                ZKClientConfig client1Config = client1Cert.buildClientConfig(ca);
                client1Config.setProperty("zookeeper.ssl.crl", "false");
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client1Config));

                // ocsp is realtime, so we can reuse this client.
                ca.revoke_through_ocsp(client1Cert.cert);
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client1Config));
            }
        }
    }
}
