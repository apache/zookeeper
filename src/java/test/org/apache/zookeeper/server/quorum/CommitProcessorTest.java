/**
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.GetDataRequest;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.PrepRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The following are invariant regardless of the particular implementation
 * of the CommitProcessor, and are tested for:
 *
 * 1. For each session, requests are processed and the client sees its
 *    responses in order.
 * 2. Write requests are processed in zxid order across all sessions.
 *
 * The following are also tested for here, but are specific to this
 * particular implementation. The underlying issue is that watches can be
 * reset while reading the data. For reads/writes on two different sessions
 * on different nodes, or with reads that do not set watches, the reads can
 * happen in any order relative to the writes. For a read in one session that
 * resets a watch that is triggered by a write on another session, however,
 * we need to ensure that there is no race condition
 *
 * 3. The pipeline needs to be drained before a write request can enter.
 * 4. No in-flight write requests while processing a read request.
 */
public class CommitProcessorTest extends ZKTestCase {
    protected static final Logger LOG =
        LoggerFactory.getLogger(CommitProcessorTest.class);

    // The amount of ms each test case should run
    static final int TEST_RUN_TIME_IN_MS = 5000;
    private AtomicInteger processedReadRequests = new AtomicInteger(0);
    private AtomicInteger processedWriteRequests = new AtomicInteger(0);

    boolean stopped;
    TestZooKeeperServer zks;
    File tmpDir;
    ArrayList<TestClientThread> testClients =
            new ArrayList<TestClientThread>();
    CommitProcessor commitProcessor;

    public void setUp(int numCommitThreads, int numClientThreads, int writePercent)
            throws Exception {
        stopped = false;
        System.setProperty(
            CommitProcessor.ZOOKEEPER_COMMIT_PROC_NUM_WORKER_THREADS,
            Integer.toString(numCommitThreads));
        tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        zks = new TestZooKeeperServer(tmpDir, tmpDir, 4000);
        zks.startup();
        for(int i=0; i<numClientThreads; ++i) {
            TestClientThread client = new TestClientThread(writePercent);
            testClients.add(client);
            client.start();
        }
    }
    
    public void setUp(int numCommitThreads, int numReadOnlyClientThreads, int mixWorkloadClientThreads, int writePercent)
            throws Exception {
        stopped = false;
        System.setProperty(
            CommitProcessor.ZOOKEEPER_COMMIT_PROC_NUM_WORKER_THREADS,
            Integer.toString(numCommitThreads));
        tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        zks = new TestZooKeeperServer(tmpDir, tmpDir, 4000);
        zks.startup();
        for(int i=0; i<mixWorkloadClientThreads; ++i) {
            TestClientThread client = new TestClientThread(writePercent);
            testClients.add(client);
            client.start();
        }
        for(int i=0; i<numReadOnlyClientThreads; ++i) {
            TestClientThread client = new TestClientThread(0);
            testClients.add(client);
            client.start();
        }
    }

    @After
    public void tearDown() throws Exception {
        LOG.info("tearDown starting");
        stopped = true;

        zks.shutdown();
        for(TestClientThread client : testClients) {
            client.interrupt();
            client.join();
        }
        if (tmpDir != null) {
            Assert.assertTrue("delete " + tmpDir.toString(),
                              ClientBase.recursiveDelete(tmpDir));
        }
        processedReadRequests.set(0);
        processedWriteRequests.set(0);
        testClients.clear();
        commitProcessor.join();
    }

    private class TestClientThread extends Thread {
        long sessionId;
        int cxid;
        int nodeId;
        int writePercent;

        public TestClientThread(int writePercent) {
            sessionId = zks.getSessionTracker().createSession(5000);
            this.writePercent = writePercent;
        }

        public void sendWriteRequest() throws Exception {
            ByteArrayOutputStream boas = new ByteArrayOutputStream();
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(boas);
            CreateRequest createReq = new CreateRequest(
                "/session" + Long.toHexString(sessionId) + "-" + (++nodeId),
                new byte[0], Ids.OPEN_ACL_UNSAFE, 1);
            createReq.serialize(boa, "request");
            ByteBuffer bb = ByteBuffer.wrap(boas.toByteArray());
            Request req = new Request(null, sessionId, ++cxid, OpCode.create,
                                      bb, new ArrayList<Id>());
            zks.getFirstProcessor().processRequest(req);

        }

