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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.hamcrest.number.OrderingComparison.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.proto.DeleteRequest;
import org.apache.zookeeper.proto.SetDataRequest;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrepRequestProcessorMetricsTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(PrepRequestProcessorMetricsTest.class);

    ZooKeeperServer zks;
    RequestProcessor nextProcessor;

    @BeforeEach
    public void setup() {
        System.setProperty(ZooKeeperServer.SKIP_ACL, "true");
        zks = spy(new ZooKeeperServer());
        zks.sessionTracker = mock(SessionTracker.class);

        ZKDatabase db = mock(ZKDatabase.class);
        when(zks.getZKDatabase()).thenReturn(db);

        DataNode node = new DataNode(new byte[1], null, mock(StatPersisted.class));
        when(db.getNode(anyString())).thenReturn(node);

        DataTree dataTree = mock(DataTree.class);
        when(db.getDataTree()).thenReturn(dataTree);

        Set<String> ephemerals = new HashSet<>();
        ephemerals.add("/crystalmountain");
        ephemerals.add("/stevenspass");
        when(db.getEphemerals(anyLong())).thenReturn(ephemerals);

        nextProcessor = mock(RequestProcessor.class);
        ServerMetrics.getMetrics().resetAll();
    }

    @AfterEach
    public void tearDown() throws Exception {
        System.clearProperty(ZooKeeperServer.SKIP_ACL);
    }

    private Request createRequest(Record record, int opCode) throws IOException {
        // encoding
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        record.serialize(boa, "request");
        baos.close();
        return new Request(null, 1L, 0, opCode, ByteBuffer.wrap(baos.toByteArray()), null);
    }

    private Request createRequest(String path, int opCode) throws IOException {
        Record record;
        switch (opCode) {
        case ZooDefs.OpCode.setData:
            record = new SetDataRequest(path, new byte[0], -1);
            break;
        case ZooDefs.OpCode.delete:
            record = new DeleteRequest(path, -1);
            break;
        default:
            record = new DeleteRequest(path, -1);
            break;
        }

        return createRequest(record, opCode);
    }

    private Request createRequest(long sessionId, int opCode) {
        return new Request(null, sessionId, 0, opCode, null, null);
    }

    @Test
    public void testPrepRequestProcessorMetrics() throws Exception {
        CountDownLatch threeRequests = new CountDownLatch(3);
        doAnswer(invocationOnMock -> {
            threeRequests.countDown();
            return null;
        }).when(nextProcessor).processRequest(any(Request.class));

        PrepRequestProcessor prepRequestProcessor = new PrepRequestProcessor(zks, nextProcessor);

        //setData will generate one change
        prepRequestProcessor.processRequest(createRequest("/foo", ZooDefs.OpCode.setData));
        //delete will generate two changes, one for itself, one for its parent
        prepRequestProcessor.processRequest(createRequest("/foo/bar", ZooDefs.OpCode.delete));
        //mocking two ephemeral nodes exists for this session so two changes
        prepRequestProcessor.processRequest(createRequest(2, ZooDefs.OpCode.closeSession));

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(3L, values.get("prep_processor_request_queued"));

        // the sleep is just to make sure the requests will stay in the queue for some time
        Thread.sleep(20);
        prepRequestProcessor.start();

        threeRequests.await(500, TimeUnit.MILLISECONDS);

        values = MetricsUtils.currentServerMetrics();
        assertEquals(3L, values.get("max_prep_processor_queue_size"));

        assertThat((long) values.get("min_prep_processor_queue_time_ms"), greaterThan(20L));
        assertEquals(3L, values.get("cnt_prep_processor_queue_time_ms"));

        assertEquals(3L, values.get("cnt_prep_process_time"));
        assertThat((long) values.get("max_prep_process_time"), greaterThan(0L));

        assertEquals(1L, values.get("cnt_close_session_prep_time"));
        assertThat((long) values.get("max_close_session_prep_time"), greaterThanOrEqualTo(0L));

        // With digest feature, we have two more OUTSTANDING_CHANGES_QUEUED than w/o digest
        // The expected should 5 in open source until we upstream the digest feature
        assertEquals(7L, values.get("outstanding_changes_queued"));
    }

    private class SimpleWatcher implements Watcher {

        CountDownLatch created;
        public SimpleWatcher(CountDownLatch latch) {
            this.created = latch;
        }
        @Override
        public void process(WatchedEvent e) {
            created.countDown();
        }

    }

    @Test
    public void testOutstandingChangesRemoved() throws Exception {
        // this metric is currently recorded in FinalRequestProcessor but it is tightly related to the Prep metrics
        QuorumUtil util = new QuorumUtil(1);
        util.startAll();

        ServerMetrics.getMetrics().resetAll();

        ZooKeeper zk = ClientBase.createZKClient(util.getConnString());
        zk.create("/test", new byte[50], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        CountDownLatch created = new CountDownLatch(1);
        zk.exists("/test", new SimpleWatcher(created));
        created.await(200, TimeUnit.MILLISECONDS);

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertThat((long) values.get("outstanding_changes_removed"), greaterThan(0L));

        util.shutdownAll();
    }

}
