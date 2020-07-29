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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SSLAuthTest extends ClientBase {

    private ClientX509Util clientX509Util;

    public static ClientX509Util setUpSecure() throws Exception {
        ClientX509Util x509Util = new ClientX509Util();
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
        System.setProperty(x509Util.getSslAuthProviderProperty(), "x509");
        System.setProperty(x509Util.getSslKeystoreLocationProperty(), testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(x509Util.getSslKeystorePasswdProperty(), "testpass");
        System.setProperty(x509Util.getSslTruststoreLocationProperty(), testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(x509Util.getSslTruststorePasswdProperty(), "testpass");
        System.setProperty("javax.net.debug", "ssl");
        System.setProperty("zookeeper.authProvider.x509", "org.apache.zookeeper.server.auth.X509AuthenticationProvider");
        return x509Util;
    }

    public static void clearSecureSetting(ClientX509Util clientX509Util) {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
        System.clearProperty(ZKClientConfig.SECURE_CLIENT);
        System.clearProperty(clientX509Util.getSslAuthProviderProperty());
        System.clearProperty(clientX509Util.getSslKeystoreLocationProperty());
        System.clearProperty(clientX509Util.getSslKeystorePasswdProperty());
        System.clearProperty(clientX509Util.getSslTruststoreLocationProperty());
        System.clearProperty(clientX509Util.getSslTruststorePasswdProperty());
        System.clearProperty("javax.net.debug");
        System.clearProperty("zookeeper.authProvider.x509");
        clientX509Util.close();
    }

    @BeforeEach
    public void setUp() throws Exception {
        clientX509Util = setUpSecure();

        String host = "localhost";
        int port = PortAssignment.unique();
        hostPort = host + ":" + port;

        serverFactory = ServerCnxnFactory.createFactory();
        serverFactory.configure(new InetSocketAddress(host, port), maxCnxns, -1, true);

        super.setUp();
    }

    @AfterEach
    public void teardown() throws Exception {
        clearSecureSetting(clientX509Util);
    }

    @Test
    public void testRejection() throws Exception {
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");

        // Replace trusted keys with a valid key that is not trusted by the server
        System.setProperty(clientX509Util.getSslKeystoreLocationProperty(), testDataPath
                                                                                    + "/ssl/testUntrustedKeyStore.jks");
        System.setProperty(clientX509Util.getSslKeystorePasswdProperty(), "testpass");

        CountdownWatcher watcher = new CountdownWatcher();

        // Handshake will take place, and then X509AuthenticationProvider should reject the untrusted cert
        new TestableZooKeeper(hostPort, CONNECTION_TIMEOUT, watcher);
        assertFalse(watcher.clientConnected.await(1000, TimeUnit.MILLISECONDS), "Untrusted certificate should not result in successful connection");
    }

    @Test
    public void testMisconfiguration() throws Exception {
        System.clearProperty(clientX509Util.getSslAuthProviderProperty());
        System.clearProperty(clientX509Util.getSslKeystoreLocationProperty());
        System.clearProperty(clientX509Util.getSslKeystorePasswdProperty());
        System.clearProperty(clientX509Util.getSslTruststoreLocationProperty());
        System.clearProperty(clientX509Util.getSslTruststorePasswdProperty());

        CountdownWatcher watcher = new CountdownWatcher();
        new TestableZooKeeper(hostPort, CONNECTION_TIMEOUT, watcher);
        assertFalse(watcher.clientConnected.await(1000, TimeUnit.MILLISECONDS), "Missing SSL configuration should not result in successful connection");
    }

}
