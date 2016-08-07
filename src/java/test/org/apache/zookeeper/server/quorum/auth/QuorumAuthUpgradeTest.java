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

package org.apache.zookeeper.server.quorum.auth;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase.MainThread;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Test;

/**
 * Rolling upgrade should do in three steps:
 *
 * step-1) Stop the server and set the flags and restart the server.
 * quorum.auth.clientEnableSasl=false and quorum.auth.serverRequireSasl=false
 * Ensure all the servers completed this step. Now, move to next step.
 *
 * step-2) Stop the server one by one and change the flags and restart the server.
 * quorum.auth.clientEnableSasl=true and quorum.auth.serverRequireSasl=false
 * Ensure all the servers completed this step. Now, move to next step.
 *
 * step-3) Stop the server one by one and change the flags and restart the server.
 * quorum.auth.clientEnableSasl=true and quorum.auth.serverRequireSasl=true
 * Now, all the servers are fully upgraded and running in secured mode.
 */
public class QuorumAuthUpgradeTest extends QuorumAuthTestBase {
    static {
        String jaasEntries = new String("" + "QuorumServer {\n"
                + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                + "       user_test=\"mypassword\";\n" + "};\n"
                + "QuorumClient {\n"
                + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                + "       username=\"test\"\n"
                + "       password=\"mypassword\";\n" + "};\n");
        setupJaasConfig(jaasEntries);
    }

    @After
    public void tearDown() throws Exception {
        shutdownAll();
    }

    @AfterClass
    public static void cleanup() {
        cleanupJaasConfig();
    }

    /**
     * Test to verify that servers are able to start without any authentication.
     * peer0 -> quorum.auth.clientEnableSasl=false and quorum.auth.serverRequireSasl=false
     * peer1 -> quorum.auth.clientEnableSasl=false and quorum.auth.serverRequireSasl=false
     */
    @Test(timeout = 30000)
    public void testNullAuthClientServer() throws Exception {
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_AUTH_ENABLED, "false");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_AUTH_REQUIRED, "false");

        String connectStr = startQuorum(2, authConfigs, 0);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT,
                watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk.close();
    }

    /**
     * Test to verify that servers are able to form quorum.
     * peer0 -> quorum.auth.clientEnableSasl=true and quorum.auth.serverRequireSasl=false
     * peer1 -> quorum.auth.clientEnableSasl=false and quorum.auth.serverRequireSasl=false
     */
    @Test(timeout = 30000)
    public void testClientAuthAgainstNullAuthServer() throws Exception {
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_AUTH_REQUIRED, "false");

        startQuorum(2, authConfigs, 1);
    }

    /**
     * Test to verify that servers are able to form quorum.
     * peer0 -> quorum.auth.clientEnableSasl=true and quorum.auth.serverRequireSasl=false
     * peer1 -> quorum.auth.clientEnableSasl=true and quorum.auth.serverRequireSasl=false
     */
    @Test(timeout = 30000)
    public void testClientAuthAgainstNoAuthRequiredServer() throws Exception {
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_AUTH_REQUIRED, "false");

        String connectStr = startQuorum(2, authConfigs, 2);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT,
                watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk.close();
    }

    /**
     * Test to verify that servers are able to form quorum.
     * peer0 -> quorum.auth.clientEnableSasl=true and quorum.auth.serverRequireSasl=true
     * peer1 -> quorum.auth.clientEnableSasl=true and quorum.auth.serverRequireSasl=true
     */
    @Test(timeout = 30000)
    public void testAuthClientServer() throws Exception {
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_AUTH_REQUIRED, "true");

        String connectStr = startQuorum(2, authConfigs, 2);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT,
                watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk.close();
    }

    /**
     * Rolling upgrade will happen in three steps:
     *
     * step-1) Stop the server and set the flags and restart the server.
     * quorum.auth.clientEnableSasl=false and quorum.auth.serverRequireSasl=false
     * Ensure all the servers completed this step. Now, move to next step.
     *
     * step-2) Stop the server one by one and change the flags and restart the server.
     * quorum.auth.clientEnableSasl=true and quorum.auth.serverRequireSasl=false
     * Ensure all the servers completed this step. Now, move to next step.
     *
     * step-3) Stop the server one by one and change the flags and restart the server.
     * quorum.auth.clientEnableSasl=true and quorum.auth.serverRequireSasl=true
     * Now, all the servers are fully upgraded and running in secured mode.
     */
    @Test(timeout = 90000)
    public void testRollingUpgrade() throws Exception {
        //1. Start peer0,1,2 servers with quorum.auth.clientEnableSasl=true and
        // quorum.auth.serverRequireSasl=false
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_AUTH_ENABLED, "false");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_AUTH_REQUIRED, "false");

        String connectStr = startQuorum(3, authConfigs, 0);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT,
                watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        zk.create("/foo1", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        //2. Upgrade peer0,1,2 with quorum.auth.clientEnableSasl=true and
        // quorum.auth.serverRequireSasl=false
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_AUTH_REQUIRED, "false");
        restartServer(authConfigs, 0);
        restartServer(authConfigs, 1);
        restartServer(authConfigs, 2);
        zk.create("/foo2", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        //3. Upgrade peer0,1,2 with quorum.auth.clientEnableSasl=true and
        // quorum.auth.serverRequireSasl=true
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_AUTH_REQUIRED, "true");
        restartServer(authConfigs, 0);
        restartServer(authConfigs, 1);
        restartServer(authConfigs, 2);
        zk.create("/foo3", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        // 4. Restart peer2 with quorum.auth.clientEnableSasl=false and
        // quorum.auth.serverRequireSasl=false. It should fail to join the
        // quorum as this needs auth.
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_AUTH_ENABLED, "false");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_AUTH_REQUIRED, "false");
        MainThread m = shutdown(2);
        startServer(m, authConfigs);
        Assert.assertFalse("waiting for server 2 being up", ClientBase
                .waitForServerUp("127.0.0.1:" + m.getClientPort(), 5000));
    }

    private void restartServer(Map<String, String> authConfigs, int index)
            throws IOException {
        LOG.info("Restarting server myid=" + index);
        MainThread m = shutdown(index);
        startServer(m, authConfigs);
        Assert.assertTrue("waiting for server" + index + "being up",
                ClientBase.waitForServerUp("127.0.0.1:" + m.getClientPort(),
                        ClientBase.CONNECTION_TIMEOUT));
    }
}
