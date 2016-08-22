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

package org.apache.zookeeper.test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.client.ZooKeeperSaslClient;
import org.junit.Assert;
import org.junit.Test;

public class SaslAuthDesignatedClientTest extends ClientBase {
    static {
        System.setProperty("zookeeper.authProvider.1","org.apache.zookeeper.server.auth.SASLAuthenticationProvider");
        System.setProperty(ZooKeeperSaslClient.LOGIN_CONTEXT_NAME_KEY, "MyZookeeperClient");

        try {
            File tmpDir = createTmpDir();
            File saslConfFile = new File(tmpDir, "jaas.conf");
            FileWriter fwriter = new FileWriter(saslConfFile);

            fwriter.write("" +
                "Server {\n" +
                "          org.apache.zookeeper.server.auth.DigestLoginModule required\n" +
                "          user_myuser=\"mypassword\";\n" +
                "};\n" +
                "Client {\n" + /* this 'Client' section has an incorrect password, but we're not configured
                                  to  use it (we're configured by the above System.setProperty(...LOGIN_CONTEXT_NAME_KEY...) to 
                                  use the 'MyZookeeperClient' section below, which has the correct password).*/
                "       org.apache.zookeeper.server.auth.DigestLoginModule required\n" +
                "       username=\"myuser\"\n" +
                "       password=\"wrongpassword\";\n" +
                "};" +
                "MyZookeeperClient {\n" +
                "       org.apache.zookeeper.server.auth.DigestLoginModule required\n" +
                "       username=\"myuser\"\n" +
                "       password=\"mypassword\";\n" +
                "};" + "\n");
            fwriter.close();
            System.setProperty("java.security.auth.login.config",saslConfFile.getAbsolutePath());
        }
        catch (IOException e) {
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
          Assert.fail("test failed :" + e);
        }
        finally {
            zk.close();
        }
    }

    @Test
    public void testSaslConfig() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.getChildren("/", false);
            Assert.assertFalse(zk.getSaslClient().
                clientTunneledAuthenticationInProgress());
            Assert.assertEquals(zk.getSaslClient().getSaslState(),
                ZooKeeperSaslClient.SaslState.COMPLETE);
            Assert.assertNotNull(
                javax.security.auth.login.Configuration.getConfiguration().
                    getAppConfigurationEntry("MyZookeeperClient"));
            Assert.assertSame(zk.getSaslClient().getLoginContext(),
                "MyZookeeperClient");
        } catch (KeeperException e) {
            Assert.fail("test failed :" + e);
        } finally {
            zk.close();
        }
    }


}
