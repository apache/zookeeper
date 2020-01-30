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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.RequestStage;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.test.TestUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TraceServerTest {
    private static final int maxOutstanding = 100;
    private static final int loggerServerPort = PortAssignment.unique();

    @BeforeClass
    public static void setUpClass() {
        System.setProperty(TraceLogger.TRACE_LOGGER_MAX_OUTSTANDING_TRACE, String.valueOf(maxOutstanding));
        System.setProperty(TraceLogger.TRACE_LOGGER_PORT, String.valueOf(loggerServerPort));
        System.setProperty(ZooTrace.TRACE_MASK, String.valueOf(ZooTrace.CLIENT_REQUEST_TRACE_MASK));
    }

    public static class TestTraceServer extends TraceServer {
        String ensembleName;
        String destination;
        List<TraceField> schema = new ArrayList<>();
        Map<String, String> trace = new HashMap<>();

        public TestTraceServer(int port) {
            super(port);
        }

        @Override
        protected void logHeader(final String hostname, final String ensembleName, final String destination,
                                 List<TraceField> schema) {
            this.ensembleName = ensembleName;
            this.destination = destination;
            this.schema.addAll(schema);
        }

        protected void logTrace(final String hostname, final String ensembleName, final String destination,
                                List<TraceField> schema, Map<String, String> message) {
            for (Map.Entry<String, String> entry : message.entrySet()) {
                int fieldIndex = Integer.parseInt(entry.getKey());
                String fieldName = schema.get(fieldIndex).getName();
                String fieldValue = entry.getValue();
                trace.put(fieldName, fieldValue);
            }
        }
    }

    @Test
    public void testTracerServer() throws Exception {
        ServerMetrics.getMetrics().resetAll();

        // start trace server
        final String ensembleName = "test_ensemble";
        TestTraceServer traceServer = null;
        TraceLogger traceLogger = null;

        try {
            // start trace server
            traceServer = new TestTraceServer(loggerServerPort);
            traceServer.startup();

            // start trace logger with same port
            traceLogger = new TraceLogger(ensembleName);

            final TestTraceServer server = traceServer;
            final TraceLogger logger = traceLogger;

            Assert.assertEquals(ZooTrace.CLIENT_REQUEST_TRACE_MASK, ZooTrace.getTextTraceLevel());

            // verify trace logger is connected to trace server
            TestUtils.assertWithTimeout(() -> logger.isReady(), true, 3000);

            // verify trace header
            TestUtils.assertWithTimeout(() -> ensembleName.equals(server.ensembleName), true, 3000);
            TestUtils.assertWithTimeout(() -> TraceLogger.DESTINATION_SERVER_TRACE.equals(server.destination), true, 3000);
            TestUtils.assertWithTimeout(() -> TraceField.values().length == server.schema.size(), true, 3000);

            Set<TraceField> s1 = new HashSet<>(Arrays.asList(TraceField.values()));
            Set<TraceField> s2 = new HashSet<>(server.schema);
            Assert.assertEquals(s1, s2);

            // send in traces and validate header and traces
            long timestamp = 1562028565L;
            TraceLogger.Message m = traceLogger.msg().add(TraceField.TIME, timestamp)
                    .add(TraceField.ZXID, 100)
                    .add(TraceField.CXID, 1000)
                    .add(TraceField.SESSION_ID, 10000)
                    .add(TraceField.CLIENT_ID, "zookeeper_client")
                    .add(TraceField.CLIENT_IP, "127.0.0.1")
                    .add(TraceField.PEER_ID, 1)
                    .add(TraceField.STAGE, RequestStage.FINAL.name())
                    .add(TraceField.REQUEST_SIZE, 200)
                    .add(TraceField.IS_EPHEMERAL, 0)
                    .add(TraceField.LATENCY, 120);
            m.send();

            Thread.sleep(5000);

            Map<String, String> expectedTraceMessage = new HashMap<>();
            expectedTraceMessage.put("time", "1562028565");
            expectedTraceMessage.put("zxid", "100");
            expectedTraceMessage.put("cxid", "1000");
            expectedTraceMessage.put("session_id", "10000");
            expectedTraceMessage.put("client_id", "zookeeper_client");
            expectedTraceMessage.put("client_ip", "127.0.0.1");
            expectedTraceMessage.put("peer_id", "1");
            expectedTraceMessage.put("stage", "FINAL");
            expectedTraceMessage.put("request_size", "200");
            expectedTraceMessage.put("is_ephemeral", "0");
            expectedTraceMessage.put("latency_ms", "120");

            TestUtils.assertWithTimeout(() -> expectedTraceMessage.size() == server.trace.size(), true, 3000);
            for (String key : expectedTraceMessage.keySet()) {
                Assert.assertEquals(expectedTraceMessage.get(key), server.trace.get(key));
            }
        } finally {
            if (traceLogger != null) {
                traceLogger.shutdown();
            }
            if (traceServer != null) {
                traceServer.shutdown();
            }
        }
    }
}
