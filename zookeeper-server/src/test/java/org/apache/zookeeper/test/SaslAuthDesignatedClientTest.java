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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.Perms;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.client.ZooKeeperSaslClient;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.junit.jupiter.api.Test;

public class SaslAuthDesignatedClientTest extends ClientBase {

    static {
        System.setProperty("zookeeper.authProvider.1", "org.apache.zookeeper.server.auth.SASLAuthenticationProvider");
        System.setProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, "MyZookeeperClient");

        try {
            File tmpDir = createTmpDir();
            File saslConfFile = new File(tmpDir, "jaas.conf");
            FileWriter fwriter = new FileWriter(saslConfFile);

            fwriter.write(""
                                  + "Server {\n"
                                  + "          org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                                  + "          user_myuser=\"mypassword\";\n"
                                  + "};\n"
                                  + "Client {\n"
                                  + /* this 'Client' section has an incorrect password, but we're not configured
                                  to  use it (we're configured by the above System.setProperty(...LOGIN_CONTEXT_NAME_KEY...) to
                                  use the 'MyZookeeperClient' section below, which has the correct password).*/
                                  "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                                  + "       username=\"myuser\"\n"
                                  + "       password=\"wrongpassword\";\n"
                                  + "};"
                                  + "MyZookeeperClient {\n"
                                  + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
                                  + "       username=\"myuser\"\n"
                                  + "       password=\"mypassword\";\n"
                                  + "};"
                                  + "\n");
            fwriter.close();
            System.setProperty("java.security.auth.login.config", saslConfFile.getAbsolutePath());
        } catch (IOException e) {
            // could not create tmp directory to hold JAAS conf file : test will fail now.
        }
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

    @Test
    public void testSaslConfig() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.getChildren("/", false);
            assertFalse(zk.getSaslClient().
                                                         clientTunneledAuthenticationInProgress());
            assertEquals(zk.getSaslClient().getSaslState(), ZooKeeperSaslClient.SaslState.COMPLETE);
            assertNotNull(javax.security.auth.login.Configuration.getConfiguration().
                                                                                                   getAppConfigurationEntry("MyZookeeperClient"));
            assertSame(zk.getSaslClient().getLoginContext(), "MyZookeeperClient");
        } catch (KeeperException e) {
            fail("test failed :" + e);
        } finally {
            zk.close();
        }
    }

    @Test
    public void testReadAccessUser() throws Exception {
        System.setProperty("zookeeper.letAnySaslUserDoX", "anyone");
        ZooKeeper zk = createClient();
        List<ACL> aclList = new ArrayList<ACL>();
        ACL acl = new ACL(Perms.ADMIN | Perms.CREATE | Perms.WRITE | Perms.DELETE, new Id("sasl", "fakeuser"));
        ACL acl1 = new ACL(Perms.READ, new Id("sasl", "anyone"));
        aclList.add(acl);
        aclList.add(acl1);
        try {
            zk.create("/abc", "testData".getBytes(), aclList, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
            fail("Unable to create znode");
        }
        zk.close();
        Thread.sleep(100);

        // try to access it with different user (myuser)
        zk = createClient();

        try {
            zk.setData("/abc", "testData1".getBytes(), -1);
            fail("Should not be able to set data");
        } catch (KeeperException.NoAuthException e) {
            // success
        }

        try {
            byte[] bytedata = zk.getData("/abc", null, null);
            String data = new String(bytedata);
            assertTrue("testData".equals(data));
        } catch (KeeperException e) {
            fail("failed to get data");
        }

        zk.close();
        Thread.sleep(100);

        // disable Client Sasl
        System.setProperty(ZKClientConfig.ENABLE_CLIENT_SASL_KEY, "false");

        try {
            zk = createClient();
            try {
                zk.getData("/abc", null, null);
                fail("Should not be able to read data when not authenticated");
            } catch (KeeperException.NoAuthException e) {
                // success
            }
            zk.close();
        } finally {
            // enable Client Sasl
            System.setProperty(ZKClientConfig.ENABLE_CLIENT_SASL_KEY, "true");
        }
    }

}
