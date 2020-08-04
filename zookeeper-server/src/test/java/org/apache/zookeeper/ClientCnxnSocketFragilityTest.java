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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.ClientCnxn.Packet;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

public class ClientCnxnSocketFragilityTest extends QuorumPeerTestBase {

    private static final int SERVER_COUNT = 3;

    private static final int SESSION_TIMEOUT = 40000;

    public static final int CONNECTION_TIMEOUT = 30000;

    private final UnsafeCoordinator unsafeCoordinator = new UnsafeCoordinator();

    private volatile CustomZooKeeper zk = null;

    private volatile FragileClientCnxnSocketNIO socket = null;

    private volatile CustomClientCnxn cnxn = null;

    private String getCxnString(int[] clientPorts) {
        StringBuffer hostPortBuffer = new StringBuffer();
        for (int i = 0; i < clientPorts.length; i++) {
            hostPortBuffer.append("127.0.0.1:");
            hostPortBuffer.append(clientPorts[i]);
            if (i != (clientPorts.length - 1)) {
                hostPortBuffer.append(',');
            }
        }
        return hostPortBuffer.toString();
    }

    private void closeZookeeper(ZooKeeper zk) {
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                LOG.info("closeZookeeper is fired");
                zk.close();
            } catch (InterruptedException e) {
            }
        });
    }

    @Test
    public void testClientCnxnSocketFragility() throws Exception {
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET,
                FragileClientCnxnSocketNIO.class.getName());
        System.setProperty(ZKClientConfig.ZOOKEEPER_REQUEST_TIMEOUT, "1000");
        final int[] clientPorts = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique() + ":"
                    + PortAssignment.unique() + ":participant;127.0.0.1:" + clientPorts[i];
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();
        MainThread[] mt = new MainThread[SERVER_COUNT];

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection, false);
            mt[i].start();
        }

        // Ensure server started
        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], CONNECTION_TIMEOUT),
                    "waiting for server " + i + " being up");
        }
        String path = "/testClientCnxnSocketFragility";
        String data = "balabala";
        ClientWatcher watcher = new ClientWatcher();
        zk = new CustomZooKeeper(getCxnString(clientPorts), SESSION_TIMEOUT, watcher);
        watcher.watchFor(zk);

        // Let's see some successful operations
        zk.create(path, data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(new String(zk.getData(path, false, new Stat())), data);
        assertTrue(!watcher.isSessionExpired());

        // Let's make a broken operation
        socket.mute();
        boolean catchKeeperException = false;
        try {
            zk.getData(path, false, new Stat());
        } catch (KeeperException e) {
            catchKeeperException = true;
            assertFalse(e instanceof KeeperException.SessionExpiredException);
        }
        socket.unmute();
        assertTrue(catchKeeperException);
        assertTrue(!watcher.isSessionExpired());

        GetDataRetryForeverBackgroundTask retryForeverGetData =
                new GetDataRetryForeverBackgroundTask(zk, path);
        retryForeverGetData.startTask();
        // Let's make a broken network
        socket.mute();

        // Let's attempt to close ZooKeeper
        cnxn.attemptClose();

        // Wait some time to expect continuous reconnecting.
        // We try to make reconnecting hit the unsafe region.
        cnxn.waitUntilHitUnsafeRegion();

        // close zk with timeout 1000 milli seconds
        closeZookeeper(zk);
        TimeUnit.MILLISECONDS.sleep(3000);

        // Since we already close zookeeper, we expect that the zk should not be alive.
        assertTrue(!zk.isAlive());
        assertTrue(!watcher.isSessionExpired());

        retryForeverGetData.syncCloseTask();
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

    class GetDataRetryForeverBackgroundTask extends Thread {
        private volatile boolean alive;
        private final CustomZooKeeper zk;
        private final String path;

        GetDataRetryForeverBackgroundTask(CustomZooKeeper zk, String path) {
            this.alive = false;
            this.zk = zk;
            this.path = path;
            // marked as daemon to avoid exhausting CPU
            setDaemon(true);
        }

        void startTask() {
            alive = true;
            start();
        }

        void syncCloseTask() throws InterruptedException {
            alive = false;
            join();
        }

        @Override
        public void run() {
            while (alive) {
                try {
                    zk.getData(path, false, new Stat());
                    // sleep for a while to avoid exhausting CPU
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (Exception e) {
                    LOG.info("zookeeper getData failed on path {}", path);
                }
            }
        }
    }

    public static class FragileClientCnxnSocketNIO extends ClientCnxnSocketNIO {

        private volatile boolean mute;

        public FragileClientCnxnSocketNIO(ZKClientConfig clientConfig) throws IOException {
            super(clientConfig);
            mute = false;
        }

        synchronized void mute() {
            if (!mute) {
                LOG.info("Fire socket mute");
                mute = true;
            }
        }

        synchronized void unmute() {
            if (mute) {
                LOG.info("Fire socket unmute");
                mute = false;
            }
        }

        @Override
        void doTransport(int waitTimeOut, Queue<Packet> pendingQueue, ClientCnxn cnxn)
                throws IOException, InterruptedException {
            if (mute) {
                throw new IOException("Socket is mute");
            }
            super.doTransport(waitTimeOut, pendingQueue, cnxn);
        }

        @Override
        void connect(InetSocketAddress addr) throws IOException {
            if (mute) {
                throw new IOException("Socket is mute");
            }
            super.connect(addr);
        }
    }

    class ClientWatcher implements Watcher {

        private ZooKeeper zk;

        private boolean sessionExpired = false;

        void watchFor(ZooKeeper zk) {
            this.zk = zk;
        }

        @Override
        public void process(WatchedEvent event) {
            LOG.info("Watcher got {}", event);
            if (event.getState() == KeeperState.Expired) {
                sessionExpired = true;
            }
        }

        boolean isSessionExpired() {
            return sessionExpired;
        }
    }

    // Coordinate to construct the risky scenario.
    class UnsafeCoordinator {

        private CountDownLatch syncLatch = new CountDownLatch(2);

        void sync(boolean closing) {
            LOG.info("Attempt to sync with {}", closing);
            if (closing) {
                syncLatch.countDown();
                try {
                    syncLatch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    class CustomClientCnxn extends ClientCnxn {

        private volatile boolean closing = false;

        private volatile boolean hitUnsafeRegion = false;

        public CustomClientCnxn(
            String chrootPath,
            HostProvider hostProvider,
            int sessionTimeout,
            ZKClientConfig zkClientConfig,
            Watcher defaultWatcher,
            ClientCnxnSocket clientCnxnSocket,
            boolean canBeReadOnly
        ) throws IOException {
            super(
                chrootPath,
                hostProvider,
                sessionTimeout,
                zkClientConfig,
                defaultWatcher,
                clientCnxnSocket,
                canBeReadOnly);
        }

        void attemptClose() {
            closing = true;
        }

        void waitUntilHitUnsafeRegion() {
            while (!hitUnsafeRegion) {
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                }
            }
        }

        @Override
        protected void onConnecting(InetSocketAddress addr) {
            if (closing) {
                LOG.info("Attempt to connnecting {} {} {}", addr, closing, state);
                ///////// Unsafe Region ////////
                // Slow down and zoom out the unsafe point to make risk
                // The unsafe point is that startConnect happens after sendThread.close
                hitUnsafeRegion = true;
                unsafeCoordinator.sync(closing);
                ////////////////////////////////
            }
        }

        @Override
        public void disconnect() {
            assertTrue(closing);
            LOG.info("Attempt to disconnecting client for session: 0x{} {} {}", Long.toHexString(getSessionId()), closing, state);
            sendThread.close();
            ///////// Unsafe Region ////////
            unsafeCoordinator.sync(closing);
            ////////////////////////////////
            try {
                sendThread.join();
            } catch (InterruptedException ex) {
                LOG.warn("Got interrupted while waiting for the sender thread to close", ex);
            }
            eventThread.queueEventOfDeath();
            if (zooKeeperSaslClient != null) {
                zooKeeperSaslClient.shutdown();
            }
        }
    }

    class CustomZooKeeper extends ZooKeeper {

        public CustomZooKeeper(String connectString, int sessionTimeout, Watcher watcher) throws IOException {
            super(connectString, sessionTimeout, watcher);
        }

        public boolean isAlive() {
            return cnxn.getState().isAlive();
        }

        ClientCnxn createConnection(
            String chrootPath,
            HostProvider hostProvider,
            int sessionTimeout,
            ZKClientConfig clientConfig,
            Watcher defaultWatcher,
            ClientCnxnSocket clientCnxnSocket,
            boolean canBeReadOnly
        ) throws IOException {
            assertTrue(clientCnxnSocket instanceof FragileClientCnxnSocketNIO);
            socket = (FragileClientCnxnSocketNIO) clientCnxnSocket;
            ClientCnxnSocketFragilityTest.this.cnxn = new CustomClientCnxn(
                chrootPath,
                hostProvider,
                sessionTimeout,
                clientConfig,
                defaultWatcher,
                clientCnxnSocket,
                canBeReadOnly);
            return ClientCnxnSocketFragilityTest.this.cnxn;
        }
    }
}