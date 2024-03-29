/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 *
 */

package org.apache.zookeeper.test;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.SecretUtilsTest;
import org.apache.zookeeper.server.NettyServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class ClientSSLTest extends QuorumPeerTestBase {

    private ClientX509Util clientX509Util;

    public static Stream<Arguments> positiveTestData() {
        ArrayList<Arguments> result = new ArrayList<>();
        for (SslProvider sslProvider : SslProvider.values()) {
            for (String fipsEnabled : new String[] { "true", "false" }) {
                for (String hostnameverification : new String[] { "true", "false" }) {
                    result.add(Arguments.of(sslProvider, fipsEnabled, hostnameverification));
                }
            }
        }
        return result.stream();
    }

    public static Stream<Arguments> negativeTestData() {
        ArrayList<Arguments> result = new ArrayList<>();
        for (SslProvider sslProvider : SslProvider.values()) {
            for (String fipsEnabled : new String[] { "true", "false" }) {
                result.add(Arguments.of(sslProvider, fipsEnabled));
            }
        }
        return result.stream();
    }

    @BeforeEach
    public void setup() {
        System.setProperty(NettyServerCnxnFactory.PORT_UNIFICATION_KEY, Boolean.TRUE.toString());
        clientX509Util = new ClientX509Util();
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
        System.setProperty(clientX509Util.getSslKeystoreLocationProperty(), testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(clientX509Util.getSslKeystorePasswdProperty(), "testpass");
        System.setProperty(clientX509Util.getSslTruststoreLocationProperty(), testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(clientX509Util.getSslTruststorePasswdProperty(), "testpass");
    }

    @AfterEach
    public void teardown() {
        System.clearProperty(NettyServerCnxnFactory.PORT_UNIFICATION_KEY);
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
        System.clearProperty(ZKClientConfig.SECURE_CLIENT);
        System.clearProperty(clientX509Util.getSslKeystoreLocationProperty());
        System.clearProperty(clientX509Util.getSslKeystorePasswdProperty());
        System.clearProperty(clientX509Util.getSslKeystorePasswdPathProperty());
        System.clearProperty(clientX509Util.getSslTruststoreLocationProperty());
        System.clearProperty(clientX509Util.getSslTruststorePasswdProperty());
        System.clearProperty(clientX509Util.getSslTruststorePasswdPathProperty());
        System.clearProperty(clientX509Util.getFipsModeProperty());
        System.clearProperty(clientX509Util.getSslHostnameVerificationEnabledProperty());
        System.clearProperty(clientX509Util.getSslProviderProperty());
        clientX509Util.close();
    }

    /**
     * This test checks that client SSL connections work in the absence of a
     * secure port when port unification is set up for the plaintext port.
     *
     * This single client port will be tested for handling both plaintext
     * and SSL traffic.
     */
    @Test
    public void testClientServerUnifiedPort() throws Exception {
        testClientServerSSL(false);
    }

    @Test
    public void testClientServerUnifiedPortWithCnxnClassName() throws Exception {
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "ClientCnxnSocketNIO");
        testClientServerSSL(false);
    }

    @Test
    public void testClientServerSSLWithCnxnClassName() throws Exception {
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "ClientCnxnSocketNetty");
        testClientServerSSL(true);
    }

    /**
     * This test checks that client - server SSL works in cluster setup of ZK servers, which includes:
     * 1. setting "secureClientPort" in "zoo.cfg" file.
     * 2. setting jvm flags for serverCnxn, keystore, truststore.
     * Finally, a zookeeper client should be able to connect to the secure port and
     * communicate with server via secure connection.
     * <p/>
     * Note that in this test a ZK server has two ports -- clientPort and secureClientPort.
     * <p/>
     * This test covers the positive scenarios for hostname verification.
     */
    @ParameterizedTest(name = "sslProvider={0}, fipsEnabled={1}, hostnameVerification={2}")
    @MethodSource("positiveTestData")
    public void testClientServerSSL_positive(SslProvider sslProvider, String fipsEnabled, String hostnameVerification) throws Exception {
        //Skipping this test for s390x arch as netty-tc-native is not supported
        assumeFalse(System.getProperty("os.arch").contains("s390x"), " Skipping for s390x arch as netty-tcnative is not yet supported.");
        // Arrange
        System.setProperty(clientX509Util.getSslProviderProperty(), sslProvider.toString());
        System.setProperty(clientX509Util.getFipsModeProperty(), fipsEnabled);
        System.setProperty(clientX509Util.getSslHostnameVerificationEnabledProperty(), hostnameVerification);

        // Act & Assert
        testClientServerSSL(hostnameVerification.equals("true") ? "localhost" : InetAddress.getLocalHost().getHostName(),
            true, CONNECTION_TIMEOUT);
    }

    /**
     * This test covers the negative scenarios for hostname verification.
     */
    @ParameterizedTest(name = "sslProvider={0}, fipsEnabled={1}")
    @MethodSource("negativeTestData")
    public void testClientServerSSL_negative(SslProvider sslProvider, boolean fipsEnabled) {
        // Arrange
        System.setProperty(clientX509Util.getSslProviderProperty(), sslProvider.toString());
        System.setProperty(clientX509Util.getFipsModeProperty(), Boolean.toString(fipsEnabled));
        System.setProperty(clientX509Util.getSslHostnameVerificationEnabledProperty(), "true");

        // Act & Assert
        assertThrows(AssertionError.class, () ->
            testClientServerSSL(InetAddress.getLocalHost().getHostName(), true, 5000));
    }

    @Test
    public void testClientServerSSL_withPasswordFromFile() throws Exception {
        final Path secretFile = SecretUtilsTest.createSecretFile("testpass");

        System.clearProperty(clientX509Util.getSslKeystorePasswdProperty());
        System.setProperty(clientX509Util.getSslKeystorePasswdPathProperty(), secretFile.toString());

        System.clearProperty(clientX509Util.getSslTruststorePasswdProperty());
        System.setProperty(clientX509Util.getSslTruststorePasswdPathProperty(), secretFile.toString());

        testClientServerSSL(true);
    }

    public void testClientServerSSL(boolean useSecurePort) throws Exception {
        testClientServerSSL("localhost", useSecurePort, CONNECTION_TIMEOUT);
    }

    public void testClientServerSSL(String hostname, boolean useSecurePort, long connectTimeout) throws Exception {
        final int SERVER_COUNT = 3;
        final int[] clientPorts = new int[SERVER_COUNT];
        final Integer[] secureClientPorts = new Integer[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            secureClientPorts[i] = PortAssignment.unique();
            String server = String.format("server.%d=127.0.0.1:%d:%d:participant;127.0.0.1:%d%n", i, PortAssignment.unique(), PortAssignment.unique(), clientPorts[i]);
            sb.append(server);
        }
        String quorumCfg = sb.toString();

        MainThread[] mt = new MainThread[SERVER_COUNT];
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (useSecurePort) {
                mt[i] = new MainThread(i, quorumCfg, secureClientPorts[i], true);
            } else {
                mt[i] = new MainThread(i, quorumCfg, true);
            }
            mt[i].start();
        }

        // Add some timing margin for the quorum to elect a leader
        // (without this margin, timeouts have been observed in parallel test runs)
        ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[0], 2 * TIMEOUT);

        // Servers have been set up. Now go test if secure connection is successful.
        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], TIMEOUT),
                    "waiting for server " + i + " being up");
            final int port = useSecurePort ? secureClientPorts[i] : clientPorts[i];
            try (ZooKeeper zk = ClientBase.createZKClient(hostname + ":" + port, TIMEOUT, connectTimeout)) {
                // Do a simple operation to make sure the connection is fine.
                zk.create("/test", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                zk.delete("/test", -1);
            }
        }

        for (int i = 0; i < mt.length; i++) {
            mt[i].shutdown();
        }
    }

    /**
     * Developers might use standalone mode (which is the default for one server).
     * This test checks SSL works in standalone mode of ZK server.
     * <p>
     * Note that in this test the Zk server has only secureClientPort
     */
    @Test
    public void testSecureStandaloneServer() throws Exception {
        Integer secureClientPort = PortAssignment.unique();
        MainThread mt = new MainThread(MainThread.UNSET_MYID, "", secureClientPort, false);
        mt.start();

        ZooKeeper zk = ClientBase.createZKClient("127.0.0.1:" + secureClientPort, TIMEOUT);
        zk.create("/test", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.delete("/test", -1);
        zk.close();
        mt.shutdown();
    }

    @Test
    public void testSecureStandaloneServerAuthFail() throws IOException {
        try {
            System.setProperty(ProviderRegistry.AUTHPROVIDER_PROPERTY_PREFIX + "authfail",
                AuthFailX509AuthenticationProvider.class.getName());
            System.setProperty(clientX509Util.getSslAuthProviderProperty(), "authfail");

            Integer secureClientPort = PortAssignment.unique();
            MainThread mt = new MainThread(MainThread.UNSET_MYID, "", secureClientPort, false);
            mt.start();

            AssertionError ex = assertThrows("Client should not able to connect when authentication fails", AssertionError.class,
                () -> {
                    ClientBase.createZKClient("localhost:" + secureClientPort, TIMEOUT, 3000);
                });
            assertThat("Exception message does not match (different exception caught?)",
                ex.getMessage(), startsWith("ZooKeeper client can not connect to"));
        } finally {
            System.clearProperty(ProviderRegistry.AUTHPROVIDER_PROPERTY_PREFIX + "authfail");
            System.clearProperty(clientX509Util.getSslAuthProviderProperty());
        }
    }
}
