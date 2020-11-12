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
import static org.junit.jupiter.api.Assertions.fail;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class X509AuthFailureTest {
    protected static final Logger LOG = LoggerFactory.getLogger(X509AuthFailureTest.class);

    private static ClientX509Util clientX509Util;
    public static final int TIMEOUT = 5000;
    public static int CONNECTION_TIMEOUT = 30000;

    @BeforeEach
    public void setup() throws Exception{
        clientX509Util = new ClientX509Util();
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
        System.setProperty(clientX509Util.getSslKeystoreLocationProperty(), testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(clientX509Util.getSslKeystorePasswdProperty(), "testpass");
    }

    @AfterEach
    public void teardown() throws Exception {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
        System.clearProperty(ZKClientConfig.SECURE_CLIENT);
        System.clearProperty(clientX509Util.getSslKeystoreLocationProperty());
        System.clearProperty(clientX509Util.getSslKeystorePasswdProperty());
        System.clearProperty(clientX509Util.getSslTruststoreLocationProperty());
        System.clearProperty(clientX509Util.getSslTruststorePasswdProperty());
        clientX509Util.close();
    }

    /**
     * Developers might use standalone mode (which is the default for one server).
     * This test checks metrics for authz failure in standalone server
     */
    @Test
    public void testSecureStandaloneServerAuthNFailure() throws Exception {
        final Integer CLIENT_PORT = PortAssignment.unique();
        final Integer SECURE_CLIENT_PORT = PortAssignment.unique();

        ZooKeeperServerMainTest.MainThread mt = new ZooKeeperServerMainTest.MainThread(CLIENT_PORT, SECURE_CLIENT_PORT, true, null);
        mt.start();

        try {
            ZooKeeper zk = createZKClnt("127.0.0.1:" + SECURE_CLIENT_PORT);
            fail("should not be reached");
        } catch (Exception e){
            //Expected
        }
        ServerStats serverStats = mt.getSecureCnxnFactory().getZooKeeperServer().serverStats();
        assertTrue(serverStats.getAuthFailedCount() >= 1);
        mt.shutdown();

    }

    private ZooKeeper createZKClnt(String cxnString) throws Exception {
        ClientBase.CountdownWatcher watcher = new ClientBase.CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(cxnString, TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        return zk;
    }

}