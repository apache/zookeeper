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
package org.apache.zookeeper;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.server.AuthenticationHelper;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EnforceAuthenticationTest extends QuorumPeerTestBase {
    protected static final Logger LOG = LoggerFactory.getLogger(EnforceAuthenticationTest.class);
    private Servers servers;
    private int clientPort;

    @Before
    public void setUp() {
        System.setProperty("zookeeper.admin.enableServer", "false");
        System.setProperty("zookeeper.4lw.commands.whitelist", "*");
        System.clearProperty(AuthenticationHelper.ENFORCE_AUTH_ENABLED);
        System.clearProperty(AuthenticationHelper.ENFORCE_AUTH_SCHEMES);
    }

    @After
    public void tearDown() throws InterruptedException {
        if (servers != null) {
            servers.shutDownAllServers();
        }
        System.clearProperty(AuthenticationHelper.ENFORCE_AUTH_ENABLED);
        System.clearProperty(AuthenticationHelper.ENFORCE_AUTH_SCHEMES);
    }

    /**
     * When AuthenticationHelper.ENFORCE_AUTH_ENABLED is not set or set to false, behaviour should
     * be same as the old ie. clients without authentication are allowed to do operations
     */
    @Test
    public void testEnforceAuthenticationOldBehaviour() throws Exception {
        Map<String, String> prop = new HashMap<>();
        startServer(prop);
        testEnforceAuthOldBehaviour(false);
    }

    @Test
    public void testEnforceAuthenticationOldBehaviourWithNetty() throws Exception {
        Map<String, String> prop = new HashMap<>();
        //setting property false should give the same behaviour as when property is not set
        prop.put(removeZooKeeper(AuthenticationHelper.ENFORCE_AUTH_ENABLED), "false");
        prop.put("serverCnxnFactory", "org.apache.zookeeper.server.NettyServerCnxnFactory");
        startServer(prop);
        testEnforceAuthOldBehaviour(true);
    }

    private void testEnforceAuthOldBehaviour(boolean netty) throws Exception {
        ZKClientConfig config = new ZKClientConfig();
        if (netty) {
            config.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET,
                "org.apache.zookeeper.ClientCnxnSocketNetty");
        }
        ZooKeeper client = ClientBase
            .createZKClient("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT, CONNECTION_TIMEOUT,
                config);
        String path = "/defaultAuth" + System.currentTimeMillis();
        String data = "someData";
        client.create(path, data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        byte[] data1 = client.getData(path, false, null);
        assertEquals(data, new String(data1));
        client.close();
    }

    /**
     * Server start should fail when ZooKeeperServer.ENFORCE_AUTH_ENABLED is set to true  but
     * AuthenticationHelper.ENFORCE_AUTH_SCHEME is not configured
     */
    @Test
    public void testServerStartShouldFailWhenEnforceAuthSchemeIsNotConfigured() throws Exception {
        Map<String, String> prop = new HashMap<>();
        prop.put(removeZooKeeper(AuthenticationHelper.ENFORCE_AUTH_ENABLED), "true");
        testServerCannotStart(prop);
    }

    /**
     * Server start should fail when AuthenticationHelper.ENFORCE_AUTH_ENABLED is set to true,
     * AuthenticationHelper.ENFORCE_AUTH_SCHEME is configured but authentication provider is not
     * configured.
     */
    @Test
    public void testServerStartShouldFailWhenAuthProviderIsNotConfigured() throws Exception {
        Map<String, String> prop = new HashMap<>();
        prop.put(removeZooKeeper(AuthenticationHelper.ENFORCE_AUTH_ENABLED), "true");
        prop.put(removeZooKeeper(AuthenticationHelper.ENFORCE_AUTH_SCHEMES), "sasl");
        testServerCannotStart(prop);
    }

    private void testServerCannotStart(Map<String, String> prop)
        throws Exception {
        File confFile = getConfFile(prop);
        ServerConfig config = new ServerConfig();
        config.parse(confFile.toString());
        ZooKeeperServerMain serverMain = new ZooKeeperServerMain();
        try {
            serverMain.runFromConfig(config);
            fail("IllegalArgumentException is expected.");
        } catch (IllegalArgumentException e) {
            //do nothing
        }
    }

    @Test
    public void testEnforceAuthenticationNewBehaviour() throws Exception {
        Map<String, String> prop = new HashMap<>();
        prop.put(removeZooKeeper(AuthenticationHelper.ENFORCE_AUTH_ENABLED), "true");
        prop.put(removeZooKeeper(AuthenticationHelper.ENFORCE_AUTH_SCHEMES), "digest");
        //digest auth provider is started by default, so no need to
        //prop.put("authProvider.1", DigestAuthenticationProvider.class.getName());
        startServer(prop);
        testEnforceAuthNewBehaviour(false);
    }

    @Test
    public void testEnforceAuthenticationNewBehaviourWithNetty() throws Exception {
        Map<String, String> prop = new HashMap<>();
        prop.put(removeZooKeeper(AuthenticationHelper.ENFORCE_AUTH_ENABLED), "true");
        prop.put(removeZooKeeper(AuthenticationHelper.ENFORCE_AUTH_SCHEMES), "digest");
        prop.put("serverCnxnFactory", "org.apache.zookeeper.server.NettyServerCnxnFactory");
        startServer(prop);
        testEnforceAuthNewBehaviour(true);
    }

    /**
     * Client operations are allowed only after the authentication is done
     */
    private void testEnforceAuthNewBehaviour(boolean netty) throws Exception {
        ZKClientConfig config = new ZKClientConfig();
        if (netty) {
            config.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET,
                "org.apache.zookeeper.ClientCnxnSocketNetty");
        }
        CountDownLatch countDownLatch = new CountDownLatch(1);
        ZooKeeper client =
            new ZooKeeper("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT, getWatcher(countDownLatch),
                config);
        countDownLatch.await();
        String path = "/newAuth" + System.currentTimeMillis();
        String data = "someData";

        //try without authentication
        try {
            client.create(path, data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("SessionClosedRequireAuthException is expected.");
        } catch (KeeperException.SessionClosedRequireAuthException e) {
            //do nothing
        }
        client.close();
        countDownLatch = new CountDownLatch(1);
        client =
            new ZooKeeper("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT, getWatcher(countDownLatch),
                config);
        countDownLatch.await();

        // try operations after authentication
        String idPassword = "user1:pass1";
        client.addAuthInfo("digest", idPassword.getBytes());
        client.create(path, data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        byte[] data1 = client.getData(path, false, null);
        assertEquals(data, new String(data1));
        client.close();
    }

    @Test
    public void testEnforceAuthenticationWithMultipleAuthSchemes() throws Exception {
        Map<String, String> prop = new HashMap<>();
        prop.put(removeZooKeeper(AuthenticationHelper.ENFORCE_AUTH_ENABLED), "true");
        prop.put(removeZooKeeper(AuthenticationHelper.ENFORCE_AUTH_SCHEMES), "digest,ip");
        startServer(prop);
        ZKClientConfig config = new ZKClientConfig();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        ZooKeeper client =
            new ZooKeeper("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT, getWatcher(countDownLatch),
                config);
        countDownLatch.await();
        // try operation without adding auth info, it should be success as ip auth info is
        // added automatically by server
        String path = "/newAuth" + System.currentTimeMillis();
        String data = "someData";
        client.create(path, data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        byte[] data1 = client.getData(path, false, null);
        assertEquals(data, new String(data1));
        client.close();
    }

    private String removeZooKeeper(String prop) {
        return prop.replace("zookeeper.", "");
    }

    private File getConfFile(Map<String, String> additionalProp) throws IOException {
        clientPort = PortAssignment.unique();
        StringBuilder sb = new StringBuilder();
        sb.append("standaloneEnabled=true" + "\n");
        if (null != additionalProp) {
            for (Map.Entry<String, String> entry : additionalProp.entrySet()) {
                sb.append(entry.getKey());
                sb.append("=");
                sb.append(entry.getValue());
                sb.append("\n");
            }
        }
        String currentQuorumCfgSection = sb.toString();
        return new MainThread(1, clientPort, currentQuorumCfgSection, false).getConfFile();
    }

    private void startServer(Map<String, String> additionalProp) throws Exception {
        additionalProp.put("standaloneEnabled", "true");
        servers = LaunchServers(1, additionalProp);
        clientPort = servers.clientPorts[0];
    }

    private Watcher getWatcher(CountDownLatch countDownLatch) {
        return event -> {
            Event.EventType type = event.getType();
            if (type == Event.EventType.None) {
                Event.KeeperState state = event.getState();
                if (state == Event.KeeperState.SyncConnected) {
                    LOG.info("Event.KeeperState.SyncConnected");
                    countDownLatch.countDown();
                } else if (state == Event.KeeperState.Expired) {
                    LOG.info("Event.KeeperState.Expired");
                } else if (state == Event.KeeperState.Disconnected) {
                    LOG.info("Event.KeeperState.Disconnected");
                } else if (state == Event.KeeperState.AuthFailed) {
                    LOG.info("Event.KeeperState.AuthFailed");
                }
            }
        };
    }
}
