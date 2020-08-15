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

import static org.apache.zookeeper.client.ZKClientConfig.LOGIN_CONTEXT_NAME_KEY;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


public class SaslDigestAuthOverSSLTest extends ClientBase {

    private ClientX509Util clientX509Util;
    private File saslConfFile;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        initSaslConfig();
        clientX509Util = setUpSSLWithNoAuth();

        String host = "localhost";
        int port = PortAssignment.unique();
        hostPort = host + ":" + port;

        serverFactory = ServerCnxnFactory.createFactory();
        serverFactory.configure(new InetSocketAddress(host, port), maxCnxns, -1, true);

        super.setUp();
    }


    @AfterEach
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


    public void initSaslConfig() {
        System.setProperty("zookeeper.authProvider.1", "org.apache.zookeeper.server.auth.SASLAuthenticationProvider");
        System.setProperty(LOGIN_CONTEXT_NAME_KEY, "ClientUsingDigest");
        try {
            File tmpDir = createTmpDir();
            saslConfFile = new File(tmpDir, "jaas.conf");
            PrintWriter saslConf = new PrintWriter(new FileWriter(saslConfFile));
            saslConf.println("Server {");
            saslConf.println("org.apache.zookeeper.server.auth.DigestLoginModule required");
            saslConf.println("user_super=\"test\";");
            saslConf.println("};");
            saslConf.println("ClientUsingDigest {");
            saslConf.println("org.apache.zookeeper.server.auth.DigestLoginModule required");
            saslConf.println("username=\"super\"");
            saslConf.println("password=\"test\";");
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
        FileUtils.deleteQuietly(saslConfFile);
        System.clearProperty(Environment.JAAS_CONF_KEY);
        System.clearProperty("zookeeper.authProvider.1");
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
        System.clearProperty("zookeeper.ssl.clientAuth");
        System.clearProperty("zookeeper.ssl.quorum.clientAuth");
        clientX509Util.close();
    }

}
