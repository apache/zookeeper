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

//import com.salesforce.sds.keystore.DynamicKeyStoreProvider;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.server.admin.JettyAdminServer;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class X509AuthFailureTest {
    protected static final Logger LOG = LoggerFactory.getLogger(X509AuthFailureTest.class);

    private static ClientX509Util clientX509Util;
    public static final int TIMEOUT = 5000;
    public static int CONNECTION_TIMEOUT = 30000;

    @Before
    public void setup() throws Exception{
        clientX509Util = new ClientX509Util();
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
        System.setProperty(clientX509Util.getSslKeystoreLocationProperty(), testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(clientX509Util.getSslKeystorePasswdProperty(), "testpass");
        System.setProperty(clientX509Util.getSslTruststoreLocationProperty(), testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(clientX509Util.getSslTruststorePasswdProperty(), "testpass");
        //System.setProperty("zookeeper.authProvider.x509", "org.apache.zookeeper.server.auth.SFDCX509AuthenticationProvider");
        //System.setProperty(clientX509Util.getSslProviderProperty(), DynamicKeyStoreProvider.PKIKS_PROVIDER_NAME);
    }

    @After
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
    public void testSecureStandaloneServerAuthZFailure() throws Exception {
        System.setProperty("zookeeper.ssl.allowedRoles", "testauthz");
        Integer secureClientPort = PortAssignment.unique();
        MainThread mt = new MainThread(QuorumPeerTestBase.MainThread.UNSET_MYID, "", secureClientPort, false);
        mt.start();

        try {
            ZooKeeper zk = createZKClnt("127.0.0.1:" + secureClientPort);
        } catch (Exception e){
            ServerStats serverStats = mt.getSecureCnxnFactory().getZooKeeperServer().serverStats();
            assertTrue(serverStats.getAuthFailedCount()>=1);
        }
        mt.shutdown();

    }

    private ZooKeeper createZKClnt(String cxnString) throws Exception {
        ClientBase.CountdownWatcher watcher = new ClientBase.CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(cxnString, TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        return zk;
    }

    public static class MainThread extends Thread {
        final TestZKServerMain main;
        final File confFile;
        final File tmpDir;

        public static final int UNSET_STATIC_CLIENTPORT = -1;
        // standalone mode doens't need myid
        public static final int UNSET_MYID = -1;

        File baseDir;
        private int myid;
        private int clientPort;
        private Map<String, String> otherConfigs;

        public MainThread(int myid, String quorumCfgSection) throws IOException {
            this(myid, quorumCfgSection, true);
        }

        public MainThread(int myid, String quorumCfgSection, Integer secureClientPort, boolean writeDynamicConfigFile)
                throws  IOException {
            this(myid, UNSET_STATIC_CLIENTPORT, JettyAdminServer.DEFAULT_PORT, secureClientPort,
                    quorumCfgSection, null, writeDynamicConfigFile, null);
        }

        public MainThread(int myid, String quorumCfgSection, boolean writeDynamicConfigFile)
                throws IOException {
            this(myid, UNSET_STATIC_CLIENTPORT, quorumCfgSection, writeDynamicConfigFile);
        }

        public MainThread(int myid, int clientPort, String quorumCfgSection, boolean writeDynamicConfigFile)
                throws IOException {
            this(myid, clientPort, JettyAdminServer.DEFAULT_PORT, quorumCfgSection, null, writeDynamicConfigFile);
        }

        public MainThread(int myid, int clientPort, String quorumCfgSection, boolean writeDynamicConfigFile,
                          String version) throws IOException {
            this(myid, clientPort, JettyAdminServer.DEFAULT_PORT, quorumCfgSection, null,
                    writeDynamicConfigFile, version);
        }

        public MainThread(int myid, int clientPort, String quorumCfgSection, String configs)
                throws IOException {
            this(myid, clientPort, JettyAdminServer.DEFAULT_PORT, quorumCfgSection, configs, true);
        }

        public MainThread(int myid, int clientPort, int adminServerPort, String quorumCfgSection,
                          String configs)  throws IOException {
            this(myid, clientPort, adminServerPort, quorumCfgSection, configs, true);
        }

        public MainThread(int myid, int clientPort, int adminServerPort, String quorumCfgSection,
                          String configs, boolean writeDynamicConfigFile)
                throws IOException {
            this(myid, clientPort, adminServerPort, quorumCfgSection, configs, writeDynamicConfigFile, null);
        }

        public MainThread(int myid, int clientPort, int adminServerPort, String quorumCfgSection,
                          String configs, boolean writeDynamicConfigFile, String version) throws IOException {
            this(myid, clientPort, adminServerPort, null, quorumCfgSection, configs, writeDynamicConfigFile, version);
        }

        public MainThread(int myid, int clientPort, int adminServerPort, Integer secureClientPort,
                          String quorumCfgSection, String configs, boolean writeDynamicConfigFile, String version)
                throws IOException {
            tmpDir = ClientBase.createTmpDir();
            LOG.info("id = " + myid + " tmpDir = " + tmpDir + " clientPort = "
                    + clientPort + " adminServerPort = " + adminServerPort);

            File dataDir = new File(tmpDir, "data");
            if (!dataDir.mkdir()) {
                throw new IOException("Unable to mkdir " + dataDir);
            }

            confFile = new File(tmpDir, "zoo.cfg");

            FileWriter fwriter = new FileWriter(confFile);
            fwriter.write("tickTime=4000\n");
            fwriter.write("initLimit=10\n");
            fwriter.write("syncLimit=5\n");
            if(configs != null){
                fwriter.write(configs);
            }

            // Convert windows path to UNIX to avoid problems with "\"
            String dir = PathUtils.normalizeFileSystemPath(dataDir.toString());

            fwriter.write("dataDir=" + dir + "\n");
            fwriter.write("admin.serverPort=" + adminServerPort + "\n");

            // For backward compatibility test, some tests create dynamic configuration
            // without setting client port.
            // This could happen both in static file or dynamic file.
            if (clientPort != UNSET_STATIC_CLIENTPORT) {
                fwriter.write("clientPort=" + clientPort + "\n");
            }

            if (secureClientPort != null) {
                fwriter.write("secureClientPort=" + secureClientPort + "\n");
            }

            fwriter.write(quorumCfgSection);

            fwriter.flush();
            fwriter.close();

            File myidFile = new File(dataDir, "myid");
            fwriter = new FileWriter(myidFile);
            fwriter.write(Integer.toString(myid));
            fwriter.flush();
            fwriter.close();
            main = new TestZKServerMain();
        }

        public void run() {
            String args[] = new String[1];
            args[0] = confFile.toString();
            try {
                main.initializeAndRun(args);
            } catch (Exception e) {
                // test will still fail even though we just log/ignore
                LOG.error("unexpected exception in run", e);
            }
        }

        public void shutdown() {
            main.shutdown();
        }

        public ServerCnxnFactory getSecureCnxnFactory(){
            return main.getSecureCnxnFactory();
        }
    }

    public static  class TestZKServerMain extends ZooKeeperServerMain {
        public void shutdown() {
            super.shutdown();
        }
    }
}