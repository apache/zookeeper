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

/**
 *
 */
package org.apache.zookeeper.test;


import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKParameterized;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZookeeperServerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.server.quorum.QuorumSocketFactoryTest;
import org.apache.zookeeper.server.quorum.X509ClusterBase;
import org.apache.zookeeper.server.quorum.X509ClusterCASigned;
import org.apache.zookeeper.server.quorum.X509ClusterSelfSigned;
import org.apache.zookeeper.server.quorum.util.QuorumSocketFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(ZKParameterized.RunnerFactory.class)
public class SSLTest extends QuorumPeerTestBase {
    private static final int SERVER_COUNT = 3;
    private ZookeeperServerConfig zkConfig;
    private final boolean sslEnabled;
    private final boolean isSelfSigned;
    private X509ClusterBase x509ClusterBase;

    public SSLTest(final boolean sslEnabled,
                   final boolean isSelfSigned) {
        this.sslEnabled = sslEnabled;
        this.isSelfSigned = isSelfSigned;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {false, false}, {true, false}, {true, true}});
    }

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @Before
    public void setup() throws IOException {
        zkConfig = new ZookeeperServerConfig();
        String testDataPath = System.getProperty("test.data.dir", "build/test/data");
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
        System.setProperty(zkConfig.getSslConfig().getSslKeyStoreLocation(), testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(zkConfig.getSslConfig().getSslKeyStorePassword(), "testpass");
        System.setProperty(zkConfig.getSslConfig().getSslTrustStoreLocation(), testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(zkConfig.getSslConfig().getSslTrustStorePassword(), "testpass");

        if (sslEnabled) {
            System.setProperty(QuorumSocketFactory.SSL_ENABLED_PROP, "true");
        }

        if (!isSelfSigned) {
            this.x509ClusterBase = new X509ClusterCASigned("x509ca",
                    this.tmpFolder.newFolder("ssl").toPath(), SERVER_COUNT);
        } else {
            this.x509ClusterBase = new X509ClusterSelfSigned("x509ca",
                    this.tmpFolder.newFolder("ssl").toPath(), SERVER_COUNT);
        }
    }

    @After
    public void teardown() throws Exception {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
        System.clearProperty(ZKClientConfig.SECURE_CLIENT);
        System.clearProperty(zkConfig.getSslConfig().getSslKeyStoreLocation());
        System.clearProperty(zkConfig.getSslConfig().getSslKeyStorePassword());
        System.clearProperty(zkConfig.getSslConfig().getSslTrustStoreLocation());
        System.clearProperty(zkConfig.getSslConfig().getSslTrustStorePassword());
        System.clearProperty(QuorumSocketFactory.SSL_ENABLED_PROP);
    }

    /**
     * This test checks that SSL works in cluster setup of ZK servers, which includes:
     * 1. setting "secureClientPort" in "zoo.cfg" file.
     * 2. setting jvm flags for serverCnxn, keystore, truststore.
     * Finally, a zookeeper client should be able to connect to the secure port and
     * communicate with server via secure connection.
     * <p/>
     * Note that in this test a ZK server has two ports -- clientPort and secureClientPort.
     */
    @Test
    public void testSecureQuorumServer() throws Exception {
        final int clientPorts[] = new int[SERVER_COUNT];
        final Integer secureClientPorts[] = new Integer[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            secureClientPorts[i] = PortAssignment.unique();
            String server = String.format("server.%d=localhost:%d:%d:participant;localhost:%d",
                    i, PortAssignment.unique(), PortAssignment.unique(), clientPorts[i]);
            sb.append(server + "\n");
        }
        String quorumCfg = sb.toString();


        MainThread[] mt = new MainThread[SERVER_COUNT];
        for (int i = 0; i < SERVER_COUNT; i++) {
            final int index = i;
            mt[i] = new MainThread(i, quorumCfg, secureClientPorts[i], true) {
                @Override
                public TestQPMain getTestQPMain() {
                    return new SSLTestQPMain(x509ClusterBase, index);
                }
            };
            mt[i].start();
        }

        // Servers have been set up. Now go test if secure connection is successful.
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], TIMEOUT));

            ZooKeeper zk = ClientBase.createZKClient("127.0.0.1:" + secureClientPorts[i], TIMEOUT);
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
     * Developers might use standalone mode (which is the default for one server).
     * This test checks SSL works in standalone mode of ZK server.
     * <p/>
     * Note that in this test the Zk server has only secureClientPort
     */
    @Test
    public void testSecureStandaloneServer() throws Exception {
        Integer secureClientPort = PortAssignment.unique();
        MainThread mt = new MainThread(MainThread.UNSET_MYID, "", secureClientPort, false);
        mt.start();

        ZooKeeper zk = ClientBase.createZKClient("127.0.0.1:" + secureClientPort, TIMEOUT);
        zk.create("/test", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.delete("/test", -1);
        zk.close();
        mt.shutdown();
    }

    private static class SSLTestQPMain extends TestQPMain {
        final X509ClusterBase x509ClusterBase;
        final int index;
        public SSLTestQPMain(final X509ClusterBase x509ClusterBase,
                             final int index) {
            this.x509ClusterBase = x509ClusterBase;
            this.index = index;
        }
        @Override
        protected QuorumPeerConfig getQuorumPeerConfig() {
            final QuorumPeerConfig quorumPeerConfg = new QuorumPeerConfig();
            QuorumSocketFactoryTest.setQuorumPeerSslConfig(x509ClusterBase,
                    quorumPeerConfg, index);
            return quorumPeerConfg;
        }
    }
}
