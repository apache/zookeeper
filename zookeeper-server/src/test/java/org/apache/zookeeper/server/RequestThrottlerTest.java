package org.apache.zookeeper.server;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;

public class RequestThrottlerTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(RequestThrottlerTest.class);

    private static String oldRequestThrottle = null;
    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private final static int TOTAL_REQUESTS = 5;
    private final static int STALL_TIME = 5000;

    // latch to hold requests in the PrepRequestProcessor to
    // keep them from going down the pipeline to go to the final
    // request processor, where the number of in process requests
    // will be decreased
    CountDownLatch resumeProcess = null;

    // latch to make sure all requests are submitted
    CountDownLatch submitted = null;

    ZooKeeperServer zks = null;
    ServerCnxnFactory f = null;
    ZooKeeper zk = null;

    @BeforeClass
    public static void enableRequestThrottle() {
        oldRequestThrottle = System.getProperty("zookeeper.request_throttle");
        System.setProperty("zookeeper.request_throttle", "true");
    }

    @Before
    public void setup() throws Exception {
        // start a server and create a client
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        zks = new TestZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        LOG.info("starting up the zookeeper server .. waiting");
        Assert.assertTrue("waiting for server being up",
                ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));

        resumeProcess = null;
        submitted = null;

        zk = ClientBase.createZKClient(HOSTPORT);
    }

    @After
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

    @AfterClass
    public static void disableRequestThrottle() {
        if (null == oldRequestThrottle) {
            System.clearProperty("zookeeper.request_throttle");
        } else {
            System.setProperty("zookeeper.request_throttle", oldRequestThrottle);
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
        protected void setupRequestProcessors() {
            RequestProcessor finalProcessor = new FinalRequestProcessor(this);
            RequestProcessor syncProcessor = new SyncRequestProcessor(this,
                    finalProcessor);
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

            super.pRequest(request);
        }
    }

    @Test
    public void testRequestThrottlerSleepEnabled() throws Exception {
        ServerMetrics.getMetrics().resetAll();

        // we only allow two requests in the pipeline
        RequestThrottler.setMaxRequests(2);

        // the throttler will sleep for STALL_TIME seconds when stalled
        RequestThrottler.setSleepEnabled(true);
        RequestThrottler.setStallTime(STALL_TIME);

        // no requests can go through the pipeline unless we raise the latch
        resumeProcess = new CountDownLatch(1);
        submitted = new CountDownLatch(TOTAL_REQUESTS);

        // send 5 requests asynchronously
        for (int i =0; i < TOTAL_REQUESTS; i++) {
            zk.create("/request_throttle_test- " + i , ("/request_throttle_test- " + i).getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (rc, path, ctx, name) -> {}, null);
        }

        // make sure the server received all 5 requests
        submitted.await(5, TimeUnit.SECONDS);
        Map<String, Object> metrics = MetricsUtils.currentServerMetrics();

        // but only two requests can get into the pipeline because of the throttler
        Assert.assertEquals(2L, (long)metrics.get("prep_processor_request_queued"));

        // let the requests go through the pipeline
        resumeProcess.countDown();
        long startPipeline = Time.currentWallTime();
        while (zks.getInflight() > 0) {
            Thread.sleep(50);
        }

        // even the in-progress requests decrease, the throttler won't resume processing until it sleeps for stallTime
        Assert.assertThat(Time.currentWallTime() - startPipeline, greaterThan(8L));

        metrics = MetricsUtils.currentServerMetrics();
        Assert.assertThat((long)metrics.get("prep_processor_request_queued"), greaterThanOrEqualTo((long)TOTAL_REQUESTS));
        Assert.assertThat((long)metrics.get("request_throttle_stall_time"), greaterThanOrEqualTo((long)STALL_TIME));
    }

    @Test
    public void testRequestThrottlerSleepDisabled() throws Exception {
        ServerMetrics.getMetrics().resetAll();

        // we only allow two requests in the pipeline
        RequestThrottler.setMaxRequests(2);

        // sleep is disabled so the throttler will wait for the number of in-progress requests to be decreased
        RequestThrottler.setSleepEnabled(false);
        RequestThrottler.setStallTime(STALL_TIME);

        // no requests can go through the pipeline unless we raise the latch
        resumeProcess = new CountDownLatch(1);
        submitted = new CountDownLatch(TOTAL_REQUESTS);

        // send 5 requests asynchronously
        for (int i =0; i < TOTAL_REQUESTS; i++) {
            zk.create("/request_throttle_test- " + i , ("/request_throttle_test- " + i).getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (rc, path, ctx, name) -> {}, null);
        }

        // make sure the server received all 5 requests
        submitted.await(5, TimeUnit.SECONDS);
        Map<String, Object> metrics = MetricsUtils.currentServerMetrics();

        // but only two requests can get into the pipeline because of the throttler
        Assert.assertEquals(2L, (long)metrics.get("prep_processor_request_queued"));
        Assert.assertEquals(1L, (long)metrics.get("request_throttle_wait_count"));

        // let the requests go through the pipeline and the throttle will be waken up to allow more requests
        // to enter the pipeline
        resumeProcess.countDown();
        long startPipeline = Time.currentWallTime();
        while (zks.getInflight() > 0) {
            Thread.sleep(50);
        }

        // the throttler is waken up when a request finishes processing so it won't stall for stallTime
        Assert.assertThat(Time.currentWallTime() - startPipeline, lessThan(1000L));

        metrics = MetricsUtils.currentServerMetrics();
        Assert.assertThat((long)metrics.get("prep_processor_request_queued"), greaterThanOrEqualTo((long)TOTAL_REQUESTS));
    }

    @Test
    public void testDropStaleRequests() throws Exception {
        ServerMetrics.getMetrics().resetAll();

        // we only allow two requests in the pipeline
        RequestThrottler.setMaxRequests(2);

        // sleep is disabled so the throttler will wait for the number of in-progress requests to be decreased
        // and make the wait time to be 10 seconds, so it should never timeout and the throttler is always waken
        // by the decrease of in-progress requests
        RequestThrottler.setSleepEnabled(false);
        RequestThrottler.setStallTime(STALL_TIME);

        RequestThrottler.setDropStaleRequests(true);

        // no requests can go through the pipeline unless we raise the latch
        resumeProcess = new CountDownLatch(1);
        submitted = new CountDownLatch(TOTAL_REQUESTS);

        // send 5 requests asynchronously
        for (int i=0; i<TOTAL_REQUESTS; i++) {
            zk.create("/request_throttle_test- " + i , ("/request_throttle_test- " + i).getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (rc, path, ctx, name) -> {}, null);
        }

        // make sure the server received all 5 requests
        submitted.await(5, TimeUnit.SECONDS);
        Map<String, Object> metrics = MetricsUtils.currentServerMetrics();

        // but only two requests can get into the pipeline because of the throttler
        Assert.assertEquals(2L, (long)metrics.get("prep_processor_request_queued"));
        Assert.assertEquals(1L, (long)metrics.get("request_throttle_wait_count"));

        for (ServerCnxn cnxn : f.cnxns){
            cnxn.setStale();
        }
        zk = null;

        resumeProcess.countDown();
        LOG.info("raise the latch");

        while (zks.getInflight() > 0) {
            Thread.sleep(50);
        }

        // the rest of the 3 requests will be dropped
        // but only the first one for a connection will be counted
        metrics = MetricsUtils.currentServerMetrics();
        Assert.assertEquals(1, (long)metrics.get("stale_requests_dropped"));
    }
}
