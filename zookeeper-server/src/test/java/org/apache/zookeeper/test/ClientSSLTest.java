/*
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

/**
 *
 */

package org.apache.zookeeper.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.server.NettyServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ClientSSLTest extends QuorumPeerTestBase {

    private ClientX509Util clientX509Util;

    @Before
    public void setup() {
        System.setProperty(NettyServerCnxnFactory.PORT_UNIFICATION_KEY, Boolean.TRUE.toString());
        clientX509Util = new ClientX509Util();
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
        System.setProperty(clientX509Util.getSslKeystoreLocationProperty(), testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(clientX509Util.getSslKeystorePasswdProperty(), "testpass");
        System.setProperty(clientX509Util.getSslTruststoreLocationProperty(), testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(clientX509Util.getSslTruststorePasswdProperty(), "testpass");
    }

    @After
    public void teardown() {
        System.clearProperty(NettyServerCnxnFactory.PORT_UNIFICATION_KEY);
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
     * This test checks that client SSL connections work in the absence of a
     * secure port when port unification is set up for the plaintext port.
     *
     * This single client port will be tested for handling both plaintext
     * and SSL traffic.
     */
    @Test
    public void testClientServerUnifiedPort() throws Exception {
        testClientServerSSL(false);
    }

    /**
     * This test checks that client - server SSL works in cluster setup of ZK servers, which includes:
     * 1. setting "secureClientPort" in "zoo.cfg" file.
     * 2. setting jvm flags for serverCnxn, keystore, truststore.
     * Finally, a zookeeper client should be able to connect to the secure port and
     * communicate with server via secure connection.
     * <p>
     * Note that in this test a ZK server has two ports -- clientPort and secureClientPort.
     */
    @Test
    public void testClientServerSSL() throws Exception {
        testClientServerSSL(true);
    }

    public void testClientServerSSL(boolean useSecurePort) throws Exception {
        final int SERVER_COUNT = 3;
        final int[] clientPorts = new int[SERVER_COUNT];
        int[] secureClientPorts = null;
        if (useSecurePort) {
          secureClientPorts = new int[SERVER_COUNT];
        }

        MainThread[] mt = startThreeNodeSSLCluster(clientPorts, secureClientPorts);

        // Servers have been set up. Now go test if secure connection is successful.
        for (int i = 0; i < SERVER_COUNT; i++) {
            final int port = useSecurePort ? secureClientPorts[i] : clientPorts[i];
            ZooKeeper zk = ClientBase.createZKClient("127.0.0.1:" + port, TIMEOUT);
            // Do a simple operation to make sure the connection is fine.
            zk.create("/test", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.delete("/test", -1);
            zk.close();
        }

        for (int i = 0; i < mt.length; i++) {
            mt[i].shutdown();
        }
    }

    /**
     * This test covers the case when from the same JVM we connect to both secure and unsecure
     * clusters. In this case we can't use the Java System Properties, but we need to specify client
     * configuration.
     *
     * In this test the servers has two client ports open, one used only for secure connection and one
     * used only for unsecure connections. (the client port unification is disabled)
     */
    @Test
    public void testClientCanConnectBothSecureAndUnsecure() throws Exception {

      // to make sure the test is testing the case we want, we disable client port unification in the
      // server, and also disable the property which would instruct the client to connect using SSL
      System.clearProperty(NettyServerCnxnFactory.PORT_UNIFICATION_KEY);
      System.clearProperty(ZKClientConfig.SECURE_CLIENT);

      final int SERVER_COUNT = 3;
      final int[] clientPorts = new int[SERVER_COUNT];
      int[] secureClientPorts = new int[SERVER_COUNT];

      MainThread[] mt = startThreeNodeSSLCluster(clientPorts, secureClientPorts);

      // Servers have been set up. Now go test if both secure and unsecure connection is successful.
      for (int i = 0; i < SERVER_COUNT; i++) {

        // testing the secure connection, also do some simple operation to verify that it works
        ZKClientConfig secureClientConfig = new ZKClientConfig();
        secureClientConfig.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
        ZooKeeper zkSecure = ClientBase.createZKClient("127.0.0.1:" + secureClientPorts[i], TIMEOUT, secureClientConfig);
        zkSecure.create("/test", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertTrue(zkSecure.isSSL());

        // testing the unsecure connection, also do some simple operation to verify that it works
        ZKClientConfig unsecureClientConfig = new ZKClientConfig();
        unsecureClientConfig.setProperty(ZKClientConfig.SECURE_CLIENT, "false");
        ZooKeeper zkUnsecure = ClientBase.createZKClient("127.0.0.1:" + clientPorts[i], TIMEOUT, unsecureClientConfig);
        zkUnsecure.delete("/test", -1);
        assertFalse(zkUnsecure.isSSL());

        zkSecure.close();
        zkUnsecure.close();
      }

      for (int i = 0; i < mt.length; i++) {
        mt[i].shutdown();
      }

    }



    /**
     * Developers might use standalone mode (which is the default for one server).
     * This test checks SSL works in standalone mode of ZK server.
     * <p>
     * Note that in this test the Zk server has only secureClientPort
     */
    @Test
    public void testSecureStandaloneServer() throws Exception {
        int secureClientPort = PortAssignment.unique();
        MainThread mt = new MainThread(MainThread.UNSET_MYID, "", secureClientPort, false);
        mt.start();

        ZooKeeper zk = ClientBase.createZKClient("127.0.0.1:" + secureClientPort, TIMEOUT);
        zk.create("/test", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.delete("/test", -1);
        zk.close();
        mt.shutdown();
    }


   /**
    * This method starts a ZK quorum with random client ports. It always define clientPort, but defines
    * secureClientPort optionally as well. We will also wait for the quorum to be started.
    *
    * @param clientPorts mandatory option, it will be populated with the client ports
    * @param secureClientPorts optional option, if it is specified (not null) then it will be populated
    *                          with the secureClientPorts for each ZooKeeper server. If it is set to
    *                          null, then no SecureClientPort will be defined for the ZooKeeper servers
    * @return array of ZooKeeper server main threads
    * @throws Exception
    */
    private MainThread[] startThreeNodeSSLCluster(final int[] clientPorts, final int[] secureClientPorts) throws Exception {
        StringBuilder sb = new StringBuilder();
        int serverCount = clientPorts.length;
        boolean useSecurePort = secureClientPorts != null && secureClientPorts.length == clientPorts.length;
        for (int i = 0; i < serverCount; i++) {
            clientPorts[i] = PortAssignment.unique();
            if (useSecurePort) {
              secureClientPorts[i] = PortAssignment.unique();
            }
            String server = String.format("server.%d=localhost:%d:%d:participant;localhost:%d",
                                          i, PortAssignment.unique(), PortAssignment.unique(), clientPorts[i]);
            sb.append(server + "\n");
        }
        String quorumCfg = sb.toString();

        MainThread[] mt = new MainThread[serverCount];
        for (int i = 0; i < serverCount; i++) {
          if (useSecurePort) {
            mt[i] = new MainThread(i, quorumCfg, secureClientPorts[i], true);
          } else {
            mt[i] = new MainThread(i, quorumCfg, true);
          }
          mt[i].start();
        }

        // Add some timing margin for the quorum to elect a leader
        // (without this margin, timeouts have been observed in parallel test runs)
        for (int i = 0; i < serverCount; i++) {
          assertTrue(
            "waiting for server " + i + " being up",
            ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], 2 * TIMEOUT));
        }

        return mt;
    }
}
