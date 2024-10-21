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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.util.regex.Pattern;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.StringUtils;
import org.apache.zookeeper.server.NettyServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class ReadOnlyModeWithSSLTest extends ZKTestCase {

    private static int CONNECTION_TIMEOUT = QuorumBase.CONNECTION_TIMEOUT;
    private QuorumUtil qu = new QuorumUtil(1);
    private ClientX509Util clientX509Util;
    private ZKClientConfig clientConfig;

    @BeforeEach
    public void setUp() throws Exception {
        clientX509Util = new ClientX509Util();
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
        clientConfig = new ZKClientConfig();
        clientConfig.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
        clientConfig.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        clientConfig.setProperty(clientX509Util.getSslKeystoreLocationProperty(), testDataPath + "/ssl/testKeyStore.jks");
        clientConfig.setProperty(clientX509Util.getSslKeystorePasswdProperty(), "testpass");
        clientConfig.setProperty(clientX509Util.getSslTruststoreLocationProperty(), testDataPath + "/ssl/testTrustStore.jks");
        clientConfig.setProperty(clientX509Util.getSslTruststorePasswdProperty(), "testpass");
        System.setProperty("readonlymode.enabled", "true");
        System.setProperty(NettyServerCnxnFactory.PORT_UNIFICATION_KEY, Boolean.TRUE.toString());
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(clientX509Util.getSslKeystoreLocationProperty(), testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(clientX509Util.getSslKeystorePasswdProperty(), "testpass");
        System.setProperty(clientX509Util.getSslTruststoreLocationProperty(), testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(clientX509Util.getSslTruststorePasswdProperty(), "testpass");
    }

    @AfterEach
    public void tearDown() throws Exception {
        System.clearProperty(NettyServerCnxnFactory.PORT_UNIFICATION_KEY);
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(clientX509Util.getSslKeystoreLocationProperty());
        System.clearProperty(clientX509Util.getSslKeystorePasswdProperty());
        System.clearProperty(clientX509Util.getSslKeystorePasswdPathProperty());
        System.clearProperty(clientX509Util.getSslTruststoreLocationProperty());
        System.clearProperty(clientX509Util.getSslTruststorePasswdProperty());
        System.clearProperty(clientX509Util.getSslTruststorePasswdPathProperty());
        System.clearProperty(clientX509Util.getFipsModeProperty());
        System.clearProperty(clientX509Util.getSslHostnameVerificationEnabledProperty());
        System.clearProperty(clientX509Util.getSslProviderProperty());
        clientX509Util.close();
        System.setProperty("readonlymode.enabled", "false");
        qu.tearDown();
    }

    /**
     * Ensures that client seeks for r/w servers while it's connected to r/o
     * server.
     */
    @Test
    @Timeout(value = 90)
    public void testSeekForRwServerWithSSL() throws Exception {
        qu.enableLocalSession(true);
        qu.startQuorum();

        try (LoggerTestTool loggerTestTool = new LoggerTestTool("org.apache.zookeeper")) {
            ByteArrayOutputStream os = loggerTestTool.getOutputStream();

            qu.shutdown(2);
            ClientBase.CountdownWatcher watcher = new ClientBase.CountdownWatcher();
            ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT, watcher, true, clientConfig);
            watcher.waitForConnected(CONNECTION_TIMEOUT);

            // if we don't suspend a peer it will rejoin a quorum
            qu.getPeer(1).peer
                    .setSuspended(true);

            // start two servers to form a quorum; client should detect this and
            // connect to one of them
            watcher.reset();
            qu.start(2);
            qu.start(3);
            ClientBase.waitForServerUp(qu.getConnString(), 2000);
            watcher.waitForConnected(CONNECTION_TIMEOUT);
            zk.create("/test", "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            // resume poor fellow
            qu.getPeer(1).peer
                    .setSuspended(false);

            String log = os.toString();
            assertFalse(StringUtils.isEmpty(log), "OutputStream doesn't have any log messages");

            LineNumberReader r = new LineNumberReader(new StringReader(log));
            String line;
            Pattern p = Pattern.compile(".*Majority server found.*");
            boolean found = false;
            while ((line = r.readLine()) != null) {
                if (p.matcher(line).matches()) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "Majority server wasn't found while connected to r/o server");
        }
    }
}
