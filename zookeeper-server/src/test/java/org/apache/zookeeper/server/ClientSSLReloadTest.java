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
package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jline.internal.Log;
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.KeyStoreFileType;
import org.apache.zookeeper.common.X509KeyType;
import org.apache.zookeeper.common.X509TestContext;
import org.apache.zookeeper.server.embedded.ExitHandler;
import org.apache.zookeeper.server.embedded.ZooKeeperServerEmbedded;
import org.apache.zookeeper.server.embedded.ZookeeperServeInfo;
import org.apache.zookeeper.test.ClientBase;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientSSLReloadTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(ClientSSLReloadTest.class);

    private X509TestContext x509TestContext1;
    private X509TestContext x509TestContext2;

    private File dir1;
    private File dir2;

    private File keyStoreFile1;
    private File trustStoreFile1;

    private File keyStoreFile2;
    private File trustStoreFile2;

    @BeforeEach
    public void setup() throws Exception {

        dir1 = ClientBase.createEmptyTestDir();
        dir2 = ClientBase.createEmptyTestDir();

        Security.addProvider(new BouncyCastleProvider());

        x509TestContext1 = X509TestContext.newBuilder()
                .setTempDir(dir1)
                .setKeyStoreKeyType(X509KeyType.EC)
                .setTrustStoreKeyType(X509KeyType.EC)
                .build();

        x509TestContext2 = X509TestContext.newBuilder()
                .setTempDir(dir2)
                .setKeyStoreKeyType(X509KeyType.EC)
                .setTrustStoreKeyType(X509KeyType.EC)
                .build();

        keyStoreFile1 = x509TestContext1.getKeyStoreFile(KeyStoreFileType.PEM);
        trustStoreFile1 = x509TestContext1.getTrustStoreFile(KeyStoreFileType.PEM);

        keyStoreFile2 = x509TestContext2.getKeyStoreFile(KeyStoreFileType.PEM);
        trustStoreFile2 = x509TestContext2.getTrustStoreFile(KeyStoreFileType.PEM);

        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
    }

    @AfterEach
    public void teardown() throws Exception {
        try {
            FileUtils.deleteDirectory(dir1);
            FileUtils.deleteDirectory(dir2);
        } catch (IOException e) {
            // ignore
        }
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
    }

    /*
     * This test performs certificate reload on a ZK server and checks the server presented certificate to the client.
     * 1) setup() creates two sets of certificates to be used by the test.
     * 2) Start the ZK server with TLS configuration ("client.certReload" config property will refresh the key and trust store for ClientX509Util at runtime)
     * 3) ZK client will connect to the server on the secure client port using keyStoreFile1 and trustStoreFile1.
     * 4) Update the keyStoreFile1 and trustStoreFile1 files in the filesystem with keyStoreFile2 and trustStoreFile2.
     * 5) Till FileChangeWatcher thread is triggered & SSLContext options are reset, ZK client should continue to connect.
     *    In Junit tests, FileChangeWatcher thread is not triggered immediately upon certifcate update in the filesystem.
     * 6) Once the certficates are reloaded by the server, ZK client connect will fail.
     * 7) Next, create a new ZK client with updated keystore & truststore paths (keyStoreFile2 and trustStoreFile2).
     * 8) Server should accept the connection on the secure client port.
     */
    @Test
    public void certficateReloadTest() throws Exception {

        final Properties configZookeeper = getServerConfig();
        try (ZooKeeperServerEmbedded zkServer = ZooKeeperServerEmbedded
                .builder()
                .baseDir(dir1.toPath())
                .configuration(configZookeeper)
                .exitHandler(ExitHandler.LOG_ONLY)
                .build()) {
            zkServer.start();
            assertTrue(ClientBase.waitForServerUp(zkServer.getConnectionString(), 60000));
            for (int i = 0; i < 100; i++) {
                ZookeeperServeInfo.ServerInfo status = ZookeeperServeInfo.getStatus("StandaloneServer*");
                if (status.isLeader() && status.isStandaloneMode()) {
                    break;
                }
                Thread.sleep(100);
            }
            ZookeeperServeInfo.ServerInfo status = ZookeeperServeInfo.getStatus("StandaloneServer*");
            assertTrue(status.isLeader());
            assertTrue(status.isStandaloneMode());

            CountDownLatch l = new CountDownLatch(1);
            ZKClientConfig zKClientConfig = getZKClientConfig();
            // ZK client object created which will connect with keyStoreFile1 and trustStoreFile1 to the server.
            try (ZooKeeper zk = new ZooKeeper(zkServer.getSecureConnectionString(), 60000, (WatchedEvent event) -> {
                switch (event.getState()) {
                    case SyncConnected:
                        l.countDown();
                        break;
                }
            }, zKClientConfig)) {
                assertTrue(zk.getClientConfig().getBoolean(ZKClientConfig.SECURE_CLIENT));
                assertTrue(l.await(10, TimeUnit.SECONDS));
            }

            Log.info("Updating keyStore & trustStore files !!!!");
            // Update the keyStoreFile1 and trustStoreFile1 files in the filesystem with keyStoreFile2 & trustStoreFile2
            FileUtils.writeStringToFile(keyStoreFile1, FileUtils.readFileToString(keyStoreFile2, StandardCharsets.US_ASCII), StandardCharsets.US_ASCII, false);
            FileUtils.writeStringToFile(trustStoreFile1, FileUtils.readFileToString(trustStoreFile2, StandardCharsets.US_ASCII), StandardCharsets.US_ASCII, false);

            // Till FileChangeWatcher thread is triggered & SSLContext options are reset, ZK client should continue connecting.
            for (int i = 0; i < 5; i++) {
                CountDownLatch l2 = new CountDownLatch(1);
                Thread.sleep(5000);
                try (ZooKeeper zk = new ZooKeeper(zkServer.getSecureConnectionString(), 60000, (WatchedEvent event) -> {
                    switch (event.getState()) {
                        case SyncConnected:
                            l.countDown();
                            break;
                    }
                }, zKClientConfig)) {
                    if (!l2.await(5, TimeUnit.SECONDS)) {
                        LOG.error("Unable to connect to zk server");
                        break;
                    }
                }
            }
            // Use the updated keyStore and trustStore paths when creating the client; Refreshed server should authenticate the client.
            zKClientConfig.setProperty("zookeeper.ssl.keyStore.location", keyStoreFile2.getAbsolutePath());
            zKClientConfig.setProperty("zookeeper.ssl.trustStore.location", trustStoreFile2.getAbsolutePath());
            zKClientConfig.setProperty("zookeeper.ssl.keyStore.type", "PEM");
            zKClientConfig.setProperty("zookeeper.ssl.trustStore.type", "PEM");
            CountDownLatch l3 = new CountDownLatch(1);
            try (ZooKeeper zk = new ZooKeeper(zkServer.getSecureConnectionString(), 60000, (WatchedEvent event) -> {
                switch (event.getState()) {
                    case SyncConnected:
                        l3.countDown();
                        break;
                }
            }, zKClientConfig)) {
                assertTrue(zk.getClientConfig().getBoolean(ZKClientConfig.SECURE_CLIENT));
                assertTrue(l3.await(10, TimeUnit.SECONDS));
            }
        }
    }

    private Properties getServerConfig() {
        int clientPort = PortAssignment.unique();
        int clientSecurePort = PortAssignment.unique();

        final Properties configZookeeper = new Properties();
        configZookeeper.put("clientPort", clientPort + "");
        configZookeeper.put("secureClientPort", clientSecurePort + "");
        configZookeeper.put("host", "localhost");
        configZookeeper.put("ticktime", "4000");
        configZookeeper.put("client.certReload", "true");
        // TLS config fields
        configZookeeper.put("ssl.keyStore.location", keyStoreFile1.getAbsolutePath());
        configZookeeper.put("ssl.trustStore.location", trustStoreFile1.getAbsolutePath());
        configZookeeper.put("ssl.keyStore.type", "PEM");
        configZookeeper.put("ssl.trustStore.type", "PEM");
        // Netty is required for TLS
        configZookeeper.put("serverCnxnFactory", org.apache.zookeeper.server.NettyServerCnxnFactory.class.getName());
        return configZookeeper;
    }

    private ZKClientConfig getZKClientConfig() throws IOException {
        // Saving copies in JKS format to be used for client calls even after writeStringToFile overwrites keyStoreFile1 and trustStoreFile1
        File clientKeyStore = x509TestContext1.getKeyStoreFile(KeyStoreFileType.JKS);
        File clientTrustStore = x509TestContext1.getTrustStoreFile(KeyStoreFileType.JKS);

        ZKClientConfig zKClientConfig = new ZKClientConfig();
        zKClientConfig.setProperty("zookeeper.client.secure", "true");
        zKClientConfig.setProperty("zookeeper.ssl.keyStore.location", clientKeyStore.getAbsolutePath());
        zKClientConfig.setProperty("zookeeper.ssl.trustStore.location", clientTrustStore.getAbsolutePath());
        zKClientConfig.setProperty("zookeeper.ssl.keyStore.type", "JKS");
        zKClientConfig.setProperty("zookeeper.ssl.trustStore.type", "JKS");
        // only netty supports TLS
        zKClientConfig.setProperty("zookeeper.clientCnxnSocket", org.apache.zookeeper.ClientCnxnSocketNetty.class.getName());
        return zKClientConfig;
    }
}
