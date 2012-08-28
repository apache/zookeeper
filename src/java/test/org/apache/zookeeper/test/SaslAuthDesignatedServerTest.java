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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.ZooKeeperSaslServer;
import org.apache.zookeeper.JaasConfiguration;
import org.junit.Assert;
import org.junit.Test;

public class SaslAuthDesignatedServerTest extends ClientBase {
    public static int AUTHENTICATION_TIMEOUT = 30000;

    static {
        System.setProperty("zookeeper.authProvider.1","org.apache.zookeeper.server.auth.SASLAuthenticationProvider");
        System.setProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY, "MyZookeeperServer");

        JaasConfiguration conf = new JaasConfiguration();

        /* this 'Server' section has an incorrect password, but we're not configured
         * to  use it (we're configured by the above System.setProperty(...LOGIN_CONTEXT_NAME_KEY...)
         * to use the 'MyZookeeperServer' section below, which has the correct password).
         */
        conf.addSection("Server", "org.apache.zookeeper.server.auth.DigestLoginModule",
                        "user_myuser", "wrongpassword");

        conf.addSection("MyZookeeperServer", "org.apache.zookeeper.server.auth.DigestLoginModule",
                        "user_myuser", "mypassword");

        conf.addSection("Client", "org.apache.zookeeper.server.auth.DigestLoginModule",
                        "username", "myuser", "password", "mypassword");

        javax.security.auth.login.Configuration.setConfiguration(conf);
    }

    private AtomicInteger authFailed = new AtomicInteger(0);

    private class MyWatcher extends CountdownWatcher {
        volatile CountDownLatch authCompleted;

        @Override
        synchronized public void reset() {
            authCompleted = new CountDownLatch(1);
            super.reset();
        }

        @Override
        public synchronized void process(WatchedEvent event) {
            if (event.getState() == KeeperState.AuthFailed) {
                authFailed.incrementAndGet();
                authCompleted.countDown();
            } else if (event.getState() == KeeperState.SaslAuthenticated) {
                authCompleted.countDown();
            } else {
                super.process(event);
            }
        }
    }

    @Test
    public void testAuth() throws Exception {
        MyWatcher watcher = new MyWatcher();
        ZooKeeper zk = createClient(watcher);
        watcher.authCompleted.await(AUTHENTICATION_TIMEOUT, TimeUnit.MILLISECONDS);
        Assert.assertEquals(authFailed.get(), 0);

        try {
            zk.create("/path1", null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
          Assert.fail("test failed :" + e);
        }
        finally {
            zk.close();
        }
    }
}
