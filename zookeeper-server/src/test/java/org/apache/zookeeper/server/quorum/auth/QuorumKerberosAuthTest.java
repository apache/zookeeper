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
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase.MainThread;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

public class QuorumKerberosAuthTest extends KerberosSecurityTestcase {
    private static File keytabFile;
    static {
        String keytabFilePath = FilenameUtils.normalize(KerberosTestUtils.getKeytabFile(), true);

        // note: we use "refreshKrb5Config=true" to refresh the kerberos config in the JVM,
        // making sure that we use the latest config even if other tests already have been executed
        // and initialized the kerberos client configs before)
        String jaasEntries = ""
                + "QuorumServer {\n"
                + "       com.sun.security.auth.module.Krb5LoginModule required\n"
                + "       useKeyTab=true\n"
                + "       keyTab=\"" + keytabFilePath + "\"\n"
                + "       storeKey=true\n"
                + "       useTicketCache=false\n"
                + "       debug=false\n"
                + "       refreshKrb5Config=true\n"
                + "       principal=\"" + KerberosTestUtils.getServerPrincipal() + "\";\n" + "};\n"
                + "QuorumLearner {\n"
                + "       com.sun.security.auth.module.Krb5LoginModule required\n"
                + "       useKeyTab=true\n"
                + "       keyTab=\"" + keytabFilePath + "\"\n"
                + "       storeKey=true\n"
                + "       useTicketCache=false\n"
                + "       debug=false\n"
                + "       refreshKrb5Config=true\n"
                + "       principal=\"" + KerberosTestUtils.getLearnerPrincipal() + "\";\n" + "};\n";
        setupJaasConfig(jaasEntries);
    }

    @Before
    public void setUp() throws Exception {
        // create keytab
        keytabFile = new File(KerberosTestUtils.getKeytabFile());
        String learnerPrincipal = KerberosTestUtils.getLearnerPrincipal();
        String serverPrincipal = KerberosTestUtils.getServerPrincipal();
        learnerPrincipal = learnerPrincipal.substring(0, learnerPrincipal.lastIndexOf("@"));
        serverPrincipal = serverPrincipal.substring(0, serverPrincipal.lastIndexOf("@"));
        getKdc().createPrincipal(keytabFile, learnerPrincipal, serverPrincipal);
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
        String serverPrincipal = KerberosTestUtils.getServerPrincipal();
        serverPrincipal = serverPrincipal.substring(0, serverPrincipal.lastIndexOf("@"));
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
}