        public void sendReadRequest() throws Exception {
            ByteArrayOutputStream boas = new ByteArrayOutputStream();
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(boas);
            GetDataRequest getDataRequest = new GetDataRequest(
                "/session" + Long.toHexString(sessionId) + "-" + nodeId, false);
            getDataRequest.serialize(boa, "request");
            ByteBuffer bb = ByteBuffer.wrap(boas.toByteArray());
            Request req = new Request(null, sessionId, ++cxid, OpCode.getData,
                                      bb, new ArrayList<Id>());
            zks.getFirstProcessor().processRequest(req);
        }

        public void run() {
            Random rand = new Random(Thread.currentThread().getId());
            try {
                sendWriteRequest();
                while(!stopped) {
                    if (rand.nextInt(100) < writePercent) {
                        sendWriteRequest();
                    } else {
                        sendReadRequest();
                    }
                    Thread.sleep(5 + rand.nextInt(95));
                }
            } catch (Exception e) {
                LOG.error("Uncaught exception in test: ", e);
            }
        }
    }

    @Test
    public void testNoCommitWorkersReadOnlyWorkload() throws Exception {
        int numClients = 10;
        LOG.info("testNoCommitWorkersReadOnlyWorkload");
        setUp(0, numClients, 0);
        synchronized (this) {
            wait(TEST_RUN_TIME_IN_MS);
        }
        Assert.assertFalse(fail);
        Assert.assertTrue("No read requests processed", processedReadRequests.get() > 0);
        // processedWriteRequests.get() == numClients since each client performs one write at the beginning (creates a znode)
        Assert.assertTrue("Write requests processed", processedWriteRequests.get() == numClients);
    }
    
    @Test
    public void testNoCommitWorkersMixedWorkload() throws Exception {
        int numClients = 10;
        LOG.info("testNoCommitWorkersMixedWorkload 25w/75r workload test");
        setUp(0, numClients, 25);
        synchronized (this) {
            wait(TEST_RUN_TIME_IN_MS);
        }
        Assert.assertFalse(fail);
        checkProcessedRequest();
    }
    
    @Test
    public void testOneCommitWorkerReadOnlyWorkload() throws Exception {
        int numClients = 10;
        LOG.info("testOneCommitWorkerReadOnlyWorkload");
        setUp(1, numClients, 0);
        synchronized (this) {
            wait(TEST_RUN_TIME_IN_MS);
        }
        Assert.assertFalse(fail);
        Assert.assertTrue("No read requests processed", processedReadRequests.get() > 0);
        // processedWriteRequests.get() == numClients since each client performs one write at the beginning (creates a znode)
        Assert.assertTrue("Write requests processed", processedWriteRequests.get() == numClients);
    }

    @Test
    public void testOneCommitWorkerMixedWorkload() throws Exception {
        setUp(1, 10, 25);
        LOG.info("testOneCommitWorkerMixedWorkload 25w/75r workload test");
        synchronized(this) {
            wait(TEST_RUN_TIME_IN_MS);
        }
        Assert.assertFalse(fail);
        checkProcessedRequest();
    }
    
    
    @Test
    public void testManyCommitWorkersReadOnly() throws Exception {
        int numClients = 10;
        LOG.info("testManyCommitWorkersReadOnly");
        setUp(10, numClients, 0);
        synchronized(this) {
            wait(TEST_RUN_TIME_IN_MS);
        }
        Assert.assertFalse(fail);
        Assert.assertTrue("No read requests processed", processedReadRequests.get() > 0);
        // processedWriteRequests.get() == numClients since each client performs one write at the beginning (creates a znode)
        Assert.assertTrue("Write requests processed", processedWriteRequests.get() == numClients);
    }
    
