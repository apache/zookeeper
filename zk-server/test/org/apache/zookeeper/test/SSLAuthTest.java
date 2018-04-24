/**
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
package org.apache.zookeeper.test;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SSLAuthTest extends ClientBase {
    @Before
    public void setUp() throws Exception {
        String testDataPath = System.getProperty("test.data.dir", "build/test/data");
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
        System.setProperty(ZKConfig.SSL_AUTHPROVIDER, "x509");
        System.setProperty(ZKConfig.SSL_KEYSTORE_LOCATION, testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(ZKConfig.SSL_KEYSTORE_PASSWD, "testpass");
        System.setProperty(ZKConfig.SSL_TRUSTSTORE_LOCATION, testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(ZKConfig.SSL_TRUSTSTORE_PASSWD, "testpass");
        System.setProperty("javax.net.debug", "ssl");
        System.setProperty("zookeeper.authProvider.x509", "org.apache.zookeeper.server.auth.X509AuthenticationProvider");

        String host = "localhost";
        int port = PortAssignment.unique();
        hostPort = host + ":" + port;

        serverFactory = ServerCnxnFactory.createFactory();
        serverFactory.configure(new InetSocketAddress(host, port), maxCnxns, true);

        super.setUp();
    }

    @After
    public void teardown() throws Exception {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
        System.clearProperty(ZKClientConfig.SECURE_CLIENT);
        System.clearProperty(ZKConfig.SSL_AUTHPROVIDER);
        System.clearProperty(ZKConfig.SSL_KEYSTORE_LOCATION);
        System.clearProperty(ZKConfig.SSL_KEYSTORE_PASSWD);
        System.clearProperty(ZKConfig.SSL_TRUSTSTORE_LOCATION);
        System.clearProperty(ZKConfig.SSL_TRUSTSTORE_PASSWD);
        System.clearProperty("javax.net.debug");
        System.clearProperty("zookeeper.authProvider.x509");
    }

    @Test
    public void testRejection() throws Exception {
        String testDataPath = System.getProperty("test.data.dir", "build/test/data");

        // Replace trusted keys with a valid key that is not trusted by the server
        System.setProperty(ZKConfig.SSL_KEYSTORE_LOCATION, testDataPath + "/ssl/testUntrustedKeyStore.jks");
        System.setProperty(ZKConfig.SSL_KEYSTORE_PASSWD, "testpass");

        CountdownWatcher watcher = new CountdownWatcher();

        // Handshake will take place, and then X509AuthenticationProvider should reject the untrusted cert
        new TestableZooKeeper(hostPort, CONNECTION_TIMEOUT, watcher);
        Assert.assertFalse("Untrusted certificate should not result in successful connection",
                watcher.clientConnected.await(1000, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testMisconfiguration() throws Exception {
        System.clearProperty(ZKConfig.SSL_AUTHPROVIDER);
        System.clearProperty(ZKConfig.SSL_KEYSTORE_LOCATION);
        System.clearProperty(ZKConfig.SSL_KEYSTORE_PASSWD);
        System.clearProperty(ZKConfig.SSL_TRUSTSTORE_LOCATION);
        System.clearProperty(ZKConfig.SSL_TRUSTSTORE_PASSWD);

        CountdownWatcher watcher = new CountdownWatcher();
        new TestableZooKeeper(hostPort, CONNECTION_TIMEOUT, watcher);
        Assert.assertFalse("Missing SSL configuration should not result in successful connection",
                watcher.clientConnected.await(1000, TimeUnit.MILLISECONDS));
    }
}