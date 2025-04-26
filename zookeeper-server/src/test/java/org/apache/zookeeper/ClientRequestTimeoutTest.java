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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.apache.jute.Record;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class ClientRequestTimeoutTest extends QuorumPeerTestBase {

    private static final int SERVER_COUNT = 3;
    private boolean dropPacket = false;
    private int dropPacketType = ZooDefs.OpCode.create;
    private boolean capturePacket = false;
    private int capturePacketType = ZooDefs.OpCode.create;
    private ClientCnxn.Packet capturedPacket = null;

    @Test
    @Timeout(value = 120)
    public void testClientRequestTimeout() throws Exception {
        int requestTimeOut = 15000;
        System.setProperty("zookeeper.request.timeout", Integer.toString(requestTimeOut));
        final int[] clientPorts = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique() + ":" + PortAssignment.unique()
                     + ":participant;127.0.0.1:" + clientPorts[i];
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();
        MainThread[] mt = new MainThread[SERVER_COUNT];

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection, false);
            mt[i].start();
        }

        // ensure server started
        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], CONNECTION_TIMEOUT),
                "waiting for server " + i + " being up");
        }

        CountdownWatcher watch1 = new CountdownWatcher();
        CustomZooKeeper zk = new CustomZooKeeper(getCxnString(clientPorts), ClientBase.CONNECTION_TIMEOUT, watch1);
        watch1.waitForConnected(ClientBase.CONNECTION_TIMEOUT);

        String data = "originalData";
        // lets see one successful operation
        zk.create("/clientHang1", data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);

        // now make environment for client hang
        dropPacket = true;
        dropPacketType = ZooDefs.OpCode.create;

        // Test synchronous API
        try {
            zk.create("/clientHang2", data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("KeeperException is expected.");
        } catch (KeeperException exception) {
            assertEquals(KeeperException.Code.REQUESTTIMEOUT.intValue(), exception.code().intValue());
        }

        // do cleanup
        zk.close();
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

    @Test
    void testClientRequestTimeoutTime() throws Exception {
        long requestTimeout = TimeUnit.SECONDS.toMillis(5);
        System.setProperty("zookeeper.request.timeout", Long.toString(requestTimeout));

        CustomZooKeeper zk = null;
        int clientPort = PortAssignment.unique();
        MainThread mainThread = new MainThread(0, clientPort, "", false);
        mainThread.start();
        try {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT),
                    "waiting for server 0 being up");

            CountdownWatcher watch = new CountdownWatcher();
            zk = new CustomZooKeeper(getCxnString(new int[]{clientPort}), ClientBase.CONNECTION_TIMEOUT, watch);
            watch.waitForConnected(ClientBase.CONNECTION_TIMEOUT);

            dropPacket = true;
            dropPacketType = ZooDefs.OpCode.create;

            String data = "originalData";
            long startTime = Time.currentElapsedTime();
            try {
                zk.create("/testClientRequestTimeout", data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                fail("KeeperException is expected.");
            } catch (KeeperException exception) {
                long cost = Time.currentElapsedTime() - startTime;
                assertEquals(KeeperException.Code.REQUESTTIMEOUT, exception.code());
                LOG.info("testClientRequestTimeoutTime cost:{}", cost);
                assertThat(cost, greaterThanOrEqualTo(requestTimeout));
                assertThat(cost, lessThan(requestTimeout + 500));
            }
        } finally {
            mainThread.shutdown();
            if (zk != null) {
                zk.close();
            }
        }
    }


    @Test
    void testClientRequestTimeoutTimeSimulatingSpuriousWakeup() throws Exception {
        long requestTimeout = TimeUnit.SECONDS.toMillis(5);
        System.setProperty("zookeeper.request.timeout", Long.toString(requestTimeout));

        CustomZooKeeper zk = null;
        int clientPort = PortAssignment.unique();
        MainThread mainThread = new MainThread(0, clientPort, "", false);
        mainThread.start();
        try {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT),
                    "waiting for server 0 being up");

            CountdownWatcher watch = new CountdownWatcher();
            zk = new CustomZooKeeper(getCxnString(new int[]{clientPort}), ClientBase.CONNECTION_TIMEOUT, watch);
            watch.waitForConnected(ClientBase.CONNECTION_TIMEOUT);

            dropPacket = true;
            dropPacketType = ZooDefs.OpCode.create;
            capturePacket = true;
            capturePacketType = ZooDefs.OpCode.create;

            // Simulating spurious wakeup
            new Thread(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(requestTimeout / 2);
                    if (capturedPacket != null) {
                        synchronized (capturedPacket) {
                            capturedPacket.notifyAll();
                        }
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).start();

            String data = "originalData";
            long startTime = Time.currentElapsedTime();
            try {
                zk.create("/testClientRequestTimeout", data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                fail("KeeperException is expected.");
            } catch (KeeperException exception) {
                long cost = Time.currentElapsedTime() - startTime;
                assertEquals(KeeperException.Code.REQUESTTIMEOUT, exception.code());
                LOG.info("testClientRequestTimeoutTimeSimulatingSpuriousWakeup cost:{}", cost);
                assertThat(cost, greaterThanOrEqualTo(requestTimeout));
                assertThat(cost, lessThan(requestTimeout + 500));
            }
        } finally {
            capturePacket = false;
            capturedPacket = null;
            mainThread.shutdown();
            if (zk != null) {
                zk.close();
            }
        }
    }

    /**
     * @return connection string in the form of
     *         127.0.0.1:port1,127.0.0.1:port2,127.0.0.1:port3
     */
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

    class CustomClientCnxn extends ClientCnxn {

        CustomClientCnxn(
            HostProvider hostProvider,
            int sessionTimeout,
            long newSessionTimeout,
            ZKClientConfig clientConfig,
            Watcher defaultWatcher,
            ClientCnxnSocket clientCnxnSocket,
            long sessionId,
            byte[] sessionPasswd,
            boolean canBeReadOnly
        ) throws IOException {
            super(
                hostProvider,
                sessionTimeout,
                newSessionTimeout,
                clientConfig,
                defaultWatcher,
                clientCnxnSocket,
                sessionId,
                sessionPasswd,
                canBeReadOnly);
        }

        @Override
        public void finishPacket(Packet p) {
            if (dropPacket && p.requestHeader.getType() == dropPacketType) {
                // do nothing, just return, it is the same as packet is dropped
                // by the network
                return;
            }
            super.finishPacket(p);
        }

        @Override
        public Packet queuePacket(
                RequestHeader h,
                ReplyHeader r,
                Record request,
                Record response,
                AsyncCallback cb,
                String clientPath,
                String serverPath,
                Object ctx,
                ZooKeeper.WatchRegistration watchRegistration,
                WatchDeregistration watchDeregistration) {
            Packet packet = super.queuePacket(h, r, request, response, cb, clientPath, serverPath,
                    ctx, watchRegistration, watchDeregistration);

            if (capturePacket && h != null && h.getType() == capturePacketType) {
                capturedPacket = packet;
            }
            return packet;
        }

    }

    class CustomZooKeeper extends ZooKeeper {

        public CustomZooKeeper(String connectString, int sessionTimeout, Watcher watcher) throws IOException {
            super(connectString, sessionTimeout, watcher);
        }

        @Override
        ClientCnxn createConnection(
            HostProvider hostProvider,
            int sessionTimeout,
            long newSessionTimeout,
            ZKClientConfig clientConfig,
            Watcher defaultWatcher,
            ClientCnxnSocket clientCnxnSocket,
            long sessionId,
            byte[] sessionPasswd,
            boolean canBeReadOnly
        ) throws IOException {
            return new CustomClientCnxn(
                hostProvider,
                sessionTimeout,
                newSessionTimeout,
                clientConfig,
                defaultWatcher,
                clientCnxnSocket,
                sessionId,
                sessionPasswd,
                canBeReadOnly);
        }

    }

}
