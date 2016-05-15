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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.GetDataRequest;
import org.apache.zookeeper.proto.SetDataRequest;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.WorkerService;
import org.apache.zookeeper.server.ZooKeeperServerListener;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommitProcessorConcurrencyTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory
            .getLogger(CommitProcessorConcurrencyTest.class);

    BlockingQueue<Request> processedRequests;
    MockCommitProcessor processor;
    int defaultSizeOfThreadPool = 16;

    @Before
    public void setUp() throws Exception {
        processedRequests = new LinkedBlockingQueue<Request>();
        processor = new MockCommitProcessor();
    }

    @After
    public void tearDown() throws Exception {
        processor.shutdown();
    }

    // This queue is infinite if we use "poll" to get requests, but returns a
    // finite size when asked.
    class MockRequestsQueue extends LinkedBlockingQueue<Request> {
        private static final long serialVersionUID = 1L;
        int readReqId = 0;

        // Always have a request to return.
        public Request poll() {
            readReqId++;
            try {
                return newRequest(new GetDataRequest("/", false),
                        OpCode.getData, readReqId % 50, readReqId);
            } catch (IOException e) {
                e.printStackTrace();
            }
            ;
            return null;
        }

        // Fixed queue size.
        public int size() {
            return 42;
        }
    }

    class MockCommitProcessor extends CommitProcessor {
        MockCommitProcessor() {
            super(new RequestProcessor() {
                public void processRequest(Request request)
                        throws RequestProcessorException {
                    processedRequests.offer(request);
                }

                public void shutdown() {
                }
            }, "0", false, new ZooKeeperServerListener() {

                @Override
                public void notifyStopping(String threadName, int errorCode) {
                }
            });
        }

        public void initThreads(int poolSize) {
            this.stopped = false;
            this.workerPool = new WorkerService("CommitProcWork", poolSize,
                    true);
        }
    }

    private Request newRequest(Record rec, int type, int sessionId, int xid)
            throws IOException {
        ByteArrayOutputStream boas = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(boas);
        rec.serialize(boa, "request");
        ByteBuffer bb = ByteBuffer.wrap(boas.toByteArray());
        return new Request(null, sessionId, xid, type, bb, new ArrayList<Id>());
    }

    /**
     * We place a read request followed by committed update request of the same
     * session in queuedRequests. We verify that both requests are processed,
     * according to the order of the session (first read, then the write).
     */
    @Test
    public void committedAndUncommittedOfTheSameSessionRaceTest()
            throws Exception {
        final String path = "/testCvsUCRace";

        Request readReq = newRequest(new GetDataRequest(path, false),
                OpCode.getData, 0x0, 0);
        Request writeReq = newRequest(
                new SetDataRequest(path, new byte[16], -1), OpCode.setData, 0x0,
                1);

        processor.committedRequests.add(writeReq);
        processor.queuedRequests.add(readReq);
        processor.queuedRequests.add(writeReq);
        processor.initThreads(1);

        processor.stoppedMainLoop = true;
        processor.run();

        Assert.assertTrue(
                "Request was not processed " + readReq + " instead "
                        + processedRequests.peek(),
                processedRequests.peek() != null
                        && processedRequests.peek().equals(readReq));
        processedRequests.poll();
        Assert.assertTrue(
                "Request was not processed " + writeReq + " instead "
                        + processedRequests.peek(),
                processedRequests.peek() != null
                        && processedRequests.peek().equals(writeReq));
    }

    /**
     * Here we create the following requests queue structure: R1_1, W1_2, R1_3,
     * R2_1, R2_2, W2_3, R2_4, R3_1, R3_2, R3_3, W3_4, R3_5, ... , W5_6, R5_7
     * i.e., 5 sessions, each has different amount or read requests, followed by
     * single write and afterwards single read. The idea is to check that all of
     * the reads that can be processed concurrently do so, and that none of the
     * uncommited requests, followed by the reads are processed.
     */
    @Test
    public void processAsMuchUncommittedRequestsAsPossibleTest()
            throws Exception {
        final String path = "/testAsMuchAsPossible";
        LinkedList<Request> shouldBeProcessed = new LinkedList<Request>();
        HashSet<Request> shouldNotBeProcessed = new HashSet<Request>();
        for (int sessionId = 1; sessionId <= 5; ++sessionId) {
            for (int readReqId = 1; readReqId <= sessionId; ++readReqId) {
                Request readReq = newRequest(new GetDataRequest(path, false),
                        OpCode.getData, sessionId, readReqId);
                shouldBeProcessed.add(readReq);
                processor.queuedRequests.add(readReq);
            }
            Request writeReq = newRequest(
                    new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
                    OpCode.create, sessionId, sessionId + 1);
            Request readReq = newRequest(new GetDataRequest(path, false),
                    OpCode.getData, sessionId, sessionId + 2);
            processor.queuedRequests.add(writeReq);
            processor.queuedRequests.add(readReq);
            shouldNotBeProcessed.add(writeReq);
            shouldNotBeProcessed.add(readReq);
        }
        processor.initThreads(defaultSizeOfThreadPool);

        processor.stoppedMainLoop = true;
        processor.run();
        Thread.sleep(1000);
        shouldBeProcessed.removeAll(processedRequests);
        for (Request r : shouldBeProcessed) {
            LOG.error("Did not process " + r);
        }
        Assert.assertTrue("Not all requests were processed",
                shouldBeProcessed.isEmpty());
        Assert.assertFalse("Processed a wrong request",
                shouldNotBeProcessed.removeAll(processedRequests));
    }

    /**
     * In the following test, we add a write request followed by several read
     * requests of the same session, and we verify several things - 1. The write
     * is not processed until commit arrives. 2. Once the write is processed,
     * all the read requests are processed as well. 3. All read requests are
     * executed after the write, before any other write, along with new reads.
     */
    @Test
    public void processAllFollowingUncommittedAfterFirstCommitTest()
            throws Exception {
        final String path = "/testUncommittedFollowingCommited";
        HashSet<Request> shouldBeInPending = new HashSet<Request>();
        HashSet<Request> shouldBeProcessedAfterPending = new HashSet<Request>();

        Request writeReq = newRequest(
                new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
                OpCode.create, 0x1, 1);
        processor.queuedRequests.add(writeReq);
        shouldBeInPending.add(writeReq);

        for (int readReqId = 2; readReqId <= 5; ++readReqId) {
            Request readReq = newRequest(new GetDataRequest(path, false),
                    OpCode.getData, 0x1, readReqId);
            processor.queuedRequests.add(readReq);
            shouldBeInPending.add(readReq);
            shouldBeProcessedAfterPending.add(readReq);
        }
        processor.initThreads(defaultSizeOfThreadPool);

        processor.stoppedMainLoop = true;
        processor.run();
        Assert.assertTrue("Processed without waiting for commit",
                processedRequests.isEmpty());
        Assert.assertTrue("Did not handled all of queuedRequests' requests",
                processor.queuedRequests.isEmpty());

        shouldBeInPending
                .removeAll(processor.pendingRequests.get(writeReq.sessionId));
        for (Request r : shouldBeInPending) {
            LOG.error("Should be in pending " + r);
        }
        Assert.assertTrue(
                "Not all requests moved to pending from queuedRequests",
                shouldBeInPending.isEmpty());

        processor.committedRequests.add(writeReq);
        processor.stoppedMainLoop = true;
        processor.run();
        processor.initThreads(defaultSizeOfThreadPool);

        Thread.sleep(500);
        Assert.assertTrue("Did not process committed request",
                processedRequests.peek() == writeReq);
        Assert.assertTrue("Did not process following read request",
                processedRequests.containsAll(shouldBeProcessedAfterPending));
        Assert.assertTrue("Did not process committed request",
                processor.committedRequests.isEmpty());
        Assert.assertTrue("Did not process committed request",
                processor.pendingRequests.isEmpty());
    }

    /**
     * In the following test, we verify that committed requests are processed
     * even when queuedRequests never gets empty. We add 10 committed request
     * and use infinite queuedRequests. We verify that the committed request was
     * processed.
     */
    @Test(timeout = 1000)
    public void noStarvationOfNonLocalCommittedRequestsTest() throws Exception {
        final String path = "/noStarvationOfCommittedRequests";
        processor.queuedRequests = new MockRequestsQueue();
        HashSet<Request> nonLocalCommits = new HashSet<Request>();
        for (int i = 0; i < 10; i++) {
            Request nonLocalCommitReq = newRequest(
                    new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
                    OpCode.create, 51, i + 1);
            processor.committedRequests.add(nonLocalCommitReq);
            nonLocalCommits.add(nonLocalCommitReq);
        }
        for (int i = 0; i < 10; i++) {
            processor.initThreads(defaultSizeOfThreadPool);
            processor.stoppedMainLoop = true;
            processor.run();
        }
        Assert.assertTrue("commit request was not processed",
                processedRequests.containsAll(nonLocalCommits));
    }

    /**
     * In the following test, we verify that committed writes are not causing
     * reads starvation. We populate the commit processor with the following
     * order of requests: 1 committed local updated, 1 read request, 100
     * committed non-local updates. 50 read requests. We verify that after the
     * first call to processor.run, only the first write is processed, then
     * after the second call, all reads are processed along with the second
     * write.
     */
    @Test
    public void noStarvationOfReadRequestsTest() throws Exception {
        final String path = "/noStarvationOfReadRequests";

        // +1 committed requests (also head of queuedRequests)
        Request firstCommittedReq = newRequest(
                new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
                OpCode.create, 0x3, 1);
        processor.queuedRequests.add(firstCommittedReq);
        processor.committedRequests.add(firstCommittedReq);
        HashSet<Request> allReads = new HashSet<Request>();

        // +1 read request to queuedRequests
        Request firstRead = newRequest(new GetDataRequest(path, false),
                OpCode.getData, 0x1, 0);
        allReads.add(firstRead);
        processor.queuedRequests.add(firstRead);

        // +1 non local commit
        Request secondCommittedReq = newRequest(
                new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
                OpCode.create, 0x99, 2);
        processor.committedRequests.add(secondCommittedReq);

        HashSet<Request> waitingCommittedRequests = new HashSet<Request>();
        // +99 non local committed requests
        for (int writeReqId = 3; writeReqId < 102; ++writeReqId) {
            Request writeReq = newRequest(
                    new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
                    OpCode.create, 0x8, writeReqId);
            processor.committedRequests.add(writeReq);
            waitingCommittedRequests.add(writeReq);
        }

        // +50 read requests to queuedRequests
        for (int readReqId = 1; readReqId <= 50; ++readReqId) {
            Request readReq = newRequest(new GetDataRequest(path, false),
                    OpCode.getData, 0x5, readReqId);
            allReads.add(readReq);
            processor.queuedRequests.add(readReq);
        }

        processor.initThreads(defaultSizeOfThreadPool);

        processor.stoppedMainLoop = true;
        processor.run();
        Assert.assertTrue("Did not process the first write request",
                processedRequests.contains(firstCommittedReq));
        for (Request r : allReads) {
            Assert.assertTrue("Processed read request",
                    !processedRequests.contains(r));
        }
        processor.run();
        Assert.assertTrue("did not processed all reads",
                processedRequests.containsAll(allReads));
        Assert.assertTrue("Did not process the second write request",
                processedRequests.contains(secondCommittedReq));
        for (Request r : waitingCommittedRequests) {
            Assert.assertTrue("Processed additional committed request",
                    !processedRequests.contains(r));
        }
    }
}