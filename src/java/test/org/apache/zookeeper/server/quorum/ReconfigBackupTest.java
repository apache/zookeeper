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

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.common.StringUtils;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ReconfigTest;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

public class ReconfigBackupTest extends QuorumPeerTestBase {

    public static String getVersionFromConfigStr(String config) throws IOException {
        Properties props = new Properties();
        props.load(new StringReader(config));
        return props.getProperty("version", "");
    }

    // upgrade this once we have Google-Guava or Java 7+
    public static String getFileContent(File file) throws FileNotFoundException {
        Scanner sc = new Scanner(file);
        StringBuilder sb = new StringBuilder();
        while (sc.hasNextLine()) {
            sb.append(sc.nextLine() + "\n");
        }
        return sb.toString();
    }

    @Before
    public void setup() {
        ClientBase.setupTestEnv();
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest",
                "super:D/InIHSb7yEEbrWz8b9l71RjZJU="/* password is 'test'*/);
    }

    /**
     * This test checks that it will backup static file on bootup.
     */
    @Test
    public void testBackupStatic() throws Exception {
        final int SERVER_COUNT = 3;
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=localhost:" + PortAssignment.unique()
                    + ":" + PortAssignment.unique() + ":participant;localhost:"
                    + clientPorts[i];
            sb.append(server + "\n");
        }

        String currentQuorumCfgSection = sb.toString();

        MainThread mt[] = new MainThread[SERVER_COUNT];
        String[] staticFileContent = new String[SERVER_COUNT];
        String[] staticBackupContent = new String[SERVER_COUNT];

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection, false);
            // check that a dynamic configuration file doesn't exist
            Assert.assertNull("static file backup shouldn't exist before bootup",
                    mt[i].getFileByName("zoo.cfg.bak"));
            staticFileContent[i] = getFileContent(mt[i].confFile);
            mt[i].start();
        }

        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
            File backupFile = mt[i].getFileByName("zoo.cfg.bak");
            Assert.assertNotNull("static file backup should exist", backupFile);
            staticBackupContent[i] = getFileContent(backupFile);
            Assert.assertEquals(staticFileContent[i], staticBackupContent[i]);
        }

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

    /**
     * This test checks that on reconfig, a new dynamic file will be created with
     * current version appended to file name. Meanwhile, the dynamic file pointer
     * in static config file should also be changed.
     */
    @Test
    public void testReconfigCreateNewVersionFile() throws Exception {
        final int SERVER_COUNT = 3;
        final int NEW_SERVER_COUNT = 5;

        final int clientPorts[] = new int[NEW_SERVER_COUNT];
        final int quorumPorts[] = new int[NEW_SERVER_COUNT];
        final int electionPorts[] = new int[NEW_SERVER_COUNT];
        final String servers[] = new String[NEW_SERVER_COUNT];

        StringBuilder sb = new StringBuilder();
        ArrayList<String> oldServers = new ArrayList<String>();
        ArrayList<String> newServers = new ArrayList<String>();

        for (int i = 0; i < NEW_SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            quorumPorts[i] = PortAssignment.unique();
            electionPorts[i] = PortAssignment.unique();
            servers[i] = "server." + i + "=localhost:" + quorumPorts[i]
                    + ":" + electionPorts[i] + ":participant;localhost:"
                    + clientPorts[i];

            newServers.add(servers[i]);

            if (i >= SERVER_COUNT) {
                continue;
            }
            oldServers.add(servers[i]);
            sb.append(servers[i] + "\n");
        }

        String quorumCfgSection = sb.toString();

        MainThread mt[] = new MainThread[NEW_SERVER_COUNT];
        ZooKeeper zk[] = new ZooKeeper[NEW_SERVER_COUNT];
        ZooKeeperAdmin zkAdmin[] = new ZooKeeperAdmin[NEW_SERVER_COUNT];

        // start old cluster
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], quorumCfgSection, "reconfigEnabled=true\n");
            mt[i].start();
        }

        String firstVersion = null, secondVersion = null;

        // test old cluster
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
            zk[i] = ClientBase.createZKClient("127.0.0.1:" + clientPorts[i]);
            zkAdmin[i] = new ZooKeeperAdmin("127.0.0.1:" + clientPorts[i],
                    ClientBase.CONNECTION_TIMEOUT, this);
            zkAdmin[i].addAuthInfo("digest", "super:test".getBytes());

            Properties cfg = ReconfigLegacyTest.readPropertiesFromFile(mt[i].confFile);
            String filename = cfg.getProperty("dynamicConfigFile", "");

            String version = QuorumPeerConfig.getVersionFromFilename(filename);
            Assert.assertNotNull(version);

            String configStr = ReconfigTest.testServerHasConfig(
                    zk[i], oldServers, null);

            String configVersion = getVersionFromConfigStr(configStr);
            // the version appended to filename should be the same as
            // the one of quorum verifier.
            Assert.assertEquals(version, configVersion);

            if (i == 0) {
                firstVersion = version;
            } else {
                Assert.assertEquals(firstVersion, version);
            }
        }

        ReconfigTest.reconfig(zkAdmin[1], null, null, newServers, -1);

        // start additional new servers
        for (int i = SERVER_COUNT; i < NEW_SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], quorumCfgSection + servers[i]);
            mt[i].start();
        }

        // wait for new servers to be up running
        for (int i = SERVER_COUNT; i < NEW_SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
            zk[i] = ClientBase.createZKClient("127.0.0.1:" + clientPorts[i]);
        }

        // test that all servers have:
        // a different, larger version dynamic file
        for (int i = 0; i < NEW_SERVER_COUNT; i++) {
            Properties cfg = ReconfigLegacyTest.readPropertiesFromFile(mt[i].confFile);
            String filename = cfg.getProperty("dynamicConfigFile", "");

            String version = QuorumPeerConfig.getVersionFromFilename(filename);
            Assert.assertNotNull(version);

            String configStr = ReconfigTest.testServerHasConfig(zk[i],
                    newServers, null);

            String quorumVersion = getVersionFromConfigStr(configStr);
            Assert.assertEquals(version, quorumVersion);

            if (i == 0) {
                secondVersion = version;
                Assert.assertTrue(
                        Long.parseLong(secondVersion, 16)
                                > Long.parseLong(firstVersion, 16));
            } else {
                Assert.assertEquals(secondVersion, version);
            }
        }

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
            zk[i].close();
            zkAdmin[i].close();
        }
    }

    /**
     * This test checks that if a version is appended to dynamic file,
     * then peer should use that version as quorum config version.
     * <p/>
     * The scenario: one server has an older version of 3 servers, and
     * four others have newer version of 5 servers. Finally, the lag-off one
     * should have server config of 5 servers.
     */
    @Test
    public void testVersionOfDynamicFilename() throws Exception {
        final int SERVER_COUNT = 5;
        final int oldServerCount = 3;
        final int lagOffServerId = 0;
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;
        StringBuilder oldSb = new StringBuilder();
        ArrayList<String> allServers = new ArrayList<String>();


        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=localhost:" + PortAssignment.unique()
                    + ":" + PortAssignment.unique() + ":participant;localhost:"
                    + clientPorts[i];
            sb.append(server + "\n");
            allServers.add(server);

            if (i < oldServerCount) {
                // only take in the first 3 servers as old quorum config.
                oldSb.append(server + "\n");
            }
        }

        String currentQuorumCfgSection = sb.toString();

        String oldQuorumCfg = oldSb.toString();

        MainThread mt[] = new MainThread[SERVER_COUNT];


        for (int i = 0; i < SERVER_COUNT; i++) {
            if (i == lagOffServerId) {
                mt[i] = new MainThread(i, clientPorts[i], oldQuorumCfg, true, "100000000");
            } else {
                mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection,
                        true, "200000000");
            }

            // before connecting to quorum, servers should have set up dynamic file
            // version and pointer. And the lag-off server is using the older
            // version dynamic file.
            if (i == lagOffServerId) {
                Assert.assertNotNull(
                        mt[i].getFileByName("zoo.cfg.dynamic.100000000"));
                Assert.assertNull(
                        mt[i].getFileByName("zoo.cfg.dynamic.200000000"));
                Assert.assertTrue(
                        mt[i].getPropFromStaticFile("dynamicConfigFile")
                                .endsWith(".100000000"));
            } else {
                Assert.assertNotNull(
                        mt[i].getFileByName("zoo.cfg.dynamic.200000000"));
                Assert.assertTrue(
                        mt[i].getPropFromStaticFile("dynamicConfigFile")
                                .endsWith(".200000000"));
            }

            mt[i].start();
        }

        String dynamicFileContent = null;

        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
            ZooKeeper zk = ClientBase.createZKClient("127.0.0.1:" + clientPorts[i]);

            // we should see that now all servers have the same config of 5 servers
            // including the lag-off server.
            String configStr = ReconfigTest.testServerHasConfig(zk, allServers, null);
            Assert.assertEquals("200000000", getVersionFromConfigStr(configStr));
            
            List<String> configLines = Arrays.asList(configStr.split("\n"));
            Collections.sort(configLines);
            String sortedConfigStr = StringUtils.joinStrings(configLines, "\n");
            
             File dynamicConfigFile = mt[i].getFileByName("zoo.cfg.dynamic.200000000");
             Assert.assertNotNull(dynamicConfigFile);

            // All dynamic files created with the same version should have
            // same configs, and they should be equal to the config we get from QuorumPeer.
            if (i == 0) {
                dynamicFileContent = getFileContent(dynamicConfigFile);                
                Assert.assertEquals(sortedConfigStr, dynamicFileContent + 
                        "version=200000000");
            } else {
                String otherDynamicFileContent = getFileContent(dynamicConfigFile);
                Assert.assertEquals(dynamicFileContent, otherDynamicFileContent);
            }

            zk.close();
        }

        // finally, we should also check that the lag-off server has updated
        // the dynamic file pointer.
        Assert.assertTrue(
                mt[lagOffServerId].getPropFromStaticFile("dynamicConfigFile")
                        .endsWith(".200000000"));

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }
}