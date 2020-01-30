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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.RequestStage;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.test.TestByteBufAllocator;
import org.apache.zookeeper.test.TestUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TraceLoggerTest {
    private TraceLogger traceLogger;
    private TraceLoggerServer loggerServer;
    private static final int maxOutstanding = 100;
    private static final int loggerServerPort = PortAssignment.unique();

    @BeforeClass
    public static void setUpClass() {
        System.setProperty(TraceLogger.TRACE_LOGGER_MAX_OUTSTANDING_TRACE, String.valueOf(maxOutstanding));
        System.setProperty(TraceLogger.TRACE_LOGGER_PORT, String.valueOf(loggerServerPort));
        System.setProperty(ZooTrace.TRACE_MASK, String.valueOf(ZooTrace.CLIENT_REQUEST_TRACE_MASK));
    }

    @Before
    public void setup() {
        TraceLogger.setTestAllocator(TestByteBufAllocator.getInstance());
        TraceLoggerServer.setTestAllocator(TestByteBufAllocator.getInstance());
    }

    @After
    public void teardown() {
        if (traceLogger != null) {
            traceLogger.shutdown();
            traceLogger = null;
        }
        if (loggerServer != null) {
            loggerServer.shutdown();
            loggerServer = null;
        }
        TraceLogger.clearTestAllocator();
        TraceLoggerServer.clearTestAllocator();
        TestByteBufAllocator.checkForLeaks();
    }

    @Test
    public void testTracerBasic() throws Exception {
        ServerMetrics.getMetrics().resetAll();
        final int numTraces = 10;

        // start trace server, use TraceLogger to send in traces and validate header and traces
        traceLogger = new TraceLogger("test");
        loggerServer = new TraceLoggerServer(loggerServerPort, true, false);

        Assert.assertEquals(ZooTrace.CLIENT_REQUEST_TRACE_MASK, ZooTrace.getTextTraceLevel());

        List<TraceLogger.Message> traces = sendTraces(traceLogger, numTraces, RequestStage.TREE_COMMIT, "createNode");

        TestUtils.assertWithTimeout(() -> {
            Map<String, Object> metrics = MetricsUtils.currentServerMetrics();
            long logged = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE).size();
            long acked = (Long) metrics.get("trace_events_acked");
            long sent = (Long) metrics.get("trace_events_sent");
            long queued = (Long) metrics.get("trace_events_queued");
            long dropped = (Long) metrics.get("trace_events_dropped");

            return (dropped == 0) && (logged == acked) && (acked == sent)
                    && (sent == (queued + 1)) && (queued == numTraces);
        }, true, 3000);

        ConcurrentLinkedQueue<Map<String, String>> received = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE);
        // first message is header
        Map<String, String> header = received.poll();
        validateHeader(header);

        // trace sent should match received.
        for (TraceLogger.Message expected : traces) {
            Map<String, String> actual = received.poll();
            validateTrace(expected, actual);
        }
    }

    @Test
    public void testLongTrace() throws Exception {
        // construct long path list in trace exceeding frame limit
        // and verify trace is received.
        traceLogger = new TraceLogger("test");

        final int numTraces = 10;

        int length = 2000;
        final List<String> paths = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            paths.add("c/test/trace/with/a/lot/of/path/" + i);
        }

        loggerServer = new TraceLoggerServer(loggerServerPort, true, false);

        ServerMetrics.getMetrics().resetAll();
        List<TraceLogger.Message> traces = sendTraces(traceLogger, numTraces, RequestStage.FINAL, "setWatches", paths);

        TestUtils.assertWithTimeout(() -> {
            Map<String, Object> metrics = MetricsUtils.currentServerMetrics();
            long logged = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE).size();
            long acked = (Long) metrics.get("trace_events_acked");
            long sent = (Long) metrics.get("trace_events_sent");
            long queued = (Long) metrics.get("trace_events_queued");
            long dropped = (Long) metrics.get("trace_events_dropped");

            return (dropped == 0) && (logged == acked) && (acked == sent)
                    && (sent == (queued + 1)) && (queued == numTraces);
        }, true, 3000);

        ConcurrentLinkedQueue<Map<String, String>> received = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE);
        // first message is header
        Map<String, String> header = received.poll();
        validateHeader(header);

        // trace sent should match received.
        for (TraceLogger.Message expected : traces) {
            Map<String, String> actual = received.poll();
            validateTrace(expected, actual);
        }
    }

    @Test
    public void testTraceServerDownAndUp() throws Exception {
        // Start traceLogger with logger server down, and send test traces.
        traceLogger = new TraceLogger("test");

        // Then start logger server and send more test traces.
        final int numTracesBatch1 = 300;
        final int numTracesBatch2 = 200;

        ServerMetrics.getMetrics().resetAll();
        List<TraceLogger.Message> firstBatch = sendTraces(traceLogger, numTracesBatch1, RequestStage.TREE_COMMIT, "create");

        // logger server is down
        TestUtils.assertWithTimeout(() -> {
            Map<String, Object> metrics = MetricsUtils.currentServerMetrics();
            long acked = (Long) metrics.get("trace_events_acked");
            long queued = (Long) metrics.get("trace_events_queued");
            long dropped = (Long) metrics.get("trace_events_dropped");

            return (acked == 0) && (queued == maxOutstanding) && (dropped == (numTracesBatch1 - maxOutstanding));
        }, true, 3000);

        // start logger server
        loggerServer = new TraceLoggerServer(loggerServerPort, true, false);

        TestUtils.assertWithTimeout(() -> {
            Map<String, Object> metrics = MetricsUtils.currentServerMetrics();
            long logged = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE).size();
            long acked = (Long) metrics.get("trace_events_acked");
            long sent = (Long) metrics.get("trace_events_sent");

            return (logged == acked) && (acked == sent) && (sent == maxOutstanding + 1);
        }, true, 3000);

        // send second batch traces
        List<TraceLogger.Message> secondBatch = sendTraces(traceLogger, numTracesBatch2, RequestStage.TREE_COMMIT, "create");

        TestUtils.assertWithTimeout(() -> {
            Map<String, Object> metrics = MetricsUtils.currentServerMetrics();
            long logged = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE).size();
            long acked = (Long) metrics.get("trace_events_acked");
            long sent = (Long) metrics.get("trace_events_sent");
            long dropped = (Long) metrics.get("trace_events_dropped");

            return (logged == acked) && (acked == sent)
                    && (sent + dropped == numTracesBatch1 + numTracesBatch2 + 1);
        }, true, 3000);

        ConcurrentLinkedQueue<Map<String, String>> received = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE);
        Map<String, String> header = received.poll();
        validateHeader(header);

        // some traces in first batch should be received.
        for (int i = 0; i < maxOutstanding; i++) {
            validateTrace(firstBatch.get(i), received.poll());
        }
    }

    @Test
    public void testFilter() throws Exception {
        // no filtering by default
        for (TraceField f: TraceField.values()) {
            Assert.assertTrue(f.getFilter().accept(1));
            Assert.assertTrue(f.getFilter().accept("any"));
            Assert.assertTrue(f.getFilter().accept(null));
            Assert.assertTrue(f.getFilter().accept(""));
        }

        ZooTrace.setTraceFilterString("stage=FINAL,SEND_WATCH::request_type=create::PEER_ID=1,7");

        Assert.assertTrue(TraceField.STAGE.getFilter().accept(RequestStage.FINAL.name()));
        Assert.assertTrue(TraceField.STAGE.getFilter().accept(RequestStage.SEND_WATCH.name()));
        Assert.assertFalse(TraceField.STAGE.getFilter().accept(RequestStage.FOLLOWER.name()));
        Assert.assertFalse(TraceField.STAGE.getFilter().accept(RequestStage.PREP.name()));
        Assert.assertFalse(TraceField.STAGE.getFilter().accept(null));
        Assert.assertTrue(TraceField.STAGE.getFilter().accept(123L));

        Assert.assertTrue(TraceField.REQUEST_TYPE.getFilter().accept("create"));
        Assert.assertFalse(TraceField.REQUEST_TYPE.getFilter().accept("getData"));

        Assert.assertTrue(TraceField.PEER_ID.getFilter().accept(7));
        Assert.assertFalse(TraceField.PEER_ID.getFilter().accept(5));
        Assert.assertTrue(TraceField.PEER_ID.getFilter().accept(1));

        // set new filter string and verify
        ZooTrace.setTraceFilterString("stage= FINAL, PREP::PEER_ID =1");

        Assert.assertTrue(TraceField.STAGE.getFilter().accept(RequestStage.FINAL.name()));
        Assert.assertFalse(TraceField.STAGE.getFilter().accept(RequestStage.SEND_WATCH.name()));
        Assert.assertFalse(TraceField.STAGE.getFilter().accept(RequestStage.FOLLOWER.name()));
        Assert.assertTrue(TraceField.STAGE.getFilter().accept(RequestStage.PREP.name()));
        Assert.assertFalse(TraceField.STAGE.getFilter().accept(null));
        Assert.assertTrue(TraceField.STAGE.getFilter().accept(123L));

        Assert.assertTrue(TraceField.REQUEST_TYPE.getFilter().accept("create"));
        Assert.assertTrue(TraceField.REQUEST_TYPE.getFilter().accept("getData"));

        Assert.assertFalse(TraceField.PEER_ID.getFilter().accept(7));
        Assert.assertFalse(TraceField.PEER_ID.getFilter().accept(5));
        Assert.assertTrue(TraceField.PEER_ID.getFilter().accept(1));

        // clear filter and verify no filtering
        ZooTrace.setTraceFilterString("");
        for (TraceField f: TraceField.values()) {
            Assert.assertTrue(f.getFilter().accept(2));
            Assert.assertTrue(f.getFilter().accept("any"));
            Assert.assertTrue(f.getFilter().accept(null));
            Assert.assertTrue(f.getFilter().accept(""));
        }

        // test invalid string
        ZooTrace.setTraceFilterString("stage=FINAL::PEER_ID=");
        Assert.assertTrue(TraceField.STAGE.getFilter().accept(RequestStage.FINAL.name()));
        Assert.assertFalse(TraceField.STAGE.getFilter().accept(RequestStage.SEND_WATCH.name()));
        Assert.assertTrue(TraceField.PEER_ID.getFilter().accept(7));

        ZooTrace.setTraceFilterString("stage=FINAL::peer_id=abc,1::bad_name=bad_value");
        Assert.assertTrue(TraceField.PEER_ID.getFilter().accept(1));
        Assert.assertFalse(TraceField.PEER_ID.getFilter().accept(7));

        ZooTrace.setTraceFilterString("");
    }

    @Test
    public void testTraceLoggerFilter() throws Exception {
        // Set trace filter and use TraceLogger to send in traces and validate header and traces
        traceLogger = new TraceLogger("test");
        ZooTrace.setTraceFilterString("stage=FINAL,SEND_WATCH::request_type=create");

        loggerServer = new TraceLoggerServer(loggerServerPort, true, false);

        ServerMetrics.getMetrics().resetAll();

        List<TraceLogger.Message> expected = new ArrayList<>();
        List<TraceLogger.Message> t1 = sendTraces(traceLogger, 1, RequestStage.PREP, "create");
        List<TraceLogger.Message> t2 = sendTraces(traceLogger, 1, RequestStage.PREP, "getData");
        List<TraceLogger.Message> t3 = sendTraces(traceLogger, 1, RequestStage.FOLLOWER, "create");
        List<TraceLogger.Message> t4 = sendTraces(traceLogger, 1, RequestStage.FOLLOWER, "create");
        List<TraceLogger.Message> t5 = sendTraces(traceLogger, 1, RequestStage.FINAL, "create");
        expected.addAll(t5);
        List<TraceLogger.Message> t6 = sendTraces(traceLogger, 1, RequestStage.FINAL, "create");
        expected.addAll(t6);
        List<TraceLogger.Message> t7 = sendTraces(traceLogger, 1, RequestStage.FINAL, "getData");
        List<TraceLogger.Message> t8 = sendTraces(traceLogger, 1, RequestStage.SEND_WATCH, "create");
        expected.addAll(t8);
        List<TraceLogger.Message> t9 = sendTraces(traceLogger, 1, RequestStage.SEND_WATCH, "getData");

        TestUtils.assertWithTimeout(() -> {
            Map<String, Object> metrics = MetricsUtils.currentServerMetrics();
            long logged = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE).size();
            long acked = (Long) metrics.get("trace_events_acked");
            long sent = (Long) metrics.get("trace_events_sent");
            long queued = (Long) metrics.get("trace_events_queued");
            long dropped = (Long) metrics.get("trace_events_dropped");

            return (dropped == 0) && (logged == acked) && (acked == sent)
                    && (sent == (queued + 1)) && (queued == expected.size());
        }, true, 3000);

        ConcurrentLinkedQueue<Map<String, String>> received = loggerServer.getMessages(TraceLogger.DESTINATION_SERVER_TRACE);
        // first message is header
        Map<String, String> header = received.poll();
        validateHeader(header);

        // trace sent should match received.
        Assert.assertEquals(expected.size(), received.size());
        for (TraceLogger.Message e : expected) {
            Map<String, String> actual = received.poll();
            validateTrace(e, actual);
        }
        ZooTrace.setTraceFilterString("");
    }

    private List<TraceLogger.Message> sendTraces(TraceLogger traceLogger, int n, RequestStage stage, String requestType) {
        return sendTraces(traceLogger, n, stage, requestType, Arrays.asList("/smc/test1", "/smc/test2"));
    }

    private List<TraceLogger.Message> sendTraces(TraceLogger traceLogger, int n, RequestStage stage,
                                                 String requestType, List<String> paths) {
        List<TraceLogger.Message> messages = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long timestamp = System.currentTimeMillis() / 1000L;
            TraceLogger.Message m = traceLogger.msg().add(TraceField.TIME, timestamp)
                    .add(TraceField.ZXID, 100 + i)
                    .add(TraceField.CXID, 1000 + i)
                    .add(TraceField.SESSION_ID, 10000 + i)
                    .add(TraceField.CLIENT_ID, "zookeeper_client")
                    .add(TraceField.CLIENT_IP, "127.0.0.1")
                    .add(TraceField.PEER_ID, i % 5)
                    .add(TraceField.STAGE, stage.name())
                    .add(TraceField.REQUEST_TYPE, requestType)
                    .add(TraceField.REQUEST_SIZE, 200)
                    .add(TraceField.IS_EPHEMERAL, 0)
                    .add(TraceField.HAS_WATCH, 0)
                    .add(TraceField.LATENCY, 120)
                    .add(TraceField.PATH, paths)
                    .add(TraceField.PACKET_DIRECTION, "in")
                    .add(TraceField.PACKET_TYPE, "diff");
            m.send();
            messages.add(m);
        }

        return messages;
    }

    private void validateHeader(Map<String, String> header) {
        Assert.assertTrue(header.containsKey("header"));
        Assert.assertTrue(header.containsKey("hostname"));
        Assert.assertTrue(header.containsKey("ensemble"));
        Assert.assertTrue(header.containsKey("window"));
        Assert.assertTrue(header.containsKey("schema"));
        Assert.assertTrue(header.containsKey("destination"));

        String schemaStr = header.get("schema");
        Set<String> schema = new HashSet<>(Arrays.asList(schemaStr.split(",")));
        Assert.assertEquals(TraceField.values().length, schema.size());
        for (TraceField tf: TraceField.values()) {
            Assert.assertTrue(schema.contains(tf.getDefinition()));
        }
    }

    private void validateTrace(TraceLogger.Message expected, Map<String, String> actual) {
        for (int i = 0; i < expected.values.size(); i += 2) {
            Assert.assertEquals(expected.values.get(i + 1), actual.get(expected.values.get(i)));
        }
    }
}
