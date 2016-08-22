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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.client.ZooKeeperSaslClient;
import org.junit.Assert;
import org.junit.Test;

public class SaslAuthFailDesignatedClientTest extends ClientBase {
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
                "Client {\n" + /* this 'Client' section has the correct password, but we're not configured
                                  to  use it (we're configured by the above System.setProperty(...LOGIN_CONTEXT_NAME_KEY...) to 
                                  use the 'MyZookeeperClient' section, which has an incorrect password).*/
                "       org.apache.zookeeper.server.auth.DigestLoginModule required\n" +
                "       username=\"myuser\"\n" +
                "       password=\"mypassword\";\n" +
                "};" +
                "MyZookeeperClient {\n" +
                "       org.apache.zookeeper.server.auth.DigestLoginModule required\n" +
                "       username=\"myuser\"\n" +
                "       password=\"wrongpassword\";\n" +
                "};" + "\n");
            fwriter.close();
            System.setProperty("java.security.auth.login.config",saslConfFile.getAbsolutePath());
        }
        catch (IOException e) {
            // could not create tmp directory to hold JAAS conf file : test will fail now.
        }
    }

    private AtomicInteger authFailed = new AtomicInteger(0);

    private class MyWatcher extends CountdownWatcher {
        @Override
        public synchronized void process(WatchedEvent event) {
            if (event.getState() == KeeperState.AuthFailed) {
                authFailed.incrementAndGet();
            }
            else {
                super.process(event);
            }
        }
    }

    @Test
    public void testAuth() throws Exception {
        // Cannot use createClient here because server may close session before 
        // JMXEnv.ensureAll is called which will fail the test case
        CountdownWatcher watcher = new CountdownWatcher();
        TestableZooKeeper zk = new TestableZooKeeper(hostPort, CONNECTION_TIMEOUT, watcher);
        if (!watcher.clientConnected.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS))
        {
            Assert.fail("Unable to connect to server");
        }
        try {
            zk.create("/path1", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            Assert.fail("Should have gotten exception.");
        } catch (KeeperException e) {
            // ok, exception as expected.
            LOG.info("Got exception as expected: " + e);
        }
        finally {
            zk.close();
        }
    }
}
