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

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase.MainThread;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import junit.framework.Assert;

public class QuorumKerberosHostBasedAuthTest extends KerberosSecurityTestcase {
    private static File keytabFile;
    private static String hostServerPrincipal = KerberosTestUtils.getHostServerPrincipal();
    private static String hostLearnerPrincipal = KerberosTestUtils.getHostLearnerPrincipal();
    private static String hostNamedLearnerPrincipal = KerberosTestUtils.getHostNamedLearnerPrincipal("myHost");
    static {
        setupJaasConfigEntries(hostServerPrincipal, hostLearnerPrincipal, hostNamedLearnerPrincipal);
    }

    private static void setupJaasConfigEntries(String hostServerPrincipal,
            String hostLearnerPrincipal, String hostNamedLearnerPrincipal) {
        String keytabFilePath = FilenameUtils.normalize(KerberosTestUtils.getKeytabFile(), true);
        String jaasEntries = new String(""
                + "QuorumServer {\n"
                + "       com.sun.security.auth.module.Krb5LoginModule required\n"
                + "       useKeyTab=true\n"
                + "       keyTab=\"" + keytabFilePath + "\"\n"
                + "       storeKey=true\n"
                + "       useTicketCache=false\n"
                + "       debug=false\n"
                + "       principal=\"" + KerberosTestUtils.replaceHostPattern(hostServerPrincipal) + "\";\n" + "};\n"
                + "QuorumLearner {\n"
                + "       com.sun.security.auth.module.Krb5LoginModule required\n"
                + "       useKeyTab=true\n"
                + "       keyTab=\"" + keytabFilePath + "\"\n"
                + "       storeKey=true\n"
                + "       useTicketCache=false\n"
                + "       debug=false\n"
                + "       principal=\"" + KerberosTestUtils.replaceHostPattern(hostLearnerPrincipal) + "\";\n" + "};\n"
                + "QuorumLearnerMyHost {\n"
                + "       com.sun.security.auth.module.Krb5LoginModule required\n"
                + "       useKeyTab=true\n"
                + "       keyTab=\"" + keytabFilePath + "\"\n"
                + "       storeKey=true\n"
                + "       useTicketCache=false\n"
                + "       debug=false\n"
                + "       principal=\"" + hostNamedLearnerPrincipal + "\";\n" + "};\n");
        setupJaasConfig(jaasEntries);
    }

    @BeforeClass
    public static void setUp() throws Exception {
        // create keytab
        keytabFile = new File(KerberosTestUtils.getKeytabFile());

        // Creates principals in the KDC and adds them to a keytab file.
        String learnerPrincipal = hostLearnerPrincipal.substring(0, hostLearnerPrincipal.lastIndexOf("@"));
        learnerPrincipal = KerberosTestUtils.replaceHostPattern(learnerPrincipal);
        String serverPrincipal = hostServerPrincipal.substring(0, hostServerPrincipal.lastIndexOf("@"));
        serverPrincipal = KerberosTestUtils.replaceHostPattern(serverPrincipal);

        // learner with ipaddress in principal
        String learnerPrincipal2 = hostNamedLearnerPrincipal.substring(0, hostNamedLearnerPrincipal.lastIndexOf("@"));
        getKdc().createPrincipal(keytabFile, learnerPrincipal, learnerPrincipal2, serverPrincipal);
    }

    @After
    public void tearDown() throws Exception {
        for (MainThread mainThread : mt) {
            mainThread.shutdown();
            mainThread.deleteBaseDir();
        }
    }

    @AfterClass
    public static void cleanup() {
        if(keytabFile != null){
            FileUtils.deleteQuietly(keytabFile);
        }
        cleanupJaasConfig();
    }

    /**
     * Test to verify that server is able to start with valid credentials
     */
    @Test(timeout = 120000)
    public void testValidCredentials() throws Exception {
        String serverPrincipal = hostServerPrincipal.substring(0, hostServerPrincipal.lastIndexOf("@"));
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED, "true");
        authConfigs.put(QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED, "true");
        authConfigs.put(QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL, serverPrincipal);
        String connectStr = startQuorum(3, authConfigs, 3);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        for (int i = 0; i < 10; i++) {
            zk.create("/" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        zk.close();
    }

    /**
     * Test to verify that the bad server connection to the quorum should be rejected.
     */
    @Test(timeout = 120000)
    public void testConnectBadServer() throws Exception {
        String serverPrincipal = hostServerPrincipal.substring(0, hostServerPrincipal.lastIndexOf("@"));
        Map<String, String> authConfigs = new HashMap<String, String>();
        authConfigs.put(QuorumAuth.QUORUM_SASL_AUTH_ENABLED, "true");
        authConfigs.put(QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED, "true");
        authConfigs.put(QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED, "true");
        authConfigs.put(QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL, serverPrincipal);
        String connectStr = startQuorum(3, authConfigs, 3);
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        for (int i = 0; i < 10; i++) {
            zk.create("/" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        zk.close();

        String quorumCfgSection = mt.get(0).getQuorumCfgSection();
        StringBuilder sb = new StringBuilder();
        sb.append(quorumCfgSection);

        int myid = mt.size() + 1;
        final int clientPort = PortAssignment.unique();
        String server = String.format("server.%d=localhost:%d:%d:participant",
                myid, PortAssignment.unique(), PortAssignment.unique());
        sb.append(server + "\n");
        quorumCfgSection = sb.toString();
        authConfigs.put(QuorumAuth.QUORUM_LEARNER_SASL_LOGIN_CONTEXT,
                "QuorumLearnerMyHost");
        MainThread badServer = new MainThread(myid, clientPort, quorumCfgSection,
                authConfigs);
        badServer.start();
        watcher = new CountdownWatcher();
        connectStr = "127.0.0.1:" + clientPort;
        zk = new ZooKeeper(connectStr, ClientBase.CONNECTION_TIMEOUT, watcher);
        try{
            watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT/3);
            Assert.fail("Must throw exception as the myHost is not an authorized one!");
        } catch (TimeoutException e){
            // expected
        } finally {
            zk.close();
            badServer.shutdown();
            badServer.deleteBaseDir();
        }
    }
}
