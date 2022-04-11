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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.management.Attribute;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.DummyWatcher;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.admin.Commands;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.util.PortForwarder;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ObserverMasterTest extends ObserverMasterTestBase {

    protected static final Logger LOG = LoggerFactory.getLogger(ObserverMasterTest.class);

    /**
     * This test ensures two things:
     * 1. That Observers can successfully proxy requests to the ensemble.
     * 2. That Observers don't participate in leader elections.
     * The second is tested by constructing an ensemble where a leader would
     * be elected if and only if an Observer voted.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testObserver(boolean testObserverMaster) throws Exception {
        // We expect two notifications before we want to continue
        latch = new CountDownLatch(2);
        setUp(-1, testObserverMaster);
        q3.start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_OBS, CONNECTION_TIMEOUT),
                "waiting for server 3 being up");

        validateObserverSyncTimeMetrics();

        if (testObserverMaster) {
            int masterPort = q3.getQuorumPeer().observer.getSocket().getPort();
            LOG.info("port {} {}", masterPort, OM_PORT);
            assertEquals(masterPort, OM_PORT, "observer failed to connect to observer master");
        }

        zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_OBS, ClientBase.CONNECTION_TIMEOUT, this);
        zk.create("/obstest", "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        // Assert that commands are getting forwarded correctly
        assertEquals(new String(zk.getData("/obstest", null, null)), "test");

        // Now check that other commands don't blow everything up
        zk.sync("/", null, null);
        zk.setData("/obstest", "test2".getBytes(), -1);
        zk.getChildren("/", false);

        assertEquals(zk.getState(), States.CONNECTED);

        LOG.info("Shutting down server 2");
        // Now kill one of the other real servers
        q2.shutdown();

        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2, ClientBase.CONNECTION_TIMEOUT),
                "Waiting for server 2 to shut down");

        LOG.info("Server 2 down");

        // Now the resulting ensemble shouldn't be quorate
        latch.await();
        assertNotSame(KeeperState.SyncConnected, lastEvent.getState(), "Client is still connected to non-quorate cluster");

        LOG.info("Latch returned");

        try {
            assertNotEquals("Shouldn't get a response when cluster not quorate!", "test", new String(zk.getData("/obstest", null, null)));
        } catch (ConnectionLossException c) {
            LOG.info("Connection loss exception caught - ensemble not quorate (this is expected)");
        }

        latch = new CountDownLatch(1);

        LOG.info("Restarting server 2");

        // Bring it back
        //q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection, extraCfgs);
        q2.start();

        LOG.info("Waiting for server 2 to come up");
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2, CONNECTION_TIMEOUT),
                "waiting for server 2 being up");

        LOG.info("Server 2 started, waiting for latch");

        latch.await();
        // It's possible our session expired - but this is ok, shows we
        // were able to talk to the ensemble
        assertTrue((KeeperState.SyncConnected == lastEvent.getState() || KeeperState.Expired == lastEvent.getState()),
                "Client didn't reconnect to quorate ensemble (state was" + lastEvent.getState() + ")");

        LOG.info("perform a revalidation test");
        int leaderProxyPort = PortAssignment.unique();
        int obsProxyPort = PortAssignment.unique();
        int leaderPort = q1.getQuorumPeer().leader == null ? CLIENT_PORT_QP2 : CLIENT_PORT_QP1;
        PortForwarder leaderPF = new PortForwarder(leaderProxyPort, leaderPort);

        latch = new CountDownLatch(1);
        ZooKeeper client = new ZooKeeper(String.format("127.0.0.1:%d,127.0.0.1:%d", leaderProxyPort, obsProxyPort), ClientBase.CONNECTION_TIMEOUT, this);
        latch.await();
        client.create("/revalidtest", "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        assertNotNull(client.exists("/revalidtest", null), "Read-after write failed");

        latch = new CountDownLatch(2);
        PortForwarder obsPF = new PortForwarder(obsProxyPort, CLIENT_PORT_OBS);
        try {
            leaderPF.shutdown();
        } catch (Exception e) {
            // ignore?
        }
        latch.await();
        assertEquals(new String(client.getData("/revalidtest", null, null)), "test");
        client.close();
        obsPF.shutdown();

        shutdown();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testRevalidation(boolean testObserverMaster) throws Exception {
        setUp(-1, testObserverMaster);
        q3.start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_OBS, CONNECTION_TIMEOUT),
                "waiting for server 3 being up");
        final int leaderProxyPort = PortAssignment.unique();
        final int obsProxyPort = PortAssignment.unique();

        int leaderPort = q1.getQuorumPeer().leader == null ? CLIENT_PORT_QP2 : CLIENT_PORT_QP1;
        PortForwarder leaderPF = new PortForwarder(leaderProxyPort, leaderPort);

        latch = new CountDownLatch(1);
        zk = new ZooKeeper(String.format("127.0.0.1:%d,127.0.0.1:%d", leaderProxyPort, obsProxyPort), ClientBase.CONNECTION_TIMEOUT, this);
        latch.await();
        zk.create("/revalidtest", "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        assertNotNull(zk.exists("/revalidtest", null), "Read-after write failed");

        latch = new CountDownLatch(2);
        PortForwarder obsPF = new PortForwarder(obsProxyPort, CLIENT_PORT_OBS);
        try {
            leaderPF.shutdown();
        } catch (Exception e) {
            // ignore?
        }
        latch.await();
        assertEquals(new String(zk.getData("/revalidtest", null, null)), "test");
        obsPF.shutdown();

        shutdown();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testInOrderCommits(boolean testObserverMaster) throws Exception {
        setUp(-1, testObserverMaster);

        zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP1, ClientBase.CONNECTION_TIMEOUT, event -> { });
        for (int i = 0; i < 10; i++) {
            zk.create("/bulk"
                              + i, ("Initial data of some size").getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        zk.close();

        q3.start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_OBS, CONNECTION_TIMEOUT),
                "waiting for observer to be up");

        latch = new CountDownLatch(1);
        zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP1, ClientBase.CONNECTION_TIMEOUT, this);
        latch.await();
        assertEquals(zk.getState(), States.CONNECTED);

        zk.create("/init", "first".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        final long zxid = q1.getQuorumPeer().getLastLoggedZxid();

        // wait for change to propagate
        waitFor("Timeout waiting for observer sync", new WaitForCondition() {
            public boolean evaluate() {
                return zxid == q3.getQuorumPeer().getLastLoggedZxid();
            }
        }, 30);

        ZooKeeper obsZk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_OBS, ClientBase.CONNECTION_TIMEOUT, this);
        int followerPort = q1.getQuorumPeer().leader == null ? CLIENT_PORT_QP1 : CLIENT_PORT_QP2;
        ZooKeeper fZk = new ZooKeeper("127.0.0.1:" + followerPort, ClientBase.CONNECTION_TIMEOUT, this);
        final int numTransactions = 10001;
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch oAsyncLatch = new CountDownLatch(numTransactions);
        Thread oAsyncWriteThread = new Thread(new AsyncWriter(obsZk, numTransactions, true, oAsyncLatch, "/obs", gate));
        CountDownLatch fAsyncLatch = new CountDownLatch(numTransactions);
        Thread fAsyncWriteThread = new Thread(new AsyncWriter(fZk, numTransactions, true, fAsyncLatch, "/follower", gate));

        LOG.info("ASYNC WRITES");
        oAsyncWriteThread.start();
        fAsyncWriteThread.start();
        gate.countDown();

        oAsyncLatch.await();
        fAsyncLatch.await();

        oAsyncWriteThread.join(ClientBase.CONNECTION_TIMEOUT);
        if (oAsyncWriteThread.isAlive()) {
            LOG.error("asyncWriteThread is still alive");
        }
        fAsyncWriteThread.join(ClientBase.CONNECTION_TIMEOUT);
        if (fAsyncWriteThread.isAlive()) {
            LOG.error("asyncWriteThread is still alive");
        }

        obsZk.close();
        fZk.close();

        shutdown();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testAdminCommands(boolean testObserverMaster) throws IOException, MBeanException, InstanceNotFoundException, ReflectionException, InterruptedException, MalformedObjectNameException, AttributeNotFoundException, InvalidAttributeValueException, KeeperException {
        // flush all beans, then start
        for (ZKMBeanInfo beanInfo : MBeanRegistry.getInstance().getRegisteredBeans()) {
            MBeanRegistry.getInstance().unregister(beanInfo);
        }

        JMXEnv.setUp();
        setUp(-1, testObserverMaster);
        q3.start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_OBS, CONNECTION_TIMEOUT),
                "waiting for observer to be up");

        // Assert that commands are getting forwarded correctly
        zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_OBS, ClientBase.CONNECTION_TIMEOUT, this);
        zk.create("/obstest", "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(new String(zk.getData("/obstest", null, null)), "test");

        // test stats collection
        final Map<String, String> emptyMap = Collections.emptyMap();
        Map<String, Object> stats = Commands.runCommand("mntr", q3.getQuorumPeer().getActiveServer(), emptyMap).toMap();
        assertTrue(stats.containsKey("observer_master_id"), "observer not emitting observer_master_id");

        // check the stats for the first peer
        if (testObserverMaster) {
            if (q1.getQuorumPeer().leader == null) {
                assertEquals(Integer.valueOf(1), q1.getQuorumPeer().getSynced_observers_metric());
            } else {
                assertEquals(Integer.valueOf(0), q1.getQuorumPeer().getSynced_observers_metric());
            }
        } else {
            if (q1.getQuorumPeer().leader == null) {
                assertNull(q1.getQuorumPeer().getSynced_observers_metric());
            } else {
                assertEquals(Integer.valueOf(1), q1.getQuorumPeer().getSynced_observers_metric());
            }
        }

        // check the stats for the second peer
        if (testObserverMaster) {
            if (q2.getQuorumPeer().leader == null) {
                assertEquals(Integer.valueOf(1), q2.getQuorumPeer().getSynced_observers_metric());
            } else {
                assertEquals(Integer.valueOf(0), q2.getQuorumPeer().getSynced_observers_metric());
            }
        } else {
            if (q2.getQuorumPeer().leader == null) {
                assertNull(q2.getQuorumPeer().getSynced_observers_metric());
            } else {
                assertEquals(Integer.valueOf(1), q2.getQuorumPeer().getSynced_observers_metric());
            }
        }

        // test admin commands for disconnection
        ObjectName connBean = null;
        for (ObjectName bean : JMXEnv.conn().queryNames(new ObjectName(MBeanRegistry.DOMAIN + ":*"), null)) {
            if (bean.getCanonicalName().contains("Learner_Connections") && bean.getCanonicalName().contains("id:"
                                                                                                                    + q3.getQuorumPeer().getId())) {
                connBean = bean;
                break;
            }
        }
        assertNotNull(connBean, "could not find connection bean");

        latch = new CountDownLatch(1);
        JMXEnv.conn().invoke(connBean, "terminateConnection", new Object[0], null);
        assertTrue(latch.await(CONNECTION_TIMEOUT / 2, TimeUnit.MILLISECONDS),
                "server failed to disconnect on terminate");
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_OBS, CONNECTION_TIMEOUT),
                "waiting for server 3 being up");

        final String obsBeanName = String.format("org.apache.ZooKeeperService:name0=ReplicatedServer_id%d,name1=replica.%d,name2=Observer", q3.getQuorumPeer().getId(), q3.getQuorumPeer().getId());
        Set<ObjectName> names = JMXEnv.conn().queryNames(new ObjectName(obsBeanName), null);
        assertEquals(1, names.size(), "expecting singular observer bean");
        ObjectName obsBean = names.iterator().next();

        if (testObserverMaster) {
            // show we can move the observer using the id
            long observerMasterId = q3.getQuorumPeer().observer.getLearnerMasterId();
            latch = new CountDownLatch(1);
            JMXEnv.conn().setAttribute(obsBean, new Attribute("LearnerMaster", Long.toString(3 - observerMasterId)));
            assertTrue(latch.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS), "server failed to disconnect on terminate");
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_OBS, CONNECTION_TIMEOUT),
                    "waiting for server 3 being up");
        } else {
            // show we get an error
            final long leaderId = q1.getQuorumPeer().leader == null ? 2 : 1;
            try {
                JMXEnv.conn().setAttribute(obsBean, new Attribute("LearnerMaster", Long.toString(3 - leaderId)));
                fail("should have seen an exception on previous command");
            } catch (RuntimeMBeanException e) {
                assertEquals(IllegalArgumentException.class, e.getCause().getClass(), "mbean failed for the wrong reason");
            }
        }

        shutdown();
        JMXEnv.tearDown();
    }

    private String createServerString(String type, long serverId, int clientPort) {
        return "server." + serverId + "=127.0.0.1:" + PortAssignment.unique() + ":" + PortAssignment.unique() + ":" + type + ";" + clientPort;
    }

    private void waitServerUp(int clientPort) {
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPort, CONNECTION_TIMEOUT),
                "waiting for server being up");
    }

    private ZooKeeperAdmin createAdmin(int clientPort) throws IOException {
        System.setProperty("zookeeper.DigestAuthenticationProvider.superDigest", "super:D/InIHSb7yEEbrWz8b9l71RjZJU="/* password is 'test'*/);
        QuorumPeerConfig.setReconfigEnabled(true);
        ZooKeeperAdmin admin = new ZooKeeperAdmin(
            "127.0.0.1:" + clientPort,
            ClientBase.CONNECTION_TIMEOUT,
            DummyWatcher.INSTANCE);
        admin.addAuthInfo("digest", "super:test".getBytes());
        return admin;
    }

    // This test is known to be flaky and fail due to "reconfig already in progress".
    // TODO: Investigate intermittent testDynamicReconfig failures.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Disabled
    public void testDynamicReconfig(boolean testObserverMaster) throws InterruptedException, IOException, KeeperException {
        if (!testObserverMaster) {
            return;
        }

        ClientBase.setupTestEnv();

        // create a quorum running with different observer master port
        // to make it easier to choose which server the observer is
        // following with
        //
        // we have setObserverMaster function but it's broken, use this
        // solution before we fixed that
        int clientPort1 = PortAssignment.unique();
        int clientPort2 = PortAssignment.unique();
        int omPort1 = PortAssignment.unique();
        int omPort2 = PortAssignment.unique();
        String quorumCfgSection = createServerString("participant", 1, clientPort1)
                                          + "\n"
                                          + createServerString("participant", 2, clientPort2);

        MainThread s1 = new MainThread(1, clientPort1, quorumCfgSection, String.format("observerMasterPort=%d%n", omPort1));
        MainThread s2 = new MainThread(2, clientPort2, quorumCfgSection, String.format("observerMasterPort=%d%n", omPort2));
        s1.start();
        s2.start();
        waitServerUp(clientPort1);
        waitServerUp(clientPort2);

        // create observer to follow non-leader observer master
        long nonLeaderOMPort = s1.getQuorumPeer().leader == null ? omPort1 : omPort2;
        int observerClientPort = PortAssignment.unique();
        int observerId = 10;
        MainThread observer = new MainThread(
            observerId,
            observerClientPort,
            quorumCfgSection + "\n" + createServerString("observer", observerId, observerClientPort),
            String.format("observerMasterPort=%d%n", nonLeaderOMPort));
        LOG.info("starting observer");
        observer.start();
        waitServerUp(observerClientPort);

        // create a client to the observer
        final LinkedBlockingQueue<KeeperState> states = new LinkedBlockingQueue<KeeperState>();
        ZooKeeper observerClient = new ZooKeeper(
            "127.0.0.1:" + observerClientPort,
            ClientBase.CONNECTION_TIMEOUT,
            event -> {
                try {
                    states.put(event.getState());
                } catch (InterruptedException ignore) {

                }
            });

        // wait for connected
        KeeperState state = states.poll(1000, TimeUnit.MILLISECONDS);
        assertEquals(KeeperState.SyncConnected, state);

        // issue reconfig command
        ArrayList<String> newServers = new ArrayList<String>();
        String server = "server.3=127.0.0.1:" + PortAssignment.unique() + ":" + PortAssignment.unique() + ":participant;localhost:" + PortAssignment.unique();
        newServers.add(server);
        ZooKeeperAdmin admin = createAdmin(clientPort1);
        ReconfigTest.reconfig(admin, newServers, null, null, -1);

        // make sure the observer has the new config
        ReconfigTest.testServerHasConfig(observerClient, newServers, null);

        // shouldn't be disconnected during reconfig, so expect to not
        // receive any new event
        state = states.poll(1000, TimeUnit.MILLISECONDS);
        assertNull(state);

        admin.close();
        observerClient.close();
        observer.shutdown();
        s2.shutdown();
        s1.shutdown();
    }

    class AsyncWriter implements Runnable {

        private final ZooKeeper client;
        private final int numTransactions;
        private final boolean issueSync;
        private final CountDownLatch writerLatch;
        private final String root;
        private final CountDownLatch gate;

        AsyncWriter(ZooKeeper client, int numTransactions, boolean issueSync, CountDownLatch writerLatch, String root, CountDownLatch gate) {
            this.client = client;
            this.numTransactions = numTransactions;
            this.issueSync = issueSync;
            this.writerLatch = writerLatch;
            this.root = root;
            this.gate = gate;
        }

        @Override
        public void run() {
            if (gate != null) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    LOG.error("Gate interrupted");
                    return;
                }
            }
            for (int i = 0; i < numTransactions; i++) {
                final boolean pleaseLog = i % 100 == 0;
                client.create(root
                                      + i, "inner thread".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (rc, path, ctx, name) -> {
                                          writerLatch.countDown();
                                          if (pleaseLog) {
                                              LOG.info("wrote {}", path);
                                          }
                                      }, null);
                if (pleaseLog) {
                    LOG.info("async wrote {}{}", root, i);
                    if (issueSync) {
                        client.sync(root + "0", null, null);
                    }
                }
            }
        }

    }

    private void validateObserverSyncTimeMetrics() {
        final String name = "observer_sync_time";
        final Map<String, Object> metrics = MetricsUtils.currentServerMetrics();

        assertEquals(5, metrics.keySet().stream().filter(key -> key.contains(name)).count());
        assertNotNull(metrics.get(String.format("avg_%s", name)));
        assertNotNull(metrics.get(String.format("min_%s", name)));
        assertNotNull(metrics.get(String.format("max_%s", name)));
        assertNotNull(metrics.get(String.format("cnt_%s", name)));
        assertNotNull(metrics.get(String.format("sum_%s", name)));
    }
}
