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

package org.apache.zookeeper.server.quorum;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.hamcrest.number.OrderingComparison.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SyncRequestProcessorMetricTest {

    ZooKeeperServer zks;
    RequestProcessor nextProcessor;
    CountDownLatch allRequestsFlushed;

    @BeforeEach
    public void setup() throws Exception {
        ZKDatabase db = mock(ZKDatabase.class);
        when(db.append(any(Request.class))).thenReturn(true);
        doAnswer(invocation -> {
            Thread.sleep(100);
            return null;
        }).when(db).commit();
        zks = mock(ZooKeeperServer.class);
        when(zks.getZKDatabase()).thenReturn(db);

        nextProcessor = mock(RequestProcessor.class);
        doAnswer(invocationOnMock -> {
            allRequestsFlushed.countDown();
            return null;
        }).when(nextProcessor).processRequest(any(Request.class));
    }

    private Request createRquest(long sessionId, int xid) {
        return new Request(null, sessionId, xid, ZooDefs.OpCode.setData, ByteBuffer.wrap(new byte[10]), null);
    }

    @Test
    public void testSyncProcessorMetrics() throws Exception {
        SyncRequestProcessor syncProcessor = new SyncRequestProcessor(zks, nextProcessor);
        for (int i = 0; i < 500; i++) {
            syncProcessor.processRequest(createRquest(1, i));
        }

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(500L, values.get("sync_processor_request_queued"));

        allRequestsFlushed = new CountDownLatch(500);
        syncProcessor.start();

        allRequestsFlushed.await(5000, TimeUnit.MILLISECONDS);

        values = MetricsUtils.currentServerMetrics();

        assertEquals(501L, values.get("cnt_sync_processor_queue_size"));
        assertEquals(500L, values.get("max_sync_processor_queue_size"));
        assertEquals(0L, values.get("min_sync_processor_queue_size"));

        assertEquals(500L, values.get("cnt_sync_processor_queue_time_ms"));
        assertThat((long) values.get("max_sync_processor_queue_time_ms"), greaterThan(0L));

        assertEquals(500L, values.get("cnt_sync_processor_queue_and_flush_time_ms"));
        assertThat((long) values.get("max_sync_processor_queue_and_flush_time_ms"), greaterThan(0L));

        assertEquals(500L, values.get("cnt_sync_process_time"));
        assertThat((long) values.get("max_sync_process_time"), greaterThan(0L));

        assertEquals(500L, values.get("max_sync_processor_batch_size"));
        assertEquals(1L, values.get("cnt_sync_processor_queue_flush_time_ms"));
        assertThat((long) values.get("max_sync_processor_queue_flush_time_ms"), greaterThanOrEqualTo(100L));

        syncProcessor.shutdown();
    }

}
