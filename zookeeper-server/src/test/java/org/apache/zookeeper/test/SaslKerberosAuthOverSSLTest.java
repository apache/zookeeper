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

import static org.apache.zookeeper.client.ZKClientConfig.ENABLE_CLIENT_SASL_KEY;
import static org.apache.zookeeper.client.ZKClientConfig.LOGIN_CONTEXT_NAME_KEY;
import static org.apache.zookeeper.client.ZKClientConfig.ZK_SASL_CLIENT_USERNAME;
import static org.apache.zookeeper.client.ZKClientConfig.ZOOKEEPER_SERVER_PRINCIPAL;
import static org.apache.zookeeper.client.ZKClientConfig.ZOOKEEPER_SERVER_REALM;
import static org.junit.Assert.fail;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.Properties;
import javax.security.auth.login.Configuration;
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Environment;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.quorum.auth.KerberosTestUtils;
import org.apache.zookeeper.server.quorum.auth.MiniKdc;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class SaslKerberosAuthOverSSLTest extends ClientBase {

    private ClientX509Util clientX509Util;
    private File keytabFileForKerberosPrincipals;
    private File saslConfFile;

    private static MiniKdc kdc;
    private static File kdcWorkDir;
    private static Properties conf;



    @BeforeClass
    public static void setupKdc() {
        startMiniKdc();
    }

    @AfterClass
    public static void tearDownKdc() {
        stopMiniKdc();
        FileUtils.deleteQuietly(kdcWorkDir);
    }



    @Before
    @Override
    public void setUp() throws Exception {
        initSaslConfig();
        clientX509Util = setUpSSLWithNoAuth();

        String host = "localhost";
        int port = PortAssignment.unique();
        hostPort = host + ":" + port;

        serverFactory = ServerCnxnFactory.createFactory();
        serverFactory.configure(new InetSocketAddress(host, port), maxCnxns, true);

        super.setUp();
    }


    @After
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        clearSslSetting(clientX509Util);
        clearSaslConfig();
    }


    @Test
    public void testAuth() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.create("/path1", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            Thread.sleep(1000);
        } catch (KeeperException e) {
            fail("test failed :" + e);
        } finally {
            zk.close();
        }
    }


    public void initSaslConfig() throws Exception {

        // registering the server and client users in the KDC mini server
        keytabFileForKerberosPrincipals = new File(KerberosTestUtils.getKeytabFile());
        String clientPrincipal = KerberosTestUtils.getClientPrincipal();
        String serverPrincipal = KerberosTestUtils.getServerPrincipal();
        clientPrincipal = clientPrincipal.substring(0, clientPrincipal.lastIndexOf("@"));
        serverPrincipal = serverPrincipal.substring(0, serverPrincipal.lastIndexOf("@"));
        kdc.createPrincipal(keytabFileForKerberosPrincipals, clientPrincipal, serverPrincipal);

        // client-side SASL config
        System.setProperty(ZOOKEEPER_SERVER_PRINCIPAL, KerberosTestUtils.getServerPrincipal());
        System.setProperty(ENABLE_CLIENT_SASL_KEY, "true");
        System.setProperty(ZOOKEEPER_SERVER_REALM, KerberosTestUtils.getRealm());
        System.setProperty(LOGIN_CONTEXT_NAME_KEY, "ClientUsingKerberos");

        // server side SASL config
        System.setProperty("zookeeper.authProvider.1", "org.apache.zookeeper.server.auth.SASLAuthenticationProvider");

        // generating the SASL config to use (contains sections both for the client and the server)
        // note: we use "refreshKrb5Config=true" to refresh the kerberos config in the JVM,
        // making sure that we use the latest config even if other tests already have been executed
        // and initialized the kerberos client configs before)
        try {
            File tmpDir = createTmpDir();
            saslConfFile = new File(tmpDir, "jaas.conf");
            PrintWriter saslConf = new PrintWriter(new FileWriter(saslConfFile));
            saslConf.println("Server {");
            saslConf.println("  com.sun.security.auth.module.Krb5LoginModule required");
            saslConf.println("  storeKey=\"true\"");
            saslConf.println("  useTicketCache=\"false\"");
            saslConf.println("  useKeyTab=\"true\"");
            saslConf.println("  doNotPrompt=\"true\"");
            saslConf.println("  debug=\"true\"");
            saslConf.println("  refreshKrb5Config=\"true\"");
            saslConf.println("  keyTab=\"" + keytabFileForKerberosPrincipals.getAbsolutePath() + "\"");
            saslConf.println("  principal=\"" + KerberosTestUtils.getServerPrincipal() + "\";");
            saslConf.println("};");
            saslConf.println("ClientUsingKerberos {");
            saslConf.println("  com.sun.security.auth.module.Krb5LoginModule required");
            saslConf.println("  storeKey=\"false\"");
            saslConf.println("  useTicketCache=\"false\"");
            saslConf.println("  useKeyTab=\"true\"");
            saslConf.println("  doNotPrompt=\"true\"");
            saslConf.println("  debug=\"true\"");
            saslConf.println("  refreshKrb5Config=\"true\"");
            saslConf.println("  keyTab=\"" + keytabFileForKerberosPrincipals.getAbsolutePath() + "\"");
            saslConf.println("  principal=\"" + KerberosTestUtils.getClientPrincipal() + "\";");
            saslConf.println("};");
            saslConf.close();
            System.setProperty(Environment.JAAS_CONF_KEY, saslConfFile.getAbsolutePath());

        } catch (IOException e) {
            LOG.error("could not create tmp directory to hold JAAS conf file, test will fail...", e);
        }

        // refresh the SASL configuration in this JVM (making sure that we use the latest config
        // even if other tests already have been executed and initialized the SASL configs before)
        Configuration.getConfiguration().refresh();
    }

    public void clearSaslConfig() {
        FileUtils.deleteQuietly(keytabFileForKerberosPrincipals);
        FileUtils.deleteQuietly(saslConfFile);

        System.clearProperty(Environment.JAAS_CONF_KEY);
        System.clearProperty(ZK_SASL_CLIENT_USERNAME);
        System.clearProperty(ENABLE_CLIENT_SASL_KEY);
        System.clearProperty(LOGIN_CONTEXT_NAME_KEY);
        System.clearProperty("zookeeper.authProvider.1");

        System.clearProperty(ZOOKEEPER_SERVER_PRINCIPAL);
    }

    public ClientX509Util setUpSSLWithNoAuth() {
        String testDataPath = System.getProperty("test.data.dir", "src/test/resources/data");
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
        System.setProperty("zookeeper.ssl.clientAuth", "none");
        System.setProperty("zookeeper.ssl.quorum.clientAuth", "none");

        ClientX509Util x509Util = new ClientX509Util();
        System.setProperty(x509Util.getSslTruststoreLocationProperty(), testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(x509Util.getSslTruststorePasswdProperty(), "testpass");
        System.setProperty(x509Util.getSslKeystoreLocationProperty(), testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(x509Util.getSslKeystorePasswdProperty(), "testpass");

        return x509Util;
    }

    public void clearSslSetting(ClientX509Util clientX509Util) {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
        System.clearProperty(ZKClientConfig.SECURE_CLIENT);
        System.clearProperty(clientX509Util.getSslTruststoreLocationProperty());
        System.clearProperty(clientX509Util.getSslTruststorePasswdProperty());
        System.clearProperty(clientX509Util.getSslKeystoreLocationProperty());
        System.clearProperty(clientX509Util.getSslKeystorePasswdProperty());
        System.clearProperty("javax.net.debug");
        System.clearProperty("zookeeper.ssl.clientAuth");
        System.clearProperty("zookeeper.ssl.quorum.clientAuth");
        clientX509Util.close();
    }



    public static void startMiniKdc() {
        try {
            kdcWorkDir = createEmptyTestDir();
            conf = MiniKdc.createConf();
            conf.setProperty("debug", "true");

            kdc = new MiniKdc(conf, kdcWorkDir);
            kdc.start();
        } catch (Exception e) {
            throw new RuntimeException("failed to start MiniKdc", e);
        }

    }

    public static void stopMiniKdc() {
        if (kdc != null) {
            kdc.stop();
        }
    }

}
