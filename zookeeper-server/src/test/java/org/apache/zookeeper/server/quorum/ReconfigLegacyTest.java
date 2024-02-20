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

package org.apache.zookeeper.server.quorum;

import static org.apache.zookeeper.server.quorum.QuorumPeerMainTLSTest.getClientTLSConfigs;
import static org.apache.zookeeper.server.quorum.QuorumPeerMainTLSTest.getServerTLSConfigs;
import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.Security;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.X509KeyType;
import org.apache.zookeeper.common.X509TestContext;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ReconfigTest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


public class ReconfigLegacyTest extends QuorumPeerTestBase {

    private static final int SERVER_COUNT = 3;
    private static File tempDir;
    private static X509TestContext x509TestContext = null;

    @BeforeAll
    public static void beforeAll() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        tempDir = ClientBase.createEmptyTestDir();
        x509TestContext = X509TestContext.newBuilder()
            .setTempDir(tempDir)
            .setKeyStoreKeyType(X509KeyType.EC)
            .setTrustStoreKeyType(X509KeyType.EC)
            .build();
    }

    @AfterAll
    public static void afterAll() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        try {
            FileUtils.deleteDirectory(tempDir);
        } catch (IOException e) {
            // ignore
        }
    }

    @BeforeEach
    public void setup() {
        ClientBase.setupTestEnv();
        QuorumPeerConfig.setReconfigEnabled(true);
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest", "super:D/InIHSb7yEEbrWz8b9l71RjZJU="/* password is 'test'*/);
    }

    /**
     * This test checks that when started with a single static config file the
     * servers will create a valid dynamic config file. Also checks that when
     * the static config includes a clientPort but the dynamic definition also
     * includes it, the static definition is erased.
     */
    @Test
    public void testConfigFileBackwardCompatibility() throws Exception {
        final int[] clientPorts = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;
        ArrayList<String> allServers = new ArrayList<>();

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=localhost:" + PortAssignment.unique() + ":" + PortAssignment.unique()
                + ":participant;localhost:" + clientPorts[i];
            allServers.add(server);
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();

        MainThread[] mt = new MainThread[SERVER_COUNT];
        ZooKeeper[] zk = new ZooKeeper[SERVER_COUNT];

        // Start the servers with a static config file, without a dynamic
        // config file.
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection, "participant", false);
            // check that a dynamic configuration file doesn't exist
            assertEquals(mt[i].getDynamicFiles().length, 0);
            mt[i].start();
        }
        // Check that the servers are up, have the right config and can process operations.
        // Check that the static config was split into static and dynamic files correctly.
        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(
                ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], CONNECTION_TIMEOUT),
                "waiting for server " + i + " being up");
            zk[i] = ClientBase.createZKClient("127.0.0.1:" + clientPorts[i]);
            File[] dynamicFiles = mt[i].getDynamicFiles();

            assertTrue(dynamicFiles.length == 1);
            ReconfigTest.testServerHasConfig(zk[i], allServers, null);
            // check that static config file doesn't include membership info
            // and has a pointer to the dynamic configuration file
            // check that static config file doesn't include peerType info
            Properties cfg = readPropertiesFromFile(mt[i].confFile);
            for (int j = 0; j < SERVER_COUNT; j++) {
                assertFalse(cfg.containsKey("server." + j));
            }
            assertFalse(cfg.containsKey("peerType"));
            assertTrue(cfg.containsKey("dynamicConfigFile"));
            assertFalse(cfg.containsKey("clientPort"));

            // check that the dynamic configuration file contains the membership info
            cfg = readPropertiesFromFile(dynamicFiles[0]);
            for (int j = 0; j < SERVER_COUNT; j++) {
                String serverLine = cfg.getProperty("server." + j, "");
                assertEquals(allServers.get(j), "server." + j + "=" + serverLine);
            }
            assertFalse(cfg.containsKey("dynamicConfigFile"));
        }
        ReconfigTest.testNormalOperation(zk[0], zk[1]);

        // now shut down the servers and restart them
        for (int i = 0; i < SERVER_COUNT; i++) {
            zk[i].close();
            mt[i].shutdown();
        }
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].start();
        }
        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(
                ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], CONNECTION_TIMEOUT),
                "waiting for server " + i + " being up");
            zk[i] = ClientBase.createZKClient("127.0.0.1:" + clientPorts[i]);
            ReconfigTest.testServerHasConfig(zk[i], allServers, null);
        }
        ReconfigTest.testNormalOperation(zk[0], zk[1]);
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
            zk[i].close();
        }
    }

    /**
     * https://issues.apache.org/jira/browse/ZOOKEEPER-1992
     * 1. When a server starts from old style static config, without a client port in the server
     *    specification, it should keep the client port in static config file.
     * 2. After port reconfig, the old port should be removed from static file
     *    and new port added to dynamic file.
     * @throws Exception
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testReconfigRemoveClientFromStatic(boolean isSecure) throws Exception {
        final int[] clientPorts = new int[SERVER_COUNT];
        final int[] secureClientPorts = new int[SERVER_COUNT];
        final int[] adminServerPorts = new int[SERVER_COUNT];
        final int[] quorumPorts = new int[SERVER_COUNT];
        final int[] electionPorts = new int[SERVER_COUNT];

        final int changedServerId = 0;
        final int newClientPortOrSecureClientPort = PortAssignment.unique();

        StringBuilder sb = new StringBuilder();
        ArrayList<String> allServers = new ArrayList<>();
        ArrayList<String> newServers = new ArrayList<>();

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            secureClientPorts[i] = PortAssignment.unique();
            adminServerPorts[i] = PortAssignment.unique();
            quorumPorts[i] = PortAssignment.unique();
            electionPorts[i] = PortAssignment.unique();

            String server = "server." + i + "=localhost:" + quorumPorts[i] + ":" + electionPorts[i] + ":participant";
            allServers.add(server);
            sb.append(server + "\n");

            if (i == changedServerId) {
                if (isSecure) {
                    newServers.add(server + ";;0.0.0.0:" + newClientPortOrSecureClientPort);
                } else {
                    newServers.add(server + ";0.0.0.0:" + newClientPortOrSecureClientPort);
                }

            } else {
                newServers.add(server);
            }
        }
        String quorumCfgSection = sb.toString();

        MainThread[] mt = new MainThread[SERVER_COUNT];
        ZooKeeper[] zk = new ZooKeeper[SERVER_COUNT];
        ZooKeeperAdmin[] zkAdmin = new ZooKeeperAdmin[SERVER_COUNT];

        Map<String, String> configMap = getServerTLSConfigs(x509TestContext);
        StringBuilder configBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : configMap.entrySet()) {
            configBuilder.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }

        // Start the servers with a static config file, without a dynamic config file.
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (isSecure) {
                mt[i] = new MainThread(i, MainThread.UNSET_STATIC_CLIENTPORT, adminServerPorts[i], secureClientPorts[i], quorumCfgSection, configBuilder.toString(), null, false, null);
            } else {
                mt[i] = new MainThread(i, clientPorts[i], adminServerPorts[i], quorumCfgSection, null, null, false);
            }
            mt[i].start();
        }


        ZKClientConfig clientConfig;
        if (isSecure) {
            clientConfig = getClientTLSConfigs(x509TestContext);
        } else {
            clientConfig = null;
        }

        // Check that when a server starts from old style config, it should keep the client
        // port in static config file.
        for (int i = 0; i < SERVER_COUNT; i++) {
            String cnxnString = "127.0.0.1:" + (isSecure ? secureClientPorts[i] : clientPorts[i]);
            assertTrue(
                ClientBase.waitForServerUp(cnxnString, CONNECTION_TIMEOUT, isSecure, clientConfig),
                "waiting for server " + i + " being up");
            zk[i] = ClientBase.createZKClient(cnxnString, CONNECTION_TIMEOUT, CONNECTION_TIMEOUT, clientConfig);
            zkAdmin[i] = new ZooKeeperAdmin(cnxnString, ClientBase.CONNECTION_TIMEOUT, this, clientConfig);
            zkAdmin[i].addAuthInfo("digest", "super:test".getBytes());

            ReconfigTest.testServerHasConfig(zk[i], allServers, null);
            Properties cfg = readPropertiesFromFile(mt[i].confFile);

            assertTrue(cfg.containsKey("dynamicConfigFile"));
            if (isSecure) {
                assertTrue(cfg.containsKey("secureClientPort"));
            } else {
                assertTrue(cfg.containsKey("clientPort"));
            }

        }
        ReconfigTest.testNormalOperation(zk[0], zk[1]);

        ReconfigTest.reconfig(zkAdmin[1], null, null, newServers, -1);
        ReconfigTest.testNormalOperation(zk[0], zk[1]);

        // Sleep since writing the config files may take time.
        Thread.sleep(1000);

        // Check that new dynamic config includes the updated client port.
        // Check that server changedServerId erased clientPort from static config.
        // Check that other servers still have clientPort in static config.

        for (int i = 0; i < SERVER_COUNT; i++) {
            ReconfigTest.testServerHasConfig(zk[i], newServers, null);
            Properties staticCfg = readPropertiesFromFile(mt[i].confFile);
            String configKey = isSecure ? "secureClientPort" : "clientPort";
            if (i == changedServerId) {
                assertFalse(staticCfg.containsKey(configKey));
            } else {
                assertTrue(staticCfg.containsKey(configKey));
            }
        }

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
            zk[i].close();
            zkAdmin[i].close();
        }
    }

    public static Properties readPropertiesFromFile(File file) throws IOException {
        Properties cfg = new Properties();
        FileInputStream in = new FileInputStream(file);
        try {
            cfg.load(in);
        } finally {
            in.close();
        }
        return cfg;
    }

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2244
     *
     * @throws Exception
     */
    @Test
    @Timeout(value = 120)
    public void testRestartZooKeeperServer() throws Exception {
        final int[] clientPorts = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique() + ":" + PortAssignment.unique()
                + ":participant;127.0.0.1:" + clientPorts[i];
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();
        MainThread[] mt = new MainThread[SERVER_COUNT];

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection, false);
            mt[i].start();
        }

        // ensure server started
        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], CONNECTION_TIMEOUT),
                "waiting for server " + i + " being up");
        }

        ZooKeeper zk = ClientBase.createZKClient("127.0.0.1:" + clientPorts[0]);

        String zNodePath = "/serverRestartTest";
        String data = "originalData";
        zk.create(zNodePath, data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();

        /**
         * stop two servers out of three and again start them
         */
        mt[0].shutdown();
        mt[1].shutdown();
        mt[0].start();
        mt[1].start();
        // ensure server started
        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], CONNECTION_TIMEOUT),
                "waiting for server " + i + " being up");
        }
        zk = ClientBase.createZKClient("127.0.0.1:" + clientPorts[0]);

        byte[] dataBytes = zk.getData(zNodePath, null, null);
        String receivedData = new String(dataBytes);
        assertEquals(data, receivedData);

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

}
