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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class SaslAuthFailTest extends ClientBase {
    static {
        System.setProperty("zookeeper.authProvider.1","org.apache.zookeeper.server.auth.SASLAuthenticationProvider");
        System.setProperty("zookeeper.allowSaslFailedClients","true");

        try {
            File tmpDir = createTmpDir();
            File saslConfFile = new File(tmpDir, "jaas.conf");
            FileWriter fwriter = new FileWriter(saslConfFile);

            fwriter.write("" +
                    "Server {\n" +
                    "          org.apache.zookeeper.server.auth.DigestLoginModule required\n" +
                    "          user_super=\"test\";\n" +
                    "};\n" +
                    "Client {\n" +
                    "       org.apache.zookeeper.server.auth.DigestLoginModule required\n" +
                    "       username=\"super\"\n" +
                    "       password=\"test1\";\n" + // NOTE: wrong password ('test' != 'test1') : this is to test SASL authentication failure.
                    "};" + "\n");
            fwriter.close();
            System.setProperty("java.security.auth.login.config",saslConfFile.getAbsolutePath());
        }
        catch (IOException e) {
            // could not create tmp directory to hold JAAS conf file.
        }
    }

    private CountDownLatch authFailed = new CountDownLatch(1);

    private class MyWatcher extends CountdownWatcher {
        @Override
        public synchronized void process(WatchedEvent event) {
            if (event.getState() == KeeperState.AuthFailed) {
                authFailed.countDown();
            }
            else {
                super.process(event);
            }
        }
    }
    
    @Test
    public void testAuthFail() {
        try (ZooKeeper zk = createClient()) {
            zk.create("/path1", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            Assert.fail("Should have gotten exception.");
        } catch (Exception e) {
            // ok, exception as expected.
            LOG.info("Got exception as expected: " + e);
        }
    }

    @Test
    public void testBadSaslAuthNotifiesWatch() throws Exception {
        try (ZooKeeper ignored = createClient(new MyWatcher(), hostPort)) {
            // wait for authFailed event from client's EventThread.
            authFailed.await();
        }
    }
}
