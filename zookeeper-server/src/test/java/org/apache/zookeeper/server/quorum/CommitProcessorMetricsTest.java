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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.WorkerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommitProcessorMetricsTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(CommitProcessorMetricsTest.class);
    CommitProcessor commitProcessor;
    DummyFinalProcessor finalProcessor;

    CountDownLatch requestScheduled = null;
    CountDownLatch requestProcessed = null;
    CountDownLatch commitSeen = null;
    CountDownLatch poolEmpytied = null;

    @BeforeEach
    public void setup() {
        LOG.info("setup");
        ServerMetrics.getMetrics().resetAll();

        // ensure no leaked parallelism properties
        System.clearProperty("zookeeper.commitProcessor.maxReadBatchSize");
        System.clearProperty("zookeeper.commitProcessor.maxCommitBatchSize");
    }

    public void setupProcessors(int commitWorkers, int finalProcTime) {
        finalProcessor = new DummyFinalProcessor(finalProcTime);
        commitProcessor = new TestCommitProcessor(finalProcessor, commitWorkers);
        commitProcessor.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        LOG.info("tearDown starting");

        commitProcessor.shutdown();
        commitProcessor.join();
    }

    private class TestCommitProcessor extends CommitProcessor {

        int numWorkerThreads;

        public TestCommitProcessor(RequestProcessor finalProcessor, int numWorkerThreads) {
            super(finalProcessor, "1", true, null);
            this.numWorkerThreads = numWorkerThreads;
        }

        @Override
        public void start() {
            super.workerPool = new TestWorkerService(numWorkerThreads);
            super.start();
            // Since there are two threads--the test thread that puts requests into the queue and the processor
            // thread (this thread) that removes requests from the queue--the execution order in general is
            // indeterminate, making it hard to check the test results.
            //
            // In some tests, we really want the requests processed one by one. To achieve this, we make sure that
            // things happen in this order:
            // processor thread gets into WAITING -> test thread sets requestProcessed latch -> test thread puts
            // a request into the queue (which wakes up the processor thread in the WAITING state) and waits for
            // the requestProcessed latch -> the processor thread wakes up and removes the request from the queue and
            // processes it and opens the requestProcessed latch -> the test thread continues onto the next request

            // So it is important for the processor thread to get into WAITING before any request is put into the queue.
            // Otherwise, it would miss the wakeup signal and wouldn't process the request or open the latch and the
            // test thread waiting on the latch would be stuck
            Thread.State state = super.getState();
            while (state != State.WAITING) {
                try {
                    Thread.sleep(50);
                } catch (Exception e) {

                }
                state = super.getState();
            }
            LOG.info("numWorkerThreads in Test is {}", numWorkerThreads);
        }

        @Override
        protected void endOfIteration() {
            if (requestProcessed != null) {
                requestProcessed.countDown();
            }
        }

        @Override
        protected void waitForEmptyPool() throws InterruptedException {
            if (commitSeen != null) {
                commitSeen.countDown();
            }
            super.waitForEmptyPool();
            if (poolEmpytied != null) {
                poolEmpytied.countDown();
            }
        }

    }

    private class TestWorkerService extends WorkerService {

        public TestWorkerService(int numWorkerThreads) {
            super("CommitProcWork", numWorkerThreads, true);
        }

        @Override
        public void schedule(WorkRequest workRequest, long id) {
            super.schedule(workRequest, id);
            if (requestScheduled != null) {
                requestScheduled.countDown();
            }
        }

    }

    private class DummyFinalProcessor implements RequestProcessor {

        int processTime;
        public DummyFinalProcessor(int processTime) {
            this.processTime = processTime;
        }

        @Override
        public void processRequest(Request request) {
            if (processTime > 0) {
                try {
                    if (commitSeen != null) {
                        commitSeen.await(5, TimeUnit.SECONDS);
                    }
                    Thread.sleep(processTime);
                } catch (Exception e) {

                }
            }
        }

        @Override
        public void shutdown() {
        }

    }

    private void checkMetrics(String metricName, long min, long max, double avg, long cnt, long sum) {
        Map<String, Object> values = MetricsUtils.currentServerMetrics();

        assertEquals(min, values.get("min_" + metricName), "expected min is " + min);
        assertEquals(max, values.get("max_" + metricName), "expected max is: " + max);
        assertEquals(avg, (Double) values.get("avg_" + metricName), 0.001, "expected avg is: " + avg);
        assertEquals(cnt, values.get("cnt_" + metricName), "expected cnt is: " + cnt);
        assertEquals(sum, values.get("sum_" + metricName), "expected sum is: " + sum);
    }

    private void checkTimeMetric(long actual, long lBoundrary, long hBoundrary) {
        assertThat(actual, greaterThanOrEqualTo(lBoundrary));
        assertThat(actual, lessThanOrEqualTo(hBoundrary));
    }

    private Request createReadRequest(long sessionId, int xid) {
        return new Request(null, sessionId, xid, ZooDefs.OpCode.getData, ByteBuffer.wrap(new byte[10]), null);
    }

    private Request createWriteRequest(long sessionId, int xid) {
        return new Request(null, sessionId, xid, ZooDefs.OpCode.setData, ByteBuffer.wrap(new byte[10]), null);
    }

    private void processRequestWithWait(Request request) throws Exception {
        requestProcessed = new CountDownLatch(1);
        commitProcessor.processRequest(request);
        requestProcessed.await(5, TimeUnit.SECONDS);
    }

    private void commitWithWait(Request request) throws Exception {
        requestProcessed = new CountDownLatch(1);
        commitProcessor.commit(request);
        requestProcessed.await(5, TimeUnit.SECONDS);
    }

    @Test
    public void testRequestsInSessionQueue() throws Exception {
        setupProcessors(0, 0);

        Request req1 = createWriteRequest(1L, 1);
        processRequestWithWait(req1);

        checkMetrics("requests_in_session_queue", 1L, 1L, 1D, 1L, 1L);

        //these two read requests will be stuck in the session queue because there is write in front of them
        processRequestWithWait(createReadRequest(1L, 2));
        processRequestWithWait(createReadRequest(1L, 3));

        checkMetrics("requests_in_session_queue", 1L, 3L, 2D, 3L, 6);

        commitWithWait(req1);

        checkMetrics("requests_in_session_queue", 1L, 3L, 2.25D, 4L, 9);
    }

    @Test
    public void testWriteFinalProcTime() throws Exception {
        setupProcessors(0, 1000);

        Request req1 = createWriteRequest(1L, 2);
        processRequestWithWait(req1);

        //no request sent to next processor yet
        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(0L, values.get("cnt_write_final_proc_time_ms"));

        commitWithWait(req1);

        values = MetricsUtils.currentServerMetrics();
        assertEquals(1L, values.get("cnt_write_final_proc_time_ms"));
        checkTimeMetric((long) values.get("max_write_final_proc_time_ms"), 1000L, 2000L);
    }

    @Test
    public void testReadFinalProcTime() throws Exception {
        setupProcessors(0, 1000);

        processRequestWithWait(createReadRequest(1L, 1));

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(1L, values.get("cnt_read_final_proc_time_ms"));
        checkTimeMetric((long) values.get("max_read_final_proc_time_ms"), 1000L, 2000L);
    }

    @Test
    public void testCommitProcessTime() throws Exception {
        setupProcessors(0, 0);
        processRequestWithWait(createReadRequest(1L, 1));

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(1L, values.get("cnt_commit_process_time"));
        checkTimeMetric((long) values.get("max_commit_process_time"), 0L, 1000L);
    }

    @Test
    public void testServerWriteCommittedTime() throws Exception {
        setupProcessors(0, 0);
        //a commit w/o pending request is a write from other servers
        commitWithWait(createWriteRequest(1L, 1));

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(1L, values.get("cnt_server_write_committed_time_ms"));
        checkTimeMetric((long) values.get("max_server_write_committed_time_ms"), 0L, 1000L);
    }

    @Test
    public void testLocalWriteCommittedTime() throws Exception {
        setupProcessors(0, 0);
        Request req1 = createWriteRequest(1L, 2);
        processRequestWithWait(req1);
        commitWithWait(req1);

        Map<String, Object> values = MetricsUtils.currentServerMetrics();

        assertEquals(1L, values.get("cnt_local_write_committed_time_ms"));
        checkTimeMetric((long) values.get("max_local_write_committed_time_ms"), 0L, 1000L);

        Request req2 = createWriteRequest(1L, 2);
        processRequestWithWait(req2);
        //the second write will be stuck in the session queue for at least one second
        //but the LOCAL_WRITE_COMMITTED_TIME is from when the commit is received
        Thread.sleep(1000);

        commitWithWait(req2);

        values = MetricsUtils.currentServerMetrics();
        assertEquals(2L, values.get("cnt_local_write_committed_time_ms"));
        checkTimeMetric((long) values.get("max_local_write_committed_time_ms"), 0L, 1000L);
    }

    @Test
    public void testWriteCommitProcTime() throws Exception {
        setupProcessors(0, 0);
        Request req1 = createWriteRequest(1L, 2);
        processRequestWithWait(req1);
        commitWithWait(req1);

        Map<String, Object> values = MetricsUtils.currentServerMetrics();

        assertEquals(1L, values.get("cnt_write_commitproc_time_ms"));
        checkTimeMetric((long) values.get("max_write_commitproc_time_ms"), 0L, 1000L);

        Request req2 = createWriteRequest(1L, 2);
        processRequestWithWait(req2);
        //the second write will be stuck in the session queue for at least one second
        Thread.sleep(1000);

        commitWithWait(req2);

        values = MetricsUtils.currentServerMetrics();
        assertEquals(2L, values.get("cnt_write_commitproc_time_ms"));
        checkTimeMetric((long) values.get("max_write_commitproc_time_ms"), 1000L, 2000L);
    }

    @Test
    public void testReadCommitProcTime() throws Exception {
        setupProcessors(0, 0);
        processRequestWithWait(createReadRequest(1L, 1));

        Map<String, Object> values = MetricsUtils.currentServerMetrics();

        assertEquals(1L, values.get("cnt_read_commitproc_time_ms"));
        checkTimeMetric((long) values.get("max_read_commitproc_time_ms"), 0L, 1000L);

        Request req1 = createWriteRequest(1L, 2);
        processRequestWithWait(req1);
        processRequestWithWait(createReadRequest(1L, 3));
        //the second read will be stuck in the session queue for at least one second
        Thread.sleep(1000);

        commitWithWait(req1);

        values = MetricsUtils.currentServerMetrics();
        assertEquals(2L, values.get("cnt_read_commitproc_time_ms"));
        checkTimeMetric((long) values.get("max_read_commitproc_time_ms"), 1000L, 2000L);
    }

    @Test
    public void testTimeWaitingEmptyPoolInCommitProcessorRead() throws Exception {
        setupProcessors(1, 1000);

        //three read requests will be scheduled first
        requestScheduled = new CountDownLatch(3);
        commitProcessor.processRequest(createReadRequest(0L, 2));
        commitProcessor.processRequest(createReadRequest(1L, 3));
        commitProcessor.processRequest(createReadRequest(2L, 4));
        requestScheduled.await(5, TimeUnit.SECONDS);

        //add a commit request to trigger waitForEmptyPool
        poolEmpytied = new CountDownLatch(1);
        commitProcessor.commit(createWriteRequest(1L, 1));
        poolEmpytied.await(5, TimeUnit.SECONDS);

        long actual = (long) MetricsUtils.currentServerMetrics().get("max_time_waiting_empty_pool_in_commit_processor_read_ms");
        //since each request takes 1000ms to process, so the waiting shouldn't be more than three times of that
        checkTimeMetric(actual, 2500L, 3500L);
    }

    @Test
    public void testConcurrentRequestProcessingInCommitProcessor() throws Exception {
        setupProcessors(3, 1000);

        //three read requests will be processed in parallel
        commitSeen = new CountDownLatch(1);
        requestScheduled = new CountDownLatch(3);
        commitProcessor.processRequest(createReadRequest(1L, 2));
        commitProcessor.processRequest(createReadRequest(1L, 3));
        commitProcessor.processRequest(createReadRequest(1L, 4));
        requestScheduled.await(5, TimeUnit.SECONDS);

        //add a commit request to trigger waitForEmptyPool, which will record number of requests being proccessed
        poolEmpytied = new CountDownLatch(1);
        commitProcessor.commit(createWriteRequest(1L, 1));
        poolEmpytied.await(5, TimeUnit.SECONDS);

        //this will change after we upstream batch write in CommitProcessor
        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(3L, values.get("max_concurrent_request_processing_in_commit_processor"));
    }

    @Test
    public void testReadsAfterWriteInSessionQueue() throws Exception {
        setupProcessors(0, 0);
        //this read request is before write
        processRequestWithWait(createReadRequest(1L, 1));

        //one write request
        Request req1 = createWriteRequest(1L, 1);
        processRequestWithWait(req1);

        //three read requests after the write
        processRequestWithWait(createReadRequest(1L, 2));
        processRequestWithWait(createReadRequest(1L, 3));
        processRequestWithWait(createReadRequest(1L, 4));

        //commit the write
        commitWithWait(req1);

        checkMetrics("reads_after_write_in_session_queue", 3L, 3L, 3d, 1, 3);
    }

    @Test
    public void testReadsQueuedInCommitProcessor() throws Exception {
        setupProcessors(0, 0);
        processRequestWithWait(createReadRequest(1L, 1));
        processRequestWithWait(createReadRequest(1L, 2));

        //recorded reads in the queue are 1, 1
        checkMetrics("read_commit_proc_req_queued", 1L, 1L, 1d, 2, 2);
    }

    @Test
    public void testWritesQueuedInCommitProcessor() throws Exception {
        setupProcessors(0, 0);
        Request req1 = createWriteRequest(1L, 1);
        processRequestWithWait(req1);
        Request req2 = createWriteRequest(1L, 2);
        processRequestWithWait(req2);

        //since we haven't got any commit request, the write request stays in the queue
        //recorded writes in the queue are 1, 2
        checkMetrics("write_commit_proc_req_queued", 1L, 2L, 1.5d, 2, 3);

        commitWithWait(req1);

        //recording is done before commit request is processed, so writes in the queue are: 1, 2, 2
        checkMetrics("write_commit_proc_req_queued", 1L, 2L, 1.6667d, 3, 5);

        commitWithWait(req2);
        //writes in the queue are 1, 2, 2, 1
        checkMetrics("write_commit_proc_req_queued", 1L, 2L, 1.5d, 4, 6);

        //send a read request to trigger the recording, this time the write queue should be empty
        //writes in the queue are 1, 2, 2, 1, 0
        processRequestWithWait(createReadRequest(1L, 1));

        checkMetrics("write_commit_proc_req_queued", 0L, 2L, 1.2d, 5, 6);
    }

    @Test
    public void testCommitsQueuedInCommitProcessor() throws Exception {
        setupProcessors(0, 0);

        commitWithWait(createWriteRequest(1L, 1));
        commitWithWait(createWriteRequest(1L, 2));

        //recorded commits in the queue are 1, 1
        checkMetrics("commit_commit_proc_req_queued", 1L, 1L, 1d, 2, 2);
    }

    @Test
    public void testCommitsQueued() throws Exception {
        setupProcessors(0, 0);

        commitWithWait(createWriteRequest(1L, 1));
        commitWithWait(createWriteRequest(1L, 2));

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(2L, (long) values.get("request_commit_queued"));
    }

    @Test
    public void testPendingSessionQueueSize() throws Exception {
        setupProcessors(0, 0);

        //one write request for session 1
        Request req1 = createWriteRequest(1L, 1);
        processRequestWithWait(req1);

        //two write requests for session 2
        Request req2 = createWriteRequest(2L, 2);
        processRequestWithWait(req2);
        Request req3 = createWriteRequest(2L, 3);
        processRequestWithWait(req3);

        commitWithWait(req1);
        //there are two sessions with pending requests
        checkMetrics("pending_session_queue_size", 2L, 2L, 2d, 1, 2);

        commitWithWait(req2);
        //there is on session with pending requests
        checkMetrics("pending_session_queue_size", 1L, 2L, 1.5d, 2, 3);

        commitWithWait(req3);
        //there is one session with pending requests
        checkMetrics("pending_session_queue_size", 1L, 2L, 1.333d, 3, 4);
    }

}
