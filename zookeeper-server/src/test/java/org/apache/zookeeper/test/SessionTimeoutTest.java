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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionTimeoutTest extends ClientBase {

    protected static final Logger LOG = LoggerFactory.getLogger(SessionTimeoutTest.class);

    private TestableZooKeeper zk;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        zk = createClient();
    }

    private static class BusyServer implements AutoCloseable {
        private final ServerSocket server;
        private final Socket client;

        public BusyServer() throws IOException {
            this.server = new ServerSocket(0, 1);
            this.client = new Socket("127.0.0.1", server.getLocalPort());
        }

        public int getLocalPort() {
            return server.getLocalPort();
        }

        public String getHostPort() {
            return String.format("127.0.0.1:%d", getLocalPort());
        }

        @Override
        public void close() throws Exception {
            client.close();
            server.close();
        }
    }

    @Test
    public void testSessionExpiration() throws InterruptedException, KeeperException {
        final CountDownLatch expirationLatch = new CountDownLatch(1);
        Watcher watcher = event -> {
            if (event.getState() == Watcher.Event.KeeperState.Expired) {
                expirationLatch.countDown();
            }
        };
        zk.exists("/foo", watcher);

        zk.getTestable().injectSessionExpiration();
        assertTrue(expirationLatch.await(5, TimeUnit.SECONDS));

        boolean gotException = false;
        try {
            zk.exists("/foo", false);
            fail("Should have thrown a SessionExpiredException");
        } catch (KeeperException.SessionExpiredException e) {
            // correct
            gotException = true;
        }
        assertTrue(gotException);
    }

    @Test
    public void testSessionRecoveredAfterMultipleFailedAttempts() throws Exception {
        // stop client also to gain less distraction
        zk.close();

        try (BusyServer busyServer = new BusyServer()) {
            List<String> servers = Arrays.asList(
                    busyServer.getHostPort(),
                    busyServer.getHostPort(),
                    hostPort,
                    busyServer.getHostPort(),
                    busyServer.getHostPort(),
                    busyServer.getHostPort()
                    );
            String connectString = String.join(",", servers);

            zk = createClient(new CountdownWatcher(), connectString);
            stopServer();

            // Wait beyond connectTimeout but not sessionTimeout.
            Thread.sleep(zk.getSessionTimeout() / 2);

            CompletableFuture<Void> connected = new CompletableFuture<>();
            zk.register(event -> {
                if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                    connected.complete(null);
                } else {
                    connected.completeExceptionally(new KeeperException.SessionExpiredException());
                }
            });

            startServer();
            connected.join();
        }
    }

    @Test
    public void testSessionExpirationAfterAllServerDown() throws Exception {
        // stop client also to gain less distraction
        zk.close();

        // small connection timeout to gain quick ci feedback
        int sessionTimeout = 3000;
        CompletableFuture<Void> expired = new CompletableFuture<>();
        zk = createClient(new CountdownWatcher(), hostPort, sessionTimeout);
        zk.register(event -> {
            if (event.getState() == Watcher.Event.KeeperState.Expired) {
                expired.complete(null);
            }
        });
        stopServer();
        expired.join();
        assertThrows(KeeperException.SessionExpiredException.class, () -> zk.exists("/", null));
    }

    @Test
    public void testSessionExpirationWhenNoServerUp() throws Exception {
        // stop client also to gain less distraction
        zk.close();

        stopServer();

        // small connection timeout to gain quick ci feedback
        int sessionTimeout = 3000;
        CompletableFuture<Void> expired = new CompletableFuture<>();
        new TestableZooKeeper(hostPort, sessionTimeout, event -> {
            if (event.getState() == Watcher.Event.KeeperState.Expired) {
                expired.complete(null);
            }
        });
        expired.join();
        assertThrows(KeeperException.SessionExpiredException.class, () -> zk.exists("/", null));
    }

    @Test
    public void testQueueEvent() throws InterruptedException, KeeperException {
        final CountDownLatch eventLatch = new CountDownLatch(1);
        Watcher watcher = event -> {
            if (event.getType() == Watcher.Event.EventType.NodeDataChanged) {
                if (event.getPath().equals("/foo/bar")) {
                    eventLatch.countDown();
                }
            }
        };
        zk.exists("/foo/bar", watcher);

        WatchedEvent event = new WatchedEvent(Watcher.Event.EventType.NodeDataChanged, Watcher.Event.KeeperState.SyncConnected, "/foo/bar");
        zk.getTestable().queueEvent(event);
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    }

    /**
     * Make sure ephemerals get cleaned up when session disconnects.
     */
    @Test
    public void testSessionDisconnect() throws KeeperException, InterruptedException, IOException {
        zk.create("/sdisconnect", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        assertNotNull(zk.exists("/sdisconnect", null), "Ephemeral node has not been created");

        zk.close();

        zk = createClient();
        assertNull(zk.exists("/sdisconnect", null), "Ephemeral node shouldn't exist after client disconnect");
    }

    /**
     * Make sure ephemerals are kept when session restores.
     */
    @Test
    public void testSessionRestore() throws KeeperException, InterruptedException, IOException {
        zk.create("/srestore", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        assertNotNull(zk.exists("/srestore", null), "Ephemeral node has not been created");

        zk.disconnect();
        zk.close();

        zk = createClient();
        assertNotNull(zk.exists("/srestore", null), "Ephemeral node should be present when session is restored");
    }

    /**
     * Make sure ephemerals are kept when server restarts.
     */
    @Test
    public void testSessionSurviveServerRestart() throws Exception {
        zk.create("/sdeath", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        assertNotNull(zk.exists("/sdeath", null), "Ephemeral node has not been created");

        zk.disconnect();
        stopServer();
        startServer();
        zk = createClient();

        assertNotNull(zk.exists("/sdeath", null), "Ephemeral node should be present when server restarted");
    }

}
