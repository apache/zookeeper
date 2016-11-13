/**
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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ReconfigTest;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ReconfigLegacyTest extends QuorumPeerTestBase {

    private static final int SERVER_COUNT = 3;

    @Before
    public void setup() {
        ClientBase.setupTestEnv();
        QuorumPeerConfig.setReconfigEnabled(true);
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest",
                "super:D/InIHSb7yEEbrWz8b9l71RjZJU="/* password is 'test'*/);
    }

    /**
     * This test checks that when started with a single static config file the
     * servers will create a valid dynamic config file. Also checks that when
     * the static config includes a clientPort but the dynamic definition also
     * includes it, the static definition is erased.
     */
    @Test
    public void testConfigFileBackwardCompatibility() throws Exception {
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;
        ArrayList<String> allServers = new ArrayList<String>();

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=localhost:" + PortAssignment.unique()
                    + ":" + PortAssignment.unique() + ":participant;localhost:"
                    + clientPorts[i];
            allServers.add(server);
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();

        MainThread mt[] = new MainThread[SERVER_COUNT];
        ZooKeeper zk[] = new ZooKeeper[SERVER_COUNT];

        // Start the servers with a static config file, without a dynamic
        // config file.
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection, false);
            // check that a dynamic configuration file doesn't exist
            Assert.assertEquals( mt[i].getDynamicFiles().length, 0 );
            mt[i].start();
        }
        // Check that the servers are up, have the right config and can process operations.
        // Check that the static config was split into static and dynamic files correctly.
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
            zk[i] = ClientBase.createZKClient("127.0.0.1:" + clientPorts[i]);
            File[] dynamicFiles = mt[i].getDynamicFiles();

            Assert.assertTrue( dynamicFiles.length== 1 );
            ReconfigTest.testServerHasConfig(zk[i], allServers, null);
            // check that static config file doesn't include membership info
            // and has a pointer to the dynamic configuration file
            Properties cfg = readPropertiesFromFile(mt[i].confFile);
            for (int j = 0; j < SERVER_COUNT; j++) {
                Assert.assertFalse(cfg.containsKey("server." + j));
            }
            Assert.assertTrue(cfg.containsKey("dynamicConfigFile"));
            Assert.assertFalse(cfg.containsKey("clientPort"));

            // check that the dynamic configuration file contains the membership info
            cfg = readPropertiesFromFile(dynamicFiles[0]);
            for (int j = 0; j < SERVER_COUNT; j++) {
                String serverLine = cfg.getProperty("server." + j, "");
                Assert.assertEquals(allServers.get(j), "server." + j + "="
                        + serverLine);
            }
            Assert.assertFalse(cfg.containsKey("dynamicConfigFile"));
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
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
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
     * {@link https://issues.apache.org/jira/browse/ZOOKEEPER-1992}
     * 1. When a server starts from old style static config, without a client port in the server
     *    specification, it should keep the client port in static config file.
     * 2. After port reconfig, the old port should be removed from static file
     *    and new port added to dynamic file.
     * @throws Exception
     */
    @Test
    public void testReconfigRemoveClientFromStatic() throws Exception {
        final int clientPorts[] = new int[SERVER_COUNT];
        final int quorumPorts[] = new int[SERVER_COUNT];
        final int electionPorts[] = new int[SERVER_COUNT];

        final int changedServerId = 0;
        final int newClientPort = PortAssignment.unique();

        StringBuilder sb = new StringBuilder();
        ArrayList<String> allServers = new ArrayList<String>();
        ArrayList<String> newServers = new ArrayList<String>();

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            quorumPorts[i] = PortAssignment.unique();
            electionPorts[i] = PortAssignment.unique();

            String server = "server." + i + "=localhost:" + quorumPorts[i]
                    +":" + electionPorts[i] + ":participant";
            allServers.add(server);
            sb.append(server + "\n");

            if(i == changedServerId) {
                newServers.add(server + ";0.0.0.0:" + newClientPort);
            } else {
                newServers.add(server);
            }
        }
        String quorumCfgSection = sb.toString();

        MainThread mt[] = new MainThread[SERVER_COUNT];
        ZooKeeper zk[] = new ZooKeeper[SERVER_COUNT];
        ZooKeeperAdmin zkAdmin[] = new ZooKeeperAdmin[SERVER_COUNT];

        // Start the servers with a static config file, without a dynamic config file.
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], quorumCfgSection, false);
            mt[i].start();
        }

        // Check that when a server starts from old style config, it should keep the client
        // port in static config file.
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
            zk[i] = ClientBase.createZKClient("127.0.0.1:" + clientPorts[i]);
            zkAdmin[i] = new ZooKeeperAdmin("127.0.0.1:" + clientPorts[i],
                    ClientBase.CONNECTION_TIMEOUT, this);
            zkAdmin[i].addAuthInfo("digest", "super:test".getBytes());

            ReconfigTest.testServerHasConfig(zk[i], allServers, null);
            Properties cfg = readPropertiesFromFile(mt[i].confFile);

            Assert.assertTrue(cfg.containsKey("dynamicConfigFile"));
            Assert.assertTrue(cfg.containsKey("clientPort"));
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
            if (i == changedServerId) {
                Assert.assertFalse(staticCfg.containsKey("clientPort"));
            } else {
                Assert.assertTrue(staticCfg.containsKey("clientPort"));
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
    @Test(timeout = 120000)
    public void testRestartZooKeeperServer() throws Exception {
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique()
                    + ":" + PortAssignment.unique() + ":participant;127.0.0.1:"
                    + clientPorts[i];
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();
        MainThread mt[] = new MainThread[SERVER_COUNT];

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection,
                    false);
            mt[i].start();
        }

        // ensure server started
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
        }

        ZooKeeper zk = ClientBase.createZKClient("127.0.0.1:" + clientPorts[0]);

        String zNodePath="/serverRestartTest";
        String data = "originalData";
        zk.create(zNodePath, data.getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
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
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
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
