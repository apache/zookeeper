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
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class SessionTimeoutTest extends ClientBase {
    protected static final Logger LOG = LoggerFactory.getLogger(SessionTimeoutTest.class);

    private TestableZooKeeper zk;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        zk = createClient();
    }

    @Test
    public void testSessionExpiration() throws InterruptedException,
            KeeperException {
        final CountDownLatch expirationLatch = new CountDownLatch(1);
        Watcher watcher = new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if ( event.getState() == Event.KeeperState.Expired ) {
                    expirationLatch.countDown();
                }
            }
        };
        zk.exists("/foo", watcher);

        zk.getTestable().injectSessionExpiration();
        Assert.assertTrue(expirationLatch.await(5, TimeUnit.SECONDS));

        boolean gotException = false;
        try {
            zk.exists("/foo", false);
            Assert.fail("Should have thrown a SessionExpiredException");
        } catch (KeeperException.SessionExpiredException e) {
            // correct
            gotException = true;
        }
        Assert.assertTrue(gotException);
    }

    @Test
    public void testQueueEvent() throws InterruptedException,
            KeeperException {
        final CountDownLatch eventLatch = new CountDownLatch(1);
        Watcher watcher = new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if ( event.getType() == Event.EventType.NodeDataChanged ) {
                    if ( event.getPath().equals("/foo/bar") ) {
                        eventLatch.countDown();
                    }
                }
            }
        };
        zk.exists("/foo/bar", watcher);

        WatchedEvent event = new WatchedEvent(Watcher.Event.EventType.NodeDataChanged,
                Watcher.Event.KeeperState.SyncConnected, "/foo/bar");
        zk.getTestable().queueEvent(event);
        Assert.assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    }

    /**
     * Make sure ephemerals get cleaned up when session disconnects.
     */
    @Test
    public void testSessionDisconnect() throws KeeperException, InterruptedException, IOException {
        zk.create("/sdisconnect", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);
        assertNotNull("Ephemeral node has not been created", zk.exists("/sdisconnect", null));

        zk.close();

        zk = createClient();
        assertNull("Ephemeral node shouldn't exist after client disconnect", zk.exists("/sdisconnect", null));
    }

    /**
     * Make sure ephemerals are kept when session restores.
     */
    @Test
    public void testSessionRestore() throws KeeperException, InterruptedException, IOException {
        zk.create("/srestore", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);
        assertNotNull("Ephemeral node has not been created", zk.exists("/srestore", null));

        zk.disconnect();
        zk.close();

        zk = createClient();
        assertNotNull("Ephemeral node should be present when session is restored", zk.exists("/srestore", null));
    }

    /**
     * Make sure ephemerals are kept when server restarts.
     */
    @Test
    public void testSessionSurviveServerRestart() throws Exception {
        zk.create("/sdeath", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);
        assertNotNull("Ephemeral node has not been created", zk.exists("/sdeath", null));

        zk.disconnect();
        stopServer();
        startServer();
        zk = createClient();

        assertNotNull("Ephemeral node should be present when server restarted", zk.exists("/sdeath", null));
    }
}
