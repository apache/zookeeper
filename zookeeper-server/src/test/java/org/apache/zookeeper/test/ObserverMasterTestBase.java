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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.server.util.PortForwarder;

public class ObserverMasterTestBase extends QuorumPeerTestBase implements Watcher {

    protected CountDownLatch latch;
    protected ZooKeeper zk;
    protected int CLIENT_PORT_QP1;
    protected int CLIENT_PORT_QP2;
    protected int CLIENT_PORT_OBS;
    protected int OM_PORT;
    protected MainThread q1;
    protected MainThread q2;
    protected MainThread q3;
    protected WatchedEvent lastEvent = null;

    protected PortForwarder setUp(final int omProxyPort, final Boolean testObserverMaster) throws IOException {
        ClientBase.setupTestEnv();
        final int PORT_QP1 = PortAssignment.unique();
        final int PORT_QP2 = PortAssignment.unique();
        final int PORT_OBS = PortAssignment.unique();
        final int PORT_QP_LE1 = PortAssignment.unique();
        final int PORT_QP_LE2 = PortAssignment.unique();
        final int PORT_OBS_LE = PortAssignment.unique();

        CLIENT_PORT_QP1 = PortAssignment.unique();
        CLIENT_PORT_QP2 = PortAssignment.unique();
        CLIENT_PORT_OBS = PortAssignment.unique();

        OM_PORT = PortAssignment.unique();

        String quorumCfgSection =
                "server.1=127.0.0.1:" + (PORT_QP1)
                        + ":" + (PORT_QP_LE1) + ";" +  CLIENT_PORT_QP1
                        + "\nserver.2=127.0.0.1:" + (PORT_QP2)
                        + ":" + (PORT_QP_LE2) + ";" + CLIENT_PORT_QP2
                        + "\nserver.3=127.0.0.1:" + (PORT_OBS)
                        + ":" + (PORT_OBS_LE) + ":observer" + ";" + CLIENT_PORT_OBS;

        String extraCfgs = testObserverMaster ? String.format("observerMasterPort=%d%n", OM_PORT) : "";
        String extraCfgsObs = testObserverMaster ? String.format("observerMasterPort=%d%n", omProxyPort <= 0 ? OM_PORT : omProxyPort) : "";

        PortForwarder forwarder = null;
        if (testObserverMaster && omProxyPort >= 0) {
            forwarder = new PortForwarder(omProxyPort, OM_PORT);
        }

        q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection, extraCfgs);
        q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection, extraCfgs);
        q3 = new MainThread(3, CLIENT_PORT_OBS, quorumCfgSection, extraCfgsObs);
        q1.start();
        q2.start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1, CONNECTION_TIMEOUT),
                "waiting for server 1 being up");
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2, CONNECTION_TIMEOUT),
                "waiting for server 2 being up");
        return forwarder;
    }

    protected void shutdown() throws InterruptedException {
        LOG.info("Shutting down all servers");

        zk.close();

        q1.shutdown();
        q2.shutdown();
        q3.shutdown();

        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1, ClientBase.CONNECTION_TIMEOUT),
                "Waiting for server 1 to shut down");
        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2, ClientBase.CONNECTION_TIMEOUT),
                "Waiting for server 2 to shut down");
        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_OBS, ClientBase.CONNECTION_TIMEOUT),
                "Waiting for server 3 to shut down");
    }

    /**
     * Implementation of watcher interface.
     */
    public void process(WatchedEvent event) {
        lastEvent = event;
        if (latch != null) {
            latch.countDown();
        }
        LOG.info("Latch got event :: {}", event);
    }
}
