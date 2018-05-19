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

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.auth.AuthenticationProvider;
import org.junit.Assert;
import org.junit.Test;

public class ForceAuthTest extends ClientBase {
    static {
        // Load the new and the old auth providers
        System.setProperty("zookeeper.authProvider.1", "org.apache.zookeeper.test.ForceAuthProvider");
    }

    private final CountDownLatch authFailed = new CountDownLatch(1);

    @Override
    protected TestableZooKeeper createClient(String hp)
    throws IOException, InterruptedException
    {
        MyWatcher watcher = new MyWatcher();
        return createClient(watcher, hp);
    }

    private class MyWatcher extends CountdownWatcher {
        @Override
        public synchronized void process(WatchedEvent event) {
            if (event.getState() == KeeperState.Disconnected) {
                authFailed.countDown();
            }
            else {
                super.process(event);
            }
        }
    }

    @Test
    public void testClientNotProvidingAuth() throws Exception {
        ZooKeeper zk = createClient();
        try {
            zk.create("/path1", null, Ids.CREATOR_ALL_ACL,
                    CreateMode.PERSISTENT);
            Assert.fail("Should get no auth exception");
        } catch(KeeperException.NoAuthException e) {
            if(!authFailed.await(CONNECTION_TIMEOUT,
                    TimeUnit.MILLISECONDS))
            {
                Assert.fail("Should have called my watcher");
            }
        }
        finally {
            zk.close();
        }
    }
    
    @Test
    public void testClientProvidingRequiredAuth() throws Exception {
        ZooKeeper zk = createClient();
        try {
            // Check if clients are able to transact when required auth
            // schemes are provided - create first
            String authProviderName = "ForceAuthProvider";
            zk.addAuthInfo(authProviderName, "foo".getBytes());
            String pathToCreate = "/path1";
            String retVal = zk.create(pathToCreate, authProviderName.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            Assert.assertEquals(pathToCreate, retVal);
            zk.close();

            // Check if clients are able to transact when required auth
            // schemes are provided - fetch next
            zk = createClient();
            zk.addAuthInfo(authProviderName, "foo".getBytes());
            byte[] retBytes = zk.getData("/path1", false, null);
            Assert.assertArrayEquals(authProviderName.getBytes(), retBytes);
        } finally {
            zk.close();
        }
    }
}