    @Test
    public void testManyCommitWorkersMixedWorkload() throws Exception {
        setUp(16, 8 , 8, 25);
        LOG.info("testManyCommitWorkersMixedWorkload 8X0w/100r + 8X25w/75r workload test");
        synchronized(this) {
            wait(TEST_RUN_TIME_IN_MS);
        }
        Assert.assertFalse(fail);
        checkProcessedRequest();
    }

    private void checkProcessedRequest() {
        Assert.assertTrue("No read requests processed", processedReadRequests.get() > 0);
        Assert.assertTrue("No write requests processed", processedWriteRequests.get() > 0);
    }

    volatile boolean fail = false;
    synchronized private void failTest(String reason) {
        fail = true;
        notifyAll();
        Assert.fail(reason);
    }

    private class TestZooKeeperServer extends ZooKeeperServer {
        public TestZooKeeperServer(File snapDir, File logDir, int tickTime)
                throws IOException {
            super(snapDir, logDir, tickTime);
        }

        public PrepRequestProcessor getFirstProcessor(){
            return (PrepRequestProcessor) firstProcessor;
        }

        // Leader mock: Prep -> MockProposal -> Commit -> validate -> Final
        // Have side thread call commitProc.commit()
        @Override
        protected void setupRequestProcessors() {
            RequestProcessor finalProcessor = new FinalRequestProcessor(zks);
            // ValidateProcessor is set up in a similar fashion to ToBeApplied
            // processor, so it can do pre/post validating of requests
            ValidateProcessor validateProcessor =
                new ValidateProcessor(finalProcessor);
            commitProcessor = new CommitProcessor(validateProcessor, "1", true, null);
            validateProcessor.setCommitProcessor(commitProcessor);
            commitProcessor.start();
            MockProposalRequestProcessor proposalProcessor =
                new MockProposalRequestProcessor(commitProcessor);
            proposalProcessor.start();
            firstProcessor = new PrepRequestProcessor(zks, proposalProcessor);
            getFirstProcessor().start();
        }
    }
    
    private class MockProposalRequestProcessor extends Thread
            implements RequestProcessor {
        private final CommitProcessor commitProcessor;
        private final LinkedBlockingQueue<Request> proposals =
            new LinkedBlockingQueue<Request>();

        public MockProposalRequestProcessor(CommitProcessor commitProcessor) {
            this.commitProcessor = commitProcessor;
        }

        @Override
        public void run() {
            Random rand = new Random(Thread.currentThread().getId());
            try {
                while(true) {
                    // If it is a read-only test, there will be no proposals..
                    if (!proposals.isEmpty()){
                        Request request = proposals.take();
                        Thread.sleep(5 + rand.nextInt(95));
                        commitProcessor.commit(request);
                    }
                }
            } catch (InterruptedException e) {
                // ignore
            }
        }

        @Override
        public void processRequest(Request request)
                throws RequestProcessorException {
            commitProcessor.processRequest(request);
            if (request.getHdr() != null) {
                // fake propose request
                proposals.add(request);
            }
        }

        @Override
        public void shutdown() {
            LOG.info("shutdown MockProposalRequestProcessor");
            proposals.clear();
            if (commitProcessor != null) {
                commitProcessor.shutdown();
            }
        }
    }

    
    private class ValidateProcessor implements RequestProcessor {
        Random rand = new Random(Thread.currentThread().getId());
        RequestProcessor nextProcessor;
        CommitProcessor commitProcessor;
        AtomicLong expectedZxid = new AtomicLong(1);
        ConcurrentHashMap<Long, AtomicInteger> cxidMap =
            new ConcurrentHashMap<Long, AtomicInteger>();

        AtomicInteger outstandingReadRequests = new AtomicInteger(0);
        AtomicInteger outstandingWriteRequests = new AtomicInteger(0);

        public ValidateProcessor(RequestProcessor nextProcessor) {
            this.nextProcessor = nextProcessor;
        }

        public void setCommitProcessor(CommitProcessor commitProcessor) {
            this.commitProcessor = commitProcessor;
        }


