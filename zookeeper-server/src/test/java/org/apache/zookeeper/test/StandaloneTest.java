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

package org.apache.zookeeper.test;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone server tests.
 */
public class StandaloneTest extends QuorumPeerTestBase implements Watcher {

    protected static final Logger LOG = LoggerFactory.getLogger(StandaloneTest.class);

    @BeforeEach
    public void setup() {
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest", "super:D/InIHSb7yEEbrWz8b9l71RjZJU="/* password is 'test'*/);
        QuorumPeerConfig.setReconfigEnabled(true);
    }

    /**
     * This test wouldn't create any dynamic config.
     * However, it adds a "clientPort=xxx" in static config file.
     * It checks the standard way of standalone mode.
     */
    @Test
    public void testNoDynamicConfig() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT = PortAssignment.unique();

        MainThread mt = new MainThread(MainThread.UNSET_MYID, CLIENT_PORT, "", false);
        verifyStandalone(mt, CLIENT_PORT);
    }

    /**
     * This test creates a dynamic config of new format.
     * The dynamic config is written in dynamic config file.
     * It checks that the client port will be read from the dynamic config.
     *
     * This handles the case of HBase, which adds a single server line to the config.
     * Maintain b/w compatibility.
     */
    @Test
    public void testClientPortInDynamicFile() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT = PortAssignment.unique();

        String quorumCfgSection = "server.1=127.0.0.1:" + (PortAssignment.unique()) + ":" + (PortAssignment.unique()) + ":participant;" + CLIENT_PORT + "\n";

        MainThread mt = new MainThread(1, quorumCfgSection);
        verifyStandalone(mt, CLIENT_PORT);
    }

    /**
     * This test creates a dynamic config of new format.
     * The dynamic config is written in static config file.
     * It checks that the client port will be read from the dynamic config.
     */
    @Test
    public void testClientPortInStaticFile() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT = PortAssignment.unique();

        String quorumCfgSection = "server.1=127.0.0.1:" + (PortAssignment.unique()) + ":" + (PortAssignment.unique()) + ":participant;" + CLIENT_PORT + "\n";

        MainThread mt = new MainThread(1, quorumCfgSection, false);
        verifyStandalone(mt, CLIENT_PORT);
    }

    void verifyStandalone(MainThread mt, int clientPort) throws InterruptedException {
        mt.start();
        try {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT),
                    "waiting for server 1 being up");
        } finally {
            assertFalse(mt.isQuorumPeerRunning(), "Error- MainThread started in Quorum Mode!");
            mt.shutdown();
        }
    }

    /**
     * Verify that reconfiguration in standalone mode fails with
     * KeeperException.UnimplementedException.
     */
    @Test
    public void testStandaloneReconfigFails() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT = PortAssignment.unique();
        final String HOSTPORT = "127.0.0.1:" + CLIENT_PORT;

        File tmpDir = ClientBase.createTmpDir();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);

        ServerCnxnFactory f = ServerCnxnFactory.createFactory(CLIENT_PORT, -1);
        f.startup(zks);
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up ");

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, watcher);
        ZooKeeperAdmin zkAdmin = new ZooKeeperAdmin(HOSTPORT, CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        List<String> joiners = new ArrayList<String>();
        joiners.add("server.2=localhost:1234:1235;1236");
        // generate some transactions that will get logged
        try {
            zkAdmin.addAuthInfo("digest", "super:test".getBytes());
            zkAdmin.reconfigure(joiners, null, null, -1, new Stat());
            fail("Reconfiguration in standalone should trigger " + "UnimplementedException");
        } catch (KeeperException.UnimplementedException ex) {
            // expected
        }
        zk.close();

        zks.shutdown();
        f.shutdown();
        assertTrue(ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being down ");
    }

}
