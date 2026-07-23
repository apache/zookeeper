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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ssl.Ca;
import org.apache.zookeeper.common.ssl.Cert;
import org.apache.zookeeper.server.embedded.ExitHandler;
import org.apache.zookeeper.server.embedded.ZooKeeperServerEmbedded;
import org.apache.zookeeper.test.ClientBase;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.burningwave.tools.net.HostResolutionRequestInterceptor;
import org.burningwave.tools.net.MappedHostResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class SSLHostnameVerificationTest {
    @BeforeAll
    public static void setupDNSMocks() {
        Map<String, String> hostAliases = new LinkedHashMap<>();

        // avoid resolving "localhost" to ipv6 address "::1"
        hostAliases.put("localhost", "127.0.0.1");
        HostResolutionRequestInterceptor.INSTANCE.install(new MappedHostResolver(hostAliases));
        HostResolutionRequestInterceptor.INSTANCE.clearCache();
    }

    @AfterAll
    public static void clearDNSMocks() {
        HostResolutionRequestInterceptor.INSTANCE.uninstall();
    }

    @BeforeAll
    public static void setup() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @AfterAll
    public static void cleanup() {
        Security.removeProvider("BC");
    }

    Watcher.Event.KeeperState checkConnectState(String connectString, ZKClientConfig clientConfig) throws Exception {
        Duration timeout = Duration.ofSeconds(1);
        Watcher.Event.KeeperState state;
        CompletableFuture<WatchedEvent> future = new CompletableFuture<>();
        try (ZooKeeper zk = new ZooKeeper(connectString, (int) timeout.toMillis(), future::complete, clientConfig)) {
            try {
                WatchedEvent event = future.get(timeout.toMillis() * 2, TimeUnit.MILLISECONDS);
                state = event.getState();
            } catch (TimeoutException ignored) {
                // See: ZOOKEEPER-4508, ZOOKEEPER-4921, ZOOKEEPER-4923
                state = Watcher.Event.KeeperState.Expired;
            }
        }
        return state;
    }

    @ParameterizedTest(name = "{0}, fips-mode: {1}")
    @CsvSource({
            "localhost, true",
            "localhost, false",
            "127.0.0.1, true",
            "127.0.0.1, false",
    })
    public void testClientHostnameVerificationWithMismatchNames(String serverHost, boolean fipsEnabled, @TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            // given: server with cert mismatching cn/dns/ip
            Cert server1Cert = ca.signer("abc0").withDnsName("abc1").withIpAddress("192.168.0.10").sign();
            Properties config = server1Cert.buildServerProperties(ca);
            config.put("ssl.hostnameVerification", "false");
            config.put("secureClientPortAddress", serverHost);

            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                // server ready
                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign("client");
                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);
                clientConfig.setProperty("zookeeper.sasl.client", "false");
                clientConfig.setProperty("zookeeper.fips-mode", Boolean.toString(fipsEnabled));
                clientConfig.setProperty("zookeeper.ssl.hostnameVerification", "true");

                // when: connect using mismatched dns/ip
                String connectionString =  server.getSecureConnectionString();
                // then: connection rejected by us as no matching name
                assertEquals(Watcher.Event.KeeperState.Expired, checkConnectState(server.getSecureConnectionString(), clientConfig));
            }
        }
    }

    @Test
    public void testClientHostnameVerificationWithMatchingCnName(@TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            // given: server cert with cn name "localhost"
            Cert server1Cert = ca.signer("localhost").sign();
            Properties config = server1Cert.buildServerProperties(ca);
            config.put("ssl.hostnameVerification", "false");

            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                // server ready
                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign("client");
                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);
                clientConfig.setProperty("zookeeper.sasl.client", "false");
                clientConfig.setProperty("zookeeper.ssl.hostnameVerification", "true");
                clientConfig.setProperty("zookeeper.fips-mode", "false");

                // when: connect using matching dns
                String connectionString =  "localhost:" + server.getSecureClientPort();
                // then: connected as there is no other sans
                assertEquals(Watcher.Event.KeeperState.SyncConnected, checkConnectState(connectionString, clientConfig));
            }
        }
    }

    @Test
    public void testClientHostnameVerificationWithMatchingReversedDnsName(@TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            // given: server cert with cn name "localhost"
            Cert server1Cert = ca.signer("localhost").sign();
            Properties config = server1Cert.buildServerProperties(ca);
            config.put("ssl.hostnameVerification", "false");

            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                // server ready
                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign("client");
                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);
                clientConfig.setProperty("zookeeper.sasl.client", "false");
                clientConfig.setProperty("zookeeper.ssl.hostnameVerification", "true");
                clientConfig.setProperty("zookeeper.fips-mode", "false");

                // when: connect using matching reversed dns
                String connectionString =  "127.0.0.1:" + server.getSecureClientPort();
                clientConfig.setProperty("zookeeper.ssl.allowReverseDnsLookup", "true");
                // then: connected as there is no other sans
                assertEquals(Watcher.Event.KeeperState.SyncConnected, checkConnectState(connectionString, clientConfig));
            }
        }
    }


    @Test
    public void testClientHostnameVerificationWithMatchingDisabledReversedDnsName(@TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            // given: server cert with cn name "localhost"
            Cert server1Cert = ca.signer("localhost").sign();
            Properties config = server1Cert.buildServerProperties(ca);
            config.put("ssl.hostnameVerification", "false");

            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                // server ready
                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign("client");
                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);
                clientConfig.setProperty("zookeeper.sasl.client", "false");
                clientConfig.setProperty("zookeeper.ssl.hostnameVerification", "true");
                clientConfig.setProperty("zookeeper.fips-mode", "false");

                // when: connect using matching reversed dns
                String connectionString =  "127.0.0.1:" + server.getSecureClientPort();
                clientConfig.setProperty("zookeeper.ssl.allowReverseDnsLookup", "false");
                // then: connected as there is no other sans
                assertEquals(Watcher.Event.KeeperState.Expired, checkConnectState(connectionString, clientConfig));
            }
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1"})
    public void testClientHostnameVerificationWithMatchingCnNameButMismatchingSan(String serverHost, @TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            // given: server with cert matching cn
            Cert server1Cert = ca.signer("localhost").withDnsName("abc1").withIpAddress("192.168.0.10").sign();
            Properties config = server1Cert.buildServerProperties(ca);
            config.put("ssl.hostnameVerification", "false");

            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                // server ready
                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign("client");
                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);
                clientConfig.setProperty("zookeeper.ssl.hostnameVerification", "true");
                clientConfig.setProperty("zookeeper.sasl.client", "false");
                clientConfig.setProperty("zookeeper.ssl.allowReverseDnsLookup", "true");
                clientConfig.setProperty("zookeeper.fips-mode", "false");

                // when: connect with dns or ip resolved to cn name
                String connectionString = String.format("%s:%d", serverHost, server.getSecureClientPort());

                // then: fail to connect
                //
                // CN matching has been deprecated by rfc2818 and can be used
                // as fallback only when no subjectAlts are available
                assertEquals(Watcher.Event.KeeperState.Expired, checkConnectState(connectionString, clientConfig));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1"})
    public void testClientHostnameVerificationWithMatchingIpAddress(String serverHost, @TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            // given: server with cert mismatching ip
            Cert server1Cert = ca.signer("abc0").withDnsName("abc1").withIpAddress("127.0.0.1").sign();
            Properties config = server1Cert.buildServerProperties(ca);
            config.put("ssl.hostnameVerification", "false");

            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                // server ready
                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign("client");
                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);
                clientConfig.setProperty("zookeeper.ssl.hostnameVerification", "true");
                clientConfig.setProperty("zookeeper.sasl.client", "false");
                clientConfig.setProperty("zookeeper.fips-mode", "false");

                // when: connect with matching ip or its dns
                String connectionString = String.format("%s:%d", serverHost, server.getSecureClientPort());

                // then: connected
                assertEquals(Watcher.Event.KeeperState.SyncConnected, checkConnectState(connectionString, clientConfig));
            }
        }
    }

    @Test
    public void testClientHostnameVerificationFipsModeWithIpAddress(@TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            // given: server with cert mismatching ip
            Cert server1Cert = ca.signer("abc0").withDnsName("abc1").withIpAddress("127.0.0.1").sign();
            Properties config = server1Cert.buildServerProperties(ca);
            config.put("ssl.hostnameVerification", "false");

            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                // server ready
                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign("client");
                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);
                clientConfig.setProperty("zookeeper.ssl.hostnameVerification", "true");
                clientConfig.setProperty("zookeeper.sasl.client", "false");
                clientConfig.setProperty("zookeeper.fips-mode", "true");

                // when: connect with ip's dns
                String connectionString = String.format("localhost:%d", server.getSecureClientPort());
                // then: rejected as fips-mode don't do dns lookup
                assertEquals(Watcher.Event.KeeperState.Expired, checkConnectState(connectionString, clientConfig));

                // when: connect with ip address
                connectionString = String.format("127.0.0.1:%d", server.getSecureClientPort());
                // then: connected
                assertEquals(Watcher.Event.KeeperState.SyncConnected, checkConnectState(connectionString, clientConfig));
            }
        }
    }

    @Test
    public void testClientHostnameVerificationFipsModeWithDns(@TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            // given: server cert with dns "localhost"
            Cert server1Cert = ca.signer("abc0").withDnsName("localhost").sign();
            Properties config = server1Cert.buildServerProperties(ca);
            config.put("ssl.hostnameVerification", "false");

            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                // server ready
                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign("client");
                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);
                clientConfig.setProperty("zookeeper.ssl.hostnameVerification", "true");
                clientConfig.setProperty("zookeeper.sasl.client", "false");
                clientConfig.setProperty("zookeeper.fips-mode", "true");

                // when: connect with ip address
                String connectionString = String.format("127.0.0.1:%d", server.getSecureClientPort());
                // then: fail as fips-mode won't do reverse dns lookup
                assertEquals(Watcher.Event.KeeperState.Expired, checkConnectState(connectionString, clientConfig));

                // when: connect with "localhost"
                connectionString = String.format("localhost:%d", server.getSecureClientPort());
                // then: succeed
                assertEquals(Watcher.Event.KeeperState.SyncConnected, checkConnectState(connectionString, clientConfig));
            }
        }
    }

    @Test
    public void testClientHostnameVerificationWithMatchingDnsName(@TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            // given: server cert with dns name "localhost"
            Cert server1Cert = ca.signer("abc0").withDnsName("localhost").withIpAddress("192.168.0.10").sign();
            Properties config = server1Cert.buildServerProperties(ca);
            config.put("ssl.hostnameVerification", "false");

            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                // server ready
                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                Cert clientCert = ca.sign("client");
                ZKClientConfig clientConfig = clientCert.buildClientConfig(ca);
                clientConfig.setProperty("zookeeper.sasl.client", "false");
                clientConfig.setProperty("zookeeper.fips-mode", "false");
                clientConfig.setProperty("zookeeper.ssl.hostnameVerification", "true");
                clientConfig.setProperty("zookeeper.ssl.allowReverseDnsLookup", "true");

                // when: connect to "127.0.0.1"
                String connectionString = String.format("127.0.0.1:%d", server.getSecureClientPort());
                // then: connected as ZKHostnameVerifier will do reverse dns lookup
                assertEquals(Watcher.Event.KeeperState.SyncConnected, checkConnectState(connectionString, clientConfig));

                // when: connect to "localhost"
                connectionString = String.format("localhost:%d", server.getSecureClientPort());
                // then: connected as dns match
                assertEquals(Watcher.Event.KeeperState.SyncConnected, checkConnectState(connectionString, clientConfig));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"x509", ""})
    public void testServerHostnameVerification(String authProvider, @TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            Cert cert = ca.sign("server");

            // given: server with client hostname verification enabled
            Properties config = cert.buildServerProperties(ca);
            config.put("ssl.hostnameVerification", "true");
            config.put("ssl.clientHostnameVerification", "true");
            config.put("ssl.allowReverseDnsLookup", "false");
            config.put("fips-mode", "false");
            if (!authProvider.isEmpty()) {
                config.put("ssl.authProvider", "x509");
            }

            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                // server ready
                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                // // when: connect with matching dns name
                Cert client1Cert = ca.signer("client1").withDnsName("localhost").sign();
                ZKClientConfig client1Config = client1Cert.buildClientConfig(ca);
                client1Config.setProperty("zookeeper.ssl.hostnameVerification", "false");

                // then: connected
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client1Config));

                // when: connect with matching ip address
                Cert client2Cert = ca.signer("client2").withIpAddress("127.0.0.1").sign();
                ZKClientConfig client2Config = client2Cert.buildClientConfig(ca);
                client2Config.setProperty("zookeeper.ssl.hostnameVerification", "false");

                // then: connected
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client2Config));

                // when: connect with matching cn name
                Cert client3Cert = ca.signer("localhost").sign();
                ZKClientConfig client3Config = client3Cert.buildClientConfig(ca);
                client3Config.setProperty("zookeeper.ssl.hostnameVerification", "false");

                // then: connected
                assertTrue(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client3Config));

                // when: connect with mismatching cert name
                Cert client4Cert = ca.signer("client4").withDnsName("abc").sign();
                ZKClientConfig client4Config = client4Cert.buildClientConfig(ca);
                client4Config.setProperty("zookeeper.ssl.hostnameVerification", "false");

                // then: fail to connect
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client4Config));
            }
        }
    }

    /**
     * FIPS mode disallow custom trust manager so server has no way to validate against client's endpoint.
     */
    @ParameterizedTest
    @ValueSource(strings = {"x509", ""})
    public void testServerHostnameVerificationFipsMode(String authProvider, @TempDir Path tmpDir) throws Exception {
        try (Ca ca = Ca.create(tmpDir)) {
            Cert cert = ca.sign("server");

            Properties config = cert.buildServerProperties(ca);

            // given: server in fips mode with client hostname verification enabled
            config.put("ssl.hostnameVerification", "true");
            config.put("ssl.clientHostnameVerification", "true");
            config.put("fips-mode", "true");

            if (!authProvider.isEmpty()) {
                config.put("ssl.authProvider", "x509");
            }

            try (ZooKeeperServerEmbedded server = ZooKeeperServerEmbedded
                    .builder()
                    .baseDir(Files.createTempDirectory(tmpDir, "server.data"))
                    .configuration(config)
                    .exitHandler(ExitHandler.LOG_ONLY)
                    .build()) {
                server.start();

                // server ready
                assertTrue(ClientBase.waitForServerUp(server.getConnectionString(), 60000));

                // // when: connect with matching dns name
                Cert client1Cert = ca.signer("localhost").withResolvedDns("localhost").sign();
                ZKClientConfig client1Config = client1Cert.buildClientConfig(ca);
                client1Config.setProperty("zookeeper.ssl.hostnameVerification", "false");

                // then: fail to connect
                assertFalse(ClientBase.waitForServerUp(server.getSecureConnectionString(), 6000, true, client1Config));
            }
        }
    }
}