        @Override
        public void processRequest(Request request)
                throws RequestProcessorException {
            if (stopped)
                return;
            if (request.type == OpCode.closeSession){
                LOG.debug("ValidateProcessor got closeSession request=" + request);
                nextProcessor.processRequest(request);
                return;
            }

            boolean isWriteRequest = commitProcessor.needCommit(request);
            if (isWriteRequest) {
                outstandingWriteRequests.incrementAndGet();
                validateWriteRequestVariant(request);
                LOG.debug("Starting write request zxid=" + request.zxid);
            } else {
                LOG.debug("Starting read request cxid="
                        + request.cxid + " for session 0x"
                        + Long.toHexString(request.sessionId));
                outstandingReadRequests.incrementAndGet();
                validateReadRequestVariant(request);
            }
            
            // Insert random delay to test thread race conditions
            try {
                Thread.sleep(5 + rand.nextInt(25));
            } catch(InterruptedException e) {
                // ignore
            }
            nextProcessor.processRequest(request);
            /*
             * The commit workers will have to execute this line before they
             * wake up the commit processor. So this value is up-to-date when
             * variant check is performed
             */
            if (isWriteRequest) {
                outstandingWriteRequests.decrementAndGet();
                LOG.debug("Done write request zxid=" + request.zxid);
                processedWriteRequests.incrementAndGet();
            } else {
                outstandingReadRequests.decrementAndGet();
                LOG.debug("Done read request cxid="
                        + request.cxid + " for session 0x"
                        + Long.toHexString(request.sessionId));
                processedReadRequests.incrementAndGet();
            }
            validateRequest(request);
        }

        /**
         * Validate that this is the only request in the pipeline
         */
        private void validateWriteRequestVariant(Request request) {
            if (stopped)
                return;
            long zxid = request.getHdr().getZxid();
            int readRequests = outstandingReadRequests.get();
            if (readRequests != 0) {
                failTest("There are " + readRequests + " outstanding"
                        + " read requests while issuing a write request zxid="
                        + zxid);
            }
            int writeRequests = outstandingWriteRequests.get();
            if (writeRequests > 1) {
                failTest("There are " + writeRequests + " outstanding"
                        + " write requests while issuing a write request zxid="
                        + zxid + " (expected one)");
            }
        }

        /**
         * Validate that no write request is in the pipeline while working
         * on a read request
         */
        private void validateReadRequestVariant(Request request) {
            int writeRequests = outstandingWriteRequests.get();
            if (writeRequests != 0) {
                failTest("There are " + writeRequests + " outstanding"
                        + " write requests while issuing a read request cxid="
                        + request.cxid + " for session 0x"
                        + Long.toHexString(request.sessionId));
            }
        }

        private void validateRequest(Request request) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Got request " + request);
            }

            // Zxids should always be in order for write requests
            if (request.getHdr() != null) {
                long zxid = request.getHdr().getZxid();
                if (!expectedZxid.compareAndSet(zxid, zxid + 1)) {
                    failTest("Write request, expected_zxid="
                             + expectedZxid.get() + "; req_zxid=" + zxid);
                }
            }

            // Each session should see its cxids in order
            AtomicInteger sessionCxid = cxidMap.get(request.sessionId);
            if (sessionCxid == null) {
                sessionCxid = new AtomicInteger(request.cxid + 1);
                AtomicInteger existingSessionCxid =
                    cxidMap.putIfAbsent(request.sessionId, sessionCxid);
                if (existingSessionCxid != null) {
                    failTest("Race condition adding cxid=" + request.cxid
                             + " for session 0x"
                             + Long.toHexString(request.sessionId)
                             + " with other_cxid=" + existingSessionCxid.get());
                }
            } else {
                if (!sessionCxid.compareAndSet(
                      request.cxid, request.cxid + 1)) {
                    failTest("Expected_cxid=" + sessionCxid.get()
                             + "; req_cxid=" + request.cxid);
                }
            }
        }

        @Override
        public void shutdown() {
            LOG.info("shutdown validateReadRequestVariant");
            cxidMap.clear();
            expectedZxid = new AtomicLong(1);
            if (nextProcessor!=null){
                nextProcessor.shutdown();
            }
        }
    }
}