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

package org.apache.zookeeper.server.instrument;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.management.Attribute;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.RequestStage;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.JMXEnv;
import org.apache.zookeeper.test.QuorumUtil;
import org.apache.zookeeper.test.TestByteBufAllocator;
import org.apache.zookeeper.test.TestUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TraceLoggerQuorumTest extends ZKTestCase {
    private TraceLoggerServer loggerServer;
    private static final int maxOutstanding = 100;
    private static final int loggerServerPort = PortAssignment.unique();

    @BeforeClass
    public static void setUpClass() {
        System.setProperty("zookeeper.disableTraceLogger", String.valueOf(false));
        System.setProperty("zookeeper.traceLoggerMaxOutstanding", String.valueOf(maxOutstanding));
        System.setProperty("zookeeper.traceLoggerPort", String.valueOf(loggerServerPort));
        System.setProperty("zookeeper.traceMask", String.valueOf(ZooTrace.CLIENT_REQUEST_TRACE_MASK));
    }

    @Before
    public void setup() {
        TraceLogger.setTestAllocator(TestByteBufAllocator.getInstance());
        TraceLoggerServer.setTestAllocator(TestByteBufAllocator.getInstance());

        loggerServer = new TraceLoggerServer(loggerServerPort, true, false);
    }

    @After
    public void teardown() {
        if (loggerServer != null) {
            loggerServer.shutdown();
            loggerServer = null;
        }
        TraceLogger.clearTestAllocator();
        TraceLoggerServer.clearTestAllocator();
        TestByteBufAllocator.checkForLeaks();
    }

    private static class Txn {
        String zxid;
        long proposed;
        final ArrayList<Long> logged = new ArrayList<>();
        long committed;
        final ArrayList<Long> applied = new ArrayList<>();
        final ArrayList<Long> finals = new ArrayList<>();
    }

    @Test
    public void testQuorumWriteTraces() throws Exception {
        // Start 3-peer quorum and validate traces.
        final int numWrites = 10;
        // set necessary trace mask
        ZooTrace.setTextTraceLevel(ZooTrace.QUORUM_TRACE_MASK + ZooTrace.CLIENT_REQUEST_TRACE_MASK);
        QuorumUtil qu = new QuorumUtil(1);

        ServerMetrics.getMetrics().resetAll();
        qu.startAll();

        // wait till tracer is up
        Thread.sleep(1200);

        ZooKeeper zk = initClient(qu.getConnString());

        InetSocketAddress localSocketAddress = (InetSocketAddress) zk.getTestable().getLocalSocketAddress();
        String expectedClientPort = Integer.toString(localSocketAddress.getPort());

        for (int i = 0; i < numWrites; ++i) {
            zk.create("/test-" + i, String.valueOf(i).getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        TestUtils.assertWithTimeout(() -> {
            Map<String, Object> metrics = MetricsUtils.currentServerMetrics();
            long logged = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE).size();
            long acked = (Long) metrics.get("trace_events_acked");
            long sent = (Long) metrics.get("trace_events_sent");
            long queued = (Long) metrics.get("trace_events_queued");

            // there are three tracer loggers shared across three peers
            // thus sent == queued + 3
            return (logged == acked) && (acked == sent) && (sent == (queued + 1));
        }, true, 3000);

        qu.shutdownAll();
        zk.close();

        ConcurrentLinkedQueue<Map<String, String>> messages = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE);
        Map<String, Txn> txns = new HashMap<>();

        int actualWrites = 0;
        for (Map<String, String> message : messages) {
            if (message.get("header") != null) {
                continue;
            }

            String zxid = message.get(Integer.toString(TraceField.ZXID.ordinal()));
            String stage = message.get(Integer.toString(TraceField.STAGE.ordinal()));
            if (zxid == null || "-1".equals(zxid) || stage == null) {
                continue;
            }

            Long timestamp = Long.parseLong(message.get(Integer.toString(TraceField.TIME.ordinal())));
            // verify client id if it exists in trace
            String traceType = message.get(Integer.toString(TraceField.TRACE_TYPE.ordinal()));
            String clientId = message.get(Integer.toString(TraceField.CLIENT_ID.ordinal()));
            String requestSize = message.get(Integer.toString(TraceField.REQUEST_SIZE.ordinal()));
            String requestType = message.get(Integer.toString(TraceField.REQUEST_TYPE.ordinal()));
            Assert.assertNull(clientId);

            // verify client ip is 127.0.0.1
            String clientIp = message.get(Integer.toString(TraceField.CLIENT_IP.ordinal()));
            Assert.assertTrue(clientIp == null || "127.0.0.1".equals(clientIp));

            // verify client port is the outbound port used by the connection
            String clientPort = message.get(Integer.toString(TraceField.CLIENT_PORT.ordinal()));
            Assert.assertTrue(clientPort == null || expectedClientPort.equals(clientPort));

            // propose trace should have client id and request size for create request
            if (RequestStage.FINAL.name().equals(stage) && "create".equals(requestType)
                    && ZooTrace.getTraceType(ZooTrace.CLIENT_REQUEST_TRACE_MASK).equals(traceType)) {
                Assert.assertTrue(Integer.parseInt(requestSize) > 0);
                String path = message.get(Integer.toString(TraceField.PATH.ordinal()));
                Assert.assertNotNull(path);
                actualWrites++;
            }

            Txn txn = txns.get(zxid);
            if (txn == null) {
                txn = new Txn();
                txn.zxid = zxid;
                txns.put(zxid, txn);
            }

            switch (stage) {
                case "PROPOSE":
                    txn.proposed = timestamp;
                    break;
                case "APPEND_LOG":
                    txn.logged.add(timestamp);
                    break;
                case "SEND_COMMIT":
                    txn.committed = timestamp;
                    break;
                case "TREE_COMMIT":
                    txn.applied.add(timestamp);
                    break;
                case "FINAL":
                    txn.finals.add(timestamp);
                    break;
                default:
                    break;
            }
        }

        int quorum = 2;
        for (Txn txn : txns.values()) {
            int loggedBeforeCommit = 0;
            Assert.assertTrue(txn.logged.size() >= quorum);
            Assert.assertTrue(txn.finals.size() >= quorum);
            for (long logged : txn.logged) {
                Assert.assertTrue(logged >= txn.proposed);
                if (logged <= txn.committed) {
                    loggedBeforeCommit++;
                }
            }
            Assert.assertTrue(loggedBeforeCommit >= quorum);
            for (long applied : txn.applied) {
                Assert.assertTrue(applied >= txn.committed);
            }
        }

        // We expect numWrites + 1 (createSession) logged transactions
        Assert.assertEquals(numWrites + 1, txns.size());
        Assert.assertEquals(numWrites, actualWrites);
    }

    @Test
    public void testQuorumReadTraces() throws Exception {
        // set necessary trace mask
        ZooTrace.setTextTraceLevel(ZooTrace.CLIENT_REQUEST_TRACE_MASK);
        // Start 3-peer quorum and validate traces.
        QuorumUtil qu = new QuorumUtil(1);

        ServerMetrics.getMetrics().resetAll();
        qu.startAll();

        // wait till tracer is up
        Thread.sleep(1200);

        ZooKeeper zk = initClient(qu.getConnString());

        final String testPath = "/test-read";
        zk.create("/test-read", "test-read-traces".getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        final int numReads = 2;
        List<ZooKeeper> readClients = new ArrayList<>(numReads);
        for (int i = 0; i < numReads; ++i) {
            readClients.add(initClient(qu.getConnString()));
        }

        for (ZooKeeper client: readClients) {
            client.getData(testPath, false, null);
        }

        TestUtils.assertWithTimeout(() -> {
            Map<String, Object> metrics = MetricsUtils.currentServerMetrics();
            long logged = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE).size();
            long acked = (Long) metrics.get("trace_events_acked");
            long sent = (Long) metrics.get("trace_events_sent");
            long queued = (Long) metrics.get("trace_events_queued");

            // all messages = header + messages
            // queued count only messages not header
            return (logged == acked) && (acked == sent) && (sent == (queued + 1));
        }, true, 3000);

        qu.shutdownAll();
        zk.close();

        ConcurrentLinkedQueue<Map<String, String>> messages = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE);
        Map<String, Map<String, String>> sessions = new HashMap<>();
        for (Map<String, String> message : messages) {
            if (message.get("header") != null) {
                continue;
            }

            String sessionId = message.get(Integer.toString(TraceField.SESSION_ID.ordinal()));
            String stage = message.get(Integer.toString(TraceField.STAGE.ordinal()));

            String clientId = message.get(Integer.toString(TraceField.CLIENT_ID.ordinal()));
            String requestType = message.get(Integer.toString(TraceField.REQUEST_TYPE.ordinal()));
            String path = message.get(Integer.toString(TraceField.PATH.ordinal()));

            if ("getData".equals(requestType)) {
                Map<String, String> fields = sessions.getOrDefault(sessionId, new HashMap<>());
                sessions.put(sessionId, fields);
                if (path != null) {
                    fields.put("path", path);
                }
                if (clientId != null) {
                    fields.put("client_id", clientId);
                }
            }
        }

        // should be two getData calls
        Assert.assertEquals(numReads, sessions.size());
        for (Map<String, String> fields: sessions.values()) {
            Assert.assertEquals(testPath, fields.get("path"));
        }
    }

    @Test
    public void testQuorumWatchTraces() throws Exception {
        // set necessary trace mask
        ZooTrace.setTextTraceLevel(ZooTrace.EVENT_DELIVERY_TRACE_MASK);

        // Start 3-peer quorum and validate traces.
        QuorumUtil qu = new QuorumUtil(1);

        ServerMetrics.getMetrics().resetAll();
        qu.startAll();

        // wait till tracer is up
        Thread.sleep(1200);

        ZooKeeper zk1 = initClient(qu.getConnString());

        ZooKeeper zk2 = initClient(qu.getConnString());
        ZooKeeper zk3 = initClient(qu.getConnString());

        final String watchPath = "/test-watch-trace";
        zk2.exists(watchPath, true);

        zk1.create(watchPath, "watch".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        zk3.exists(watchPath, true);

        zk1.delete(watchPath, -1);

        TestUtils.assertWithTimeout(() -> {
            Map<String, Object> metrics = MetricsUtils.currentServerMetrics();
            long logged = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE).size();
            long acked = (Long) metrics.get("trace_events_acked");
            long sent = (Long) metrics.get("trace_events_sent");
            long queued = (Long) metrics.get("trace_events_queued");

            return (logged == acked) && (acked == sent) && (sent == (queued + 1));
        }, true, 3000);

        zk1.close();
        qu.shutdownAll();

        int sendWatchCount = 0;
        ConcurrentLinkedQueue<Map<String, String>> messages = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE);
        for (Map<String, String> message : messages) {
            if (message.get("header") != null) {
                continue;
            }

            String stage = message.get(Integer.toString(TraceField.STAGE.ordinal()));
            String path = message.get(Integer.toString(TraceField.PATH.ordinal()));

            if (RequestStage.SEND_WATCH.name().equals(stage)) {
                Assert.assertEquals(watchPath, path);
                sendWatchCount++;
            }
        }
        Assert.assertEquals(2, sendWatchCount);
        zk2.close();
        zk3.close();
    }

    @Test
    public void testJmxTraceAttributes() throws IOException, MalformedObjectNameException, AttributeNotFoundException,
            MBeanException, ReflectionException, InstanceNotFoundException, InvalidAttributeValueException {
        QuorumUtil qu = new QuorumUtil(1);
        try {
            qu.startAll();

            ObjectName traceBean = null;
            for (ObjectName bean : JMXEnv.conn().queryNames(new ObjectName(MBeanRegistry.DOMAIN + ":*"), null)) {
                if (bean.getCanonicalName().contains("ZooTrace")) {
                    traceBean = bean;
                    break;
                }
            }
            Assert.assertNotNull("Trace bean must not be null", traceBean);
            JMXEnv.conn().setAttribute(traceBean, new Attribute("TraceMask", 66));
            JMXEnv.conn().setAttribute(traceBean, new Attribute("TraceBufferSize", 12345));

            Assert.assertEquals(66L, JMXEnv.conn().getAttribute(traceBean, "TraceMask"));
            Assert.assertEquals(12345L, JMXEnv.conn().getAttribute(traceBean, "TraceBufferSize"));

            Assert.assertEquals(66L, ZooTrace.getTextTraceLevel());
            Assert.assertEquals(12345L, TraceLogger.getMaxOutstanding());
        } finally {
            qu.shutdownAll();
        }
    }

    private ZooKeeper initClient(String connString) throws Exception {
        return ClientBase.createZKClient(connString);
    }

}
