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

import java.util.HashMap;
import java.util.Map;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase.MainThread;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Test;

public class QuorumDigestAuthTest extends QuorumAuthTestBase {

    static {
        String jaasEntries = new String(""
                + "QuorumServer {\n"
                + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                + "       user_test=\"mypassword\";\n" + "};\n"
                + "QuorumClient {\n"
                + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                + "       username=\"test\"\n"
                + "       password=\"mypassword\";\n" + "};\n"
                + "QuorumClientInvalid {\n"
                + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                + "       username=\"test\"\n"
                + "       password=\"invalid\";\n" + "};" + "\n");
        setupJaasConfig(jaasEntries);
    }

    @After
    public void tearDown() throws Exception {
        for (MainThread mainThread : mt) {
            mainThread.shutdown();
            mainThread.deleteBaseDir();
        }
    }

    @AfterClass
    public static void cleanup(){
        cleanupJaasConfig();
    }

    /**
     * Test to verify that server is able to start with valid credentials
     */
    @Test(timeout = 30000)
    public void testValidCredentials() throws Exception {
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_AUTH_REQUIRED, "true");

        String connectStr = startQuorum(3, authConfigs, 3);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT,
                watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        for (int i = 0; i < 10; i++) {
            zk.create("/" + i, new byte[0], Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
        }
        zk.close();
    }

    /**
     * Test to verify that server is able to start with invalid credentials if
     * the configuration is set to quorum.auth.requireSasl=false. Quorum will
     * talk each other even if the authentication is not succeeded
     */
    @Test(timeout = 30000)
    public void testSaslNotRequiredWithInvalidCredentials() throws Exception {
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_LOGIN_CONTEXT, "QuorumClientInvalid");
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_AUTH_ENABLED, "false");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_AUTH_REQUIRED, "false");
        String connectStr = startQuorum(3, authConfigs, 3);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT,
                watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        for (int i = 0; i < 10; i++) {
            zk.create("/" + i, new byte[0], Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
        }
        zk.close();
    }

    /**
     * Test to verify that server shouldn't start with invalid credentials
     * if the configuration is set to quorum.auth.requireSasl=true
     */
    @Test(timeout = 30000)
    public void testSaslRequiredInvalidCredentials() throws Exception {
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_LOGIN_CONTEXT, "QuorumClientInvalid");
        authConfigs.put(QuorumAuth.QUORUM_CLIENT_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_AUTH_REQUIRED, "true");
        int serverCount = 2;
        final int[] clientPorts = startQuorum(serverCount, new StringBuilder(),
                authConfigs, serverCount);
        for (int i = 0; i < serverCount; i++) {
            boolean waitForServerUp = ClientBase.waitForServerUp(
                    "127.0.0.1:" + clientPorts[i], QuorumPeerTestBase.TIMEOUT);
            Assert.assertFalse("Shouldn't start server with invalid credentials",
                    waitForServerUp);
        }
    }
}
