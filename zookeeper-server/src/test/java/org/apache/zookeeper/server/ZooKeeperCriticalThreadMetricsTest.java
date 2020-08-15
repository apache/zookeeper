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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.junit.jupiter.api.Test;

public class ZooKeeperCriticalThreadMetricsTest extends ZKTestCase {

    CountDownLatch processed;

    private class MyRequestProcessor implements RequestProcessor {

        @Override
        public void processRequest(Request request) throws RequestProcessorException {
            // use this dummy request processor to trigger a unrecoverable ex
            throw new RequestProcessorException("test", new Exception());
        }

        @Override
        public void shutdown() {
        }

    }

    private class MyPrepRequestProcessor extends PrepRequestProcessor {

        public MyPrepRequestProcessor() {
            super(new ZooKeeperServer(), new MyRequestProcessor());
        }

        @Override
        public void run() {
            super.run();
            processed.countDown();
        }

    }

    @Test
    public void testUnrecoverableErrorCountFromRequestProcessor() throws Exception {
        ServerMetrics.getMetrics().resetAll();

        processed = new CountDownLatch(1);
        PrepRequestProcessor processor = new MyPrepRequestProcessor();
        processor.start();

        processor.processRequest(new Request(null, 1L, 1, ZooDefs.OpCode.setData, ByteBuffer.wrap(new byte[10]), null));
        processed.await();

        processor.shutdown();

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(1L, values.get("unrecoverable_error_count"));
    }

    @Test
    public void testUnrecoverableErrorCount() {
        ServerMetrics.getMetrics().resetAll();

        ZooKeeperServer zks = new ZooKeeperServer();
        ZooKeeperCriticalThread thread = new ZooKeeperCriticalThread("test", zks.getZooKeeperServerListener());

        thread.handleException("test", new Exception());

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(1L, values.get("unrecoverable_error_count"));
    }

}
