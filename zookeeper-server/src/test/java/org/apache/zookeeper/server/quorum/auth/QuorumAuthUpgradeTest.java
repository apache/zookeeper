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

package org.apache.zookeeper.server.quorum.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase.MainThread;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.apache.zookeeper.test.ClientTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Rolling upgrade should do in three steps:
 *
 * step-1) Stop the server and set the flags and restart the server.
 * quorum.auth.enableSasl=true, quorum.auth.learnerRequireSasl=false and quorum.auth.serverRequireSasl=false
 * Ensure that all the servers should complete this step. Now, move to next step.
 *
 * step-2) Stop the server one by one and change the flags and restart the server.
 * quorum.auth.enableSasl=true, quorum.auth.learnerRequireSasl=true and quorum.auth.serverRequireSasl=false
 * Ensure that all the servers should complete this step. Now, move to next step.
 *
 * step-3) Stop the server one by one and change the flags and restart the server.
 * quorum.auth.enableSasl=true, quorum.auth.learnerRequireSasl=true and quorum.auth.serverRequireSasl=true
 * Now, all the servers are fully upgraded and running in secured mode.
 */
public class QuorumAuthUpgradeTest extends QuorumAuthTestBase {

    static {
        String jaasEntries = "QuorumServer {\n"
                             + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                             + "       user_test=\"mypassword\";\n"
                             + "};\n"
                             + "QuorumLearner {\n"
                             + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                             + "       username=\"test\"\n"
                             + "       password=\"mypassword\";\n"
                             + "};\n";
        setupJaasConfig(jaasEntries);
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        shutdownAll();
        super.tearDown();
    }

    @AfterAll
    public static void cleanup() {
        cleanupJaasConfig();
    }

    /**
     * Test to verify that servers are able to start without any authentication.
     * peer0 -&gt; quorum.auth.enableSasl=false
     * peer1 -&gt; quorum.auth.enableSasl=false
     */
    @Test
    @Timeout(value = 30)
    public void testNullAuthLearnerServer() throws Exception {
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "false");

        String connectStr = startQuorum(2, authConfigs, 0);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
    }

    /**
     * Test to verify that servers are able to form quorum.
     * peer0 -&gt; quorum.auth.enableSasl=true, quorum.auth.learnerRequireSasl=false, quorum.auth.serverRequireSasl=false
     * peer1 -&gt; quorum.auth.enableSasl=false, quorum.auth.learnerRequireSasl=false, quorum.auth.serverRequireSasl=false
     */
    @Test
    @Timeout(value = 30)
    public void testAuthLearnerAgainstNullAuthServer() throws Exception {
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "true");

        String connectStr = startQuorum(2, authConfigs, 1);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
    }

    /**
     * Test to verify that servers are able to form quorum.
     * peer0 -&gt; quorum.auth.enableSasl=true, quorum.auth.learnerRequireSasl=false, quorum.auth.serverRequireSasl=false
     * peer1 -&gt; quorum.auth.enableSasl=true, quorum.auth.learnerRequireSasl=false, quorum.auth.serverRequireSasl=false
     */
    @Test
    @Timeout(value = 30)
    public void testAuthLearnerAgainstNoAuthRequiredServer() throws Exception {
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "true");

        String connectStr = startQuorum(2, authConfigs, 2);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
    }

    /**
     * Test to verify that servers are able to form quorum.
     * peer0 -&gt; quorum.auth.enableSasl=true, quorum.auth.learnerRequireSasl=true, quorum.auth.serverRequireSasl=true
     * peer1 -&gt; quorum.auth.enableSasl=true, quorum.auth.learnerRequireSasl=true, quorum.auth.serverRequireSasl=true
     */
    @Test
    @Timeout(value = 30)
    public void testAuthLearnerServer() throws Exception {
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED, "true");
        authConfigs.put(QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED, "true");

        String connectStr = startQuorum(2, authConfigs, 2);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
    }

    /**
     * Rolling upgrade should do in three steps:
     *
     * step-1) Stop the server and set the flags and restart the server.
     * quorum.auth.enableSasl=true, quorum.auth.learnerRequireSasl=false and quorum.auth.serverRequireSasl=false
     * Ensure that all the servers should complete this step. Now, move to next step.
     *
     * step-2) Stop the server one by one and change the flags and restart the server.
     * quorum.auth.enableSasl=true, quorum.auth.learnerRequireSasl=true and quorum.auth.serverRequireSasl=false
     * Ensure that all the servers should complete this step. Now, move to next step.
     *
     * step-3) Stop the server one by one and change the flags and restart the server.
     * quorum.auth.enableSasl=true, quorum.auth.learnerRequireSasl=true and quorum.auth.serverRequireSasl=true
     * Now, all the servers are fully upgraded and running in secured mode.
     */
    @Test
    @Timeout(value = 90)
    public void testRollingUpgrade() throws Exception {
        // Start peer0,1,2 servers with quorum.auth.enableSasl=false and
        // quorum.auth.learnerRequireSasl=false, quorum.auth.serverRequireSasl=false
        // Assume this is an existing cluster.
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "false");

        String connectStr = startQuorum(3, authConfigs, 0);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);

        //1. Upgrade peer0,1,2 with quorum.auth.enableSasl=true and
        // quorum.auth.learnerRequireSasl=false, quorum.auth.serverRequireSasl=false
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED, "false");
        authConfigs.put(QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED, "false");
        restartServer(authConfigs, 0, zk, watcher);
        restartServer(authConfigs, 1, zk, watcher);
        restartServer(authConfigs, 2, zk, watcher);

        //2. Upgrade peer0,1,2 with quorum.auth.enableSasl=true and
        // quorum.auth.learnerRequireSasl=true, quorum.auth.serverRequireSasl=false
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED, "false");
        restartServer(authConfigs, 0, zk, watcher);
        restartServer(authConfigs, 1, zk, watcher);
        restartServer(authConfigs, 2, zk, watcher);

        //3. Upgrade peer0,1,2 with quorum.auth.enableSasl=true and
        // quorum.auth.learnerRequireSasl=true, quorum.auth.serverRequireSasl=true
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED, "true");
        restartServer(authConfigs, 0, zk, watcher);
        restartServer(authConfigs, 1, zk, watcher);
        restartServer(authConfigs, 2, zk, watcher);

        //4. Restart peer2 with quorum.auth.learnerEnableSasl=false and
        // quorum.auth.serverRequireSasl=false. It should fail to join the
        // quorum as this needs auth.
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "false");
        MainThread m = shutdown(2);
        startServer(m, authConfigs);
        assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + m.getClientPort(), 5000),
            "waiting for server 2 being up");
    }

    private void restartServer(
        Map<String, String> authConfigs,
        int index,
        ZooKeeper zk,
        CountdownWatcher watcher) throws IOException, KeeperException, InterruptedException, TimeoutException {
            LOG.info("Restarting server myid={}", index);
            MainThread m = shutdown(index);
            startServer(m, authConfigs);
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + m.getClientPort(), ClientBase.CONNECTION_TIMEOUT),
                "waiting for server" + index + "being up");
            watcher.waitForConnected(ClientTest.CONNECTION_TIMEOUT);
            zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
    }

}
