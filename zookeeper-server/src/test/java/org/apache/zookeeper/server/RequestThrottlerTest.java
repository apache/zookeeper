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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestThrottlerTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(RequestThrottlerTest.class);

    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private static String GLOBAL_OUTSTANDING_LIMIT = "1";
    private static final int TOTAL_REQUESTS = 5;
    private static final int STALL_TIME = 5000;

    // latch to hold requests in the PrepRequestProcessor to
    // keep them from going down the pipeline to reach the final
    // request processor, where the number of in process requests
    // will be decreased
    CountDownLatch resumeProcess = null;

    // latch to make sure all requests are submitted
    CountDownLatch submitted = null;

    // latch to make sure all requests entered the pipeline
    CountDownLatch entered = null;

    // latch to make sure requests finished the pipeline
    CountDownLatch finished = null;

    CountDownLatch disconnected = null;

    CountDownLatch throttled = null;
    CountDownLatch throttling = null;

    ZooKeeperServer zks = null;
    ServerCnxnFactory f = null;
    ZooKeeper zk = null;
    int connectionLossCount = 0;

    @BeforeEach
    public void setup() throws Exception {
        // start a server and create a client
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        zks = new TestZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        LOG.info("starting up the zookeeper server .. waiting");
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up");

        resumeProcess = null;
        submitted = null;

        zk = ClientBase.createZKClient(HOSTPORT);
    }

    @AfterEach
    public void tearDown() throws Exception {
        // shut down the server and the client
        if (null != zk) {
            zk.close();
        }

        if (null != f) {
            f.shutdown();
        }
        if (null != zks) {
            zks.shutdown();
        }
    }

    // TestZooKeeperServer
    // 1. uses our version of PrepRequestProcessor, which can hold the request as long as we want
    // 2. count the number of submitted requests
    class TestZooKeeperServer extends ZooKeeperServer {

        public TestZooKeeperServer(File snapDir, File logDir, int tickTime) throws IOException {
            super(snapDir, logDir, tickTime);
        }

        @Override
        protected RequestThrottler createRequestThrottler() {
            return new TestRequestThrottler(this);
        }

        @Override
        protected void setupRequestProcessors() {
            RequestProcessor finalProcessor = new FinalRequestProcessor(this);
            RequestProcessor syncProcessor = new SyncRequestProcessor(this, finalProcessor);
            ((SyncRequestProcessor) syncProcessor).start();
            firstProcessor = new TestPrepRequestProcessor(this, syncProcessor);
            ((TestPrepRequestProcessor) firstProcessor).start();
        }

        @Override
        public void submitRequest(Request si) {
            if (null != submitted) {
                submitted.countDown();
            }
            super.submitRequest(si);
        }

        @Override
        public void requestFinished(Request request) {
            if (null != finished){
                finished.countDown();
            }
            super.requestFinished(request);
        }
    }

    class TestRequestThrottler extends RequestThrottler {
        public TestRequestThrottler(ZooKeeperServer zks) {
            super(zks);
        }

        @Override
        synchronized void throttleSleep(int stallTime) throws InterruptedException {
            if (throttling != null) {
                throttling.countDown();
            }
            super.throttleSleep(stallTime);
            // Defend against unstable timing and potential spurious wakeup.
            if (throttled != null) {
                assertTrue(throttled.await(20, TimeUnit.SECONDS));
            }
        }
    }

    class TestPrepRequestProcessor extends PrepRequestProcessor {

        public TestPrepRequestProcessor(ZooKeeperServer zks, RequestProcessor syncProcessor) {
            super(zks, syncProcessor);
        }

        @Override
        protected void pRequest(Request request) throws RequestProcessorException {
            // keep the request in the processor as long as we want
            if (resumeProcess != null) {
                try {
                    resumeProcess.await(20, TimeUnit.SECONDS);
                } catch (Exception e) {

                }
            }

            if (entered != null) {
                entered.countDown();
            }

            super.pRequest(request);
        }

    }

    @Test
    public void testRequestThrottler() throws Exception {
        ServerMetrics.getMetrics().resetAll();

        // we only allow two requests in the pipeline
        RequestThrottler.setMaxRequests(2);

        RequestThrottler.setStallTime(STALL_TIME);
        RequestThrottler.setDropStaleRequests(false);

        // no requests can go through the pipeline unless we raise the latch
        resumeProcess = new CountDownLatch(1);
        submitted = new CountDownLatch(TOTAL_REQUESTS);
        entered = new CountDownLatch(TOTAL_REQUESTS);

        // send 5 requests asynchronously
        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            zk.create("/request_throttle_test- " + i, ("/request_throttle_test- "
                                                               + i).getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (rc, path, ctx, name) -> {
            }, null);
        }

        // make sure the server received all 5 requests
        submitted.await(5, TimeUnit.SECONDS);

        // but only two requests can get into the pipeline because of the throttler
        waitForMetric("prep_processor_request_queued", is(2L));
        waitForMetric("request_throttle_wait_count", greaterThanOrEqualTo(1L));

        // let the requests go through the pipeline and the throttler will be waken up to allow more requests
        // to enter the pipeline
        resumeProcess.countDown();

        // wait for more than one STALL_TIME to reduce timeout before wakeup
        assertTrue(entered.await(STALL_TIME + 5000, TimeUnit.MILLISECONDS));

        Map<String, Object> metrics = MetricsUtils.currentServerMetrics();
        assertEquals(TOTAL_REQUESTS, (long) metrics.get("prep_processor_request_queued"));
    }

    @Test
    public void testDropStaleRequests() throws Exception {
        ServerMetrics.getMetrics().resetAll();

        // we only allow two requests in the pipeline
        RequestThrottler.setMaxRequests(2);

        RequestThrottler.setStallTime(STALL_TIME);

        RequestThrottler.setDropStaleRequests(true);

        // no requests can go through the pipeline unless we raise the latch
        resumeProcess = new CountDownLatch(1);
        submitted = new CountDownLatch(TOTAL_REQUESTS);

        throttled = new CountDownLatch(1);
        throttling = new CountDownLatch(1);

        // send 5 requests asynchronously
        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            zk.create("/request_throttle_test- " + i, ("/request_throttle_test- "
                                                               + i).getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (rc, path, ctx, name) -> {
            }, null);
        }

        // make sure the server received all 5 requests
        assertTrue(submitted.await(5, TimeUnit.SECONDS));

        // stale throttled requests
        assertTrue(throttling.await(5, TimeUnit.SECONDS));
        for (ServerCnxn cnxn : f.cnxns) {
            cnxn.setStale();
        }
        throttled.countDown();
        zk = null;

        // only first three requests are counted as finished
        finished = new CountDownLatch(3);

        // let the requests go through the pipeline
        resumeProcess.countDown();
        LOG.info("raise the latch");

        while (zks.getInflight() > 0) {
            Thread.sleep(50);
        }

        assertTrue(finished.await(5, TimeUnit.SECONDS));

        // assert after all requests processed to avoid concurrent issues as metrics are
        // counted in different threads.
        Map<String, Object> metrics = MetricsUtils.currentServerMetrics();

        // only two requests can get into the pipeline because of the throttler
        assertEquals(2L, (long) metrics.get("prep_processor_request_queued"));

        // the rest of the 3 requests will be dropped
        // but only the first one for a connection will be counted
        assertEquals(1L, (long) metrics.get("request_throttle_wait_count"));
        assertEquals(1, (long) metrics.get("stale_requests_dropped"));
    }

    @Test
    public void testLargeRequestThrottling() throws Exception {
        ServerMetrics.getMetrics().resetAll();

        AsyncCallback.StringCallback createCallback = (rc, path, ctx, name) -> {
            if (KeeperException.Code.get(rc) == KeeperException.Code.CONNECTIONLOSS) {
                connectionLossCount++;
                disconnected.countDown();
            }
        };

        // the total length of the request is about 170-180 bytes, so only two requests are allowed
        byte[] data = new byte[100];
        // the third request will incur throttle. We don't send more requests to avoid reconnecting
        // due to unstable test environment(e.g. slow sending).
        int number_requests = 3;

        // we allow more requests in the pipeline
        RequestThrottler.setMaxRequests(number_requests + 2);

        // request could become stale in processor threads due to throttle in io thread
        RequestThrottler.setDropStaleRequests(false);

        // enable large request throttling
        zks.setLargeRequestThreshold(150);
        zks.setLargeRequestMaxBytes(400);

        // no requests can go through the pipeline unless we raise the latch
        resumeProcess = new CountDownLatch(1);
        // the connection will be close when large requests exceed the limit
        // we can't use the submitted latch because requests after close won't be submitted
        disconnected = new CountDownLatch(number_requests);

        // send requests asynchronously
        for (int i = 0; i < number_requests; i++) {
            zk.create("/request_throttle_test- " + i , data,
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, createCallback, null);
        }

        // make sure the server received all requests
        assertTrue(disconnected.await(30, TimeUnit.SECONDS));

        finished = new CountDownLatch(2);
        // let the requests go through the pipeline
        resumeProcess.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));

        // assert metrics after finished so metrics in no io threads are set also.
        Map<String, Object> metrics = MetricsUtils.currentServerMetrics();

        // but only two requests can get into the pipeline because they are large requests
        // the connection will be closed
        assertEquals(2L, (long) metrics.get("prep_processor_request_queued"));
        assertEquals(1L, (long) metrics.get("large_requests_rejected"));
        assertEquals(number_requests, connectionLossCount);

        // when the two requests finish, they are stale because the connection is closed already
        assertEquals(2, (long) metrics.get("stale_replies"));
    }

    @Test
    public void testGlobalOutstandingRequestThrottlingWithRequestThrottlerDisabled() throws Exception {
        try {
            System.setProperty(ZooKeeperServer.GLOBAL_OUTSTANDING_LIMIT, GLOBAL_OUTSTANDING_LIMIT);

            ServerMetrics.getMetrics().resetAll();

            // Here we disable RequestThrottler and let incoming requests queued at first request processor.
            RequestThrottler.setMaxRequests(0);
            resumeProcess = new CountDownLatch(1);
            int totalRequests = 10;

            for (int i = 0; i < totalRequests; i++) {
                zk.create("/request_throttle_test- " + i, ("/request_throttle_test- "
                        + i).getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (rc, path, ctx, name) -> {
                }, null);
            }

            // We should start throttling instead of queuing more requests.
            //
            // We always allow up to GLOBAL_OUTSTANDING_LIMIT + 1 number of requests coming in request processing pipeline
            // before throttling. For the next request, we will throttle by disabling receiving future requests but we still
            // allow this single request coming in. Ideally, the total number of queued requests in processing pipeline would
            // be GLOBAL_OUTSTANDING_LIMIT + 2.
            //
            // But due to leak of consistent view of number of outstanding requests, the number could be larger.
            waitForMetric("prep_processor_request_queued", greaterThanOrEqualTo(Long.parseLong(GLOBAL_OUTSTANDING_LIMIT) + 2));

            resumeProcess.countDown();
        } catch (Exception e) {
            throw e;
        } finally {
            System.clearProperty(ZooKeeperServer.GLOBAL_OUTSTANDING_LIMIT);
        }
    }
}
