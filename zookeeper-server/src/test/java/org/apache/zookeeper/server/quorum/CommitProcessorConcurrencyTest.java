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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommitProcessorConcurrencyTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(CommitProcessorConcurrencyTest.class);

    BlockingQueue<Request> processedRequests;
    MockCommitProcessor processor;
    int defaultSizeOfThreadPool = 16;

    @BeforeEach
    public void setUp() throws Exception {
        processedRequests = new LinkedBlockingQueue<Request>();
        processor = new MockCommitProcessor();
        CommitProcessor.setMaxReadBatchSize(-1);
        CommitProcessor.setMaxCommitBatchSize(1);
    }

    @AfterEach
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
                return newRequest(new GetDataRequest("/", false), OpCode.getData, readReqId % 50, readReqId);
            } catch (IOException e) {
                e.printStackTrace();
            }
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
                public void processRequest(Request request) throws RequestProcessorException {
                    processedRequests.offer(request);
                }

                public void shutdown() {
                }
            }, "0", false, new ZooKeeperServerListener() {

                @Override
                public void notifyStopping(String threadName, int errorCode) {
                    fail("Commit processor crashed " + errorCode);
                }
            });
        }

        public void initThreads(int poolSize) {
            this.stopped = false;
            this.workerPool = new WorkerService("CommitProcWork", poolSize, true);
        }

    }

    private Request newRequest(Record rec, int type, int sessionId, int xid) throws IOException {
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
    public void committedAndUncommittedOfTheSameSessionRaceTest() throws Exception {
        final String path = "/testCvsUCRace";

        Request readReq = newRequest(new GetDataRequest(path, false), OpCode.getData, 0x0, 0);
        Request writeReq = newRequest(new SetDataRequest(path, new byte[16], -1), OpCode.setData, 0x0, 1);

        processor.committedRequests.add(writeReq);
        processor.queuedRequests.add(readReq);
        processor.queuedRequests.add(writeReq);
        processor.queuedWriteRequests.add(writeReq);
        processor.initThreads(1);

        processor.stoppedMainLoop = true;
        processor.run();

        assertTrue(
            processedRequests.peek() != null && processedRequests.peek().equals(readReq),
            "Request was not processed " + readReq + " instead " + processedRequests.peek());
        processedRequests.poll();
        assertTrue(
            processedRequests.peek() != null && processedRequests.peek().equals(writeReq),
            "Request was not processed " + writeReq + " instead " + processedRequests.peek());
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
    public void processAsMuchUncommittedRequestsAsPossibleTest() throws Exception {
        final String path = "/testAsMuchAsPossible";
        List<Request> shouldBeProcessed = new LinkedList<Request>();
        Set<Request> shouldNotBeProcessed = new HashSet<Request>();
        for (int sessionId = 1; sessionId <= 5; ++sessionId) {
            for (int readReqId = 1; readReqId <= sessionId; ++readReqId) {
                Request readReq = newRequest(new GetDataRequest(path, false), OpCode.getData, sessionId, readReqId);
                shouldBeProcessed.add(readReq);
                processor.queuedRequests.add(readReq);
            }
            Request writeReq = newRequest(
                new CreateRequest(
                    path,
                    new byte[0],
                    Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
                OpCode.create,
                sessionId,
                sessionId + 1);
            Request readReq = newRequest(
                new GetDataRequest(path, false),
                OpCode.getData,
                sessionId,
                sessionId + 2);
            processor.queuedRequests.add(writeReq);
            processor.queuedWriteRequests.add(writeReq);
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
            LOG.error("Did not process {}", r);
        }
        assertTrue(shouldBeProcessed.isEmpty(), "Not all requests were processed");
        assertFalse(shouldNotBeProcessed.removeAll(processedRequests), "Processed a wrong request");
    }

    /**
     * In the following test, we add a write request followed by several read
     * requests of the same session, and we verify several things - 1. The write
     * is not processed until commit arrives. 2. Once the write is processed,
     * all the read requests are processed as well. 3. All read requests are
     * executed after the write, before any other write, along with new reads.
     */
    @Test
    public void processAllFollowingUncommittedAfterFirstCommitTest() throws Exception {
        final String path = "/testUncommittedFollowingCommited";
        Set<Request> shouldBeInPending = new HashSet<Request>();
        Set<Request> shouldBeProcessedAfterPending = new HashSet<Request>();

        Request writeReq = newRequest(
            new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
            OpCode.create,
            0x1,
            1);
        processor.queuedRequests.add(writeReq);
        processor.queuedWriteRequests.add(writeReq);
        shouldBeInPending.add(writeReq);

        for (int readReqId = 2; readReqId <= 5; ++readReqId) {
            Request readReq = newRequest(new GetDataRequest(path, false), OpCode.getData, 0x1, readReqId);
            processor.queuedRequests.add(readReq);
            shouldBeInPending.add(readReq);
            shouldBeProcessedAfterPending.add(readReq);
        }
        processor.initThreads(defaultSizeOfThreadPool);

        processor.stoppedMainLoop = true;
        processor.run();
        assertTrue(processedRequests.isEmpty(), "Processed without waiting for commit");
        assertTrue(processor.queuedRequests.isEmpty(), "Did not handled all of queuedRequests' requests");
        assertTrue(!processor.queuedWriteRequests.isEmpty(), "Removed from blockedQueuedRequests before commit");

        shouldBeInPending.removeAll(processor.pendingRequests.get(writeReq.sessionId));
        for (Request r : shouldBeInPending) {
            LOG.error("Should be in pending {}", r);
        }
        assertTrue(shouldBeInPending.isEmpty(), "Not all requests moved to pending from queuedRequests");

        processor.committedRequests.add(writeReq);
        processor.stoppedMainLoop = true;
        processor.run();
        processor.initThreads(defaultSizeOfThreadPool);

        Thread.sleep(500);
        assertTrue(processedRequests.peek() == writeReq, "Did not process committed request");
        assertTrue(processedRequests.containsAll(shouldBeProcessedAfterPending), "Did not process following read request");
        assertTrue(processor.committedRequests.isEmpty(), "Did not process committed request");
        assertTrue(processor.pendingRequests.isEmpty(), "Did not process committed request");
        assertTrue(processor.queuedWriteRequests.isEmpty(), "Did not remove from blockedQueuedRequests");
    }

    /**
     * In the following test, we add a write request followed by several read
     * requests of the same session. We will do this for 2 sessions. For the
     * second session, we will queue up another write after the reads, and
     * we verify several things - 1. The writes are not processed until
     * the commits arrive. 2. Only 2 writes are processed, with maxCommitBatchSize
     * of 3, due to the blocking reads. 3. Once the writes are processed,
     * all the read requests are processed as well. 4. All read requests are
     * executed after the write, before any other write for that session,
     * along with new reads. 5. Then we add another read for session 1, and
     * another write and commit for session 2. 6. Only the old write, and the read
     * are processed, leaving the commit in the queue. 7. Last write is executed
     * in the last iteration, and all lists are empty.
     */
    @Test
    public void processAllWritesMaxBatchSize() throws Exception {
        final String path = "/processAllWritesMaxBatchSize";
        HashSet<Request> shouldBeProcessedAfterPending = new HashSet<Request>();

        Request writeReq = newRequest(
            new CreateRequest(
                path + "_1",
                new byte[0],
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
            OpCode.create,
            0x1,
            1);
        processor.queuedRequests.add(writeReq);
        processor.queuedWriteRequests.add(writeReq);

        Request writeReq2 = newRequest(
            new CreateRequest(
                path + "_2",
                new byte[0],
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
            OpCode.create,
            0x2,
            1);
        processor.queuedRequests.add(writeReq2);
        processor.queuedWriteRequests.add(writeReq2);

        for (int readReqId = 2; readReqId <= 5; ++readReqId) {
            Request readReq = newRequest(new GetDataRequest(path, false), OpCode.getData, 0x1, readReqId);
            Request readReq2 = newRequest(new GetDataRequest(path, false), OpCode.getData, 0x2, readReqId);
            processor.queuedRequests.add(readReq);
            shouldBeProcessedAfterPending.add(readReq);
            processor.queuedRequests.add(readReq2);
            shouldBeProcessedAfterPending.add(readReq2);
        }

        Request writeReq3 = newRequest(
            new CreateRequest(
                path + "_3",
                new byte[0],
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
            OpCode.create,
            0x2,
            6);
        processor.queuedRequests.add(writeReq3);
        processor.queuedWriteRequests.add(writeReq3);

        processor.initThreads(defaultSizeOfThreadPool);

        processor.stoppedMainLoop = true;
        CommitProcessor.setMaxCommitBatchSize(2);
        processor.run();
        assertTrue(processedRequests.isEmpty(), "Processed without waiting for commit");
        assertTrue(processor.queuedRequests.isEmpty(), "Did not handled all of queuedRequests' requests");
        assertTrue(!processor.queuedWriteRequests.isEmpty(), "Removed from blockedQueuedRequests before commit");
        assertTrue(processor.pendingRequests.containsKey(writeReq.sessionId), "Missing session 1 in pending queue");
        assertTrue(processor.pendingRequests.containsKey(writeReq2.sessionId), "Missing session 2 in pending queue");

        processor.committedRequests.add(writeReq);
        processor.committedRequests.add(writeReq2);
        processor.committedRequests.add(writeReq3);
        processor.stoppedMainLoop = true;
        CommitProcessor.setMaxCommitBatchSize(3);
        processor.run();
        processor.initThreads(defaultSizeOfThreadPool);

        Thread.sleep(500);
        assertTrue(processedRequests.peek() == writeReq, "Did not process committed request");
        assertTrue(processedRequests.containsAll(shouldBeProcessedAfterPending), "Did not process following read request");
        assertTrue(!processor.committedRequests.isEmpty(), "Processed committed request");
        assertTrue(processor.committedRequests.peek() == writeReq3, "Removed commit for write req 3");
        assertTrue(!processor.pendingRequests.isEmpty(), "Processed committed request");
        assertTrue(processor.pendingRequests.containsKey(writeReq3.sessionId), "Missing session 2 in pending queue");
        assertTrue(processor.pendingRequests.get(writeReq3.sessionId).peek() == writeReq3,
            "Missing write 3 in pending queue");
        assertTrue(!processor.queuedWriteRequests.isEmpty(),
            "Removed from blockedQueuedRequests");
        assertTrue(processor.queuedWriteRequests.peek() == writeReq3,
            "Removed write req 3 from blockedQueuedRequests");

        Request readReq3 = newRequest(new GetDataRequest(path, false), OpCode.getData, 0x1, 7);
        processor.queuedRequests.add(readReq3);
        shouldBeProcessedAfterPending.add(readReq3);
        Request writeReq4 = newRequest(
            new CreateRequest(
                path + "_4",
                new byte[0],
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
            OpCode.create,
            0x2,
            7);

        processor.queuedRequests.add(writeReq4);
        processor.queuedWriteRequests.add(writeReq4);
        processor.committedRequests.add(writeReq4);

        processor.stoppedMainLoop = true;
        CommitProcessor.setMaxCommitBatchSize(3);
        processor.run();
        processor.initThreads(defaultSizeOfThreadPool);

        Thread.sleep(500);
        assertTrue(processedRequests.peek() == writeReq, "Did not process committed request");
        assertTrue(processedRequests.containsAll(shouldBeProcessedAfterPending), "Did not process following read request");
        assertTrue(!processor.committedRequests.isEmpty(), "Processed unexpected committed request");
        assertTrue(processor.pendingRequests.isEmpty(), "Unexpected pending request");
        assertTrue(!processor.queuedWriteRequests.isEmpty(), "Removed from blockedQueuedRequests");
        assertTrue(processor.queuedWriteRequests.peek() == writeReq4,
            "Removed write req 4 from blockedQueuedRequests");

        processor.stoppedMainLoop = true;
        CommitProcessor.setMaxCommitBatchSize(3);
        processor.run();
        processor.initThreads(defaultSizeOfThreadPool);

        Thread.sleep(500);
        assertTrue(processedRequests.peek() == writeReq, "Did not process committed request");
        assertTrue(processedRequests.containsAll(shouldBeProcessedAfterPending), "Did not process following read request");
        assertTrue(processor.committedRequests.isEmpty(), "Did not process committed request");
        assertTrue(processor.pendingRequests.isEmpty(), "Did not process committed request");
        assertTrue(processor.queuedWriteRequests.isEmpty(), "Did not remove from blockedQueuedRequests");

    }

    /**
     * In the following test, we verify that committed requests are processed
     * even when queuedRequests never gets empty. We add 10 committed request
     * and use infinite queuedRequests. We verify that the committed request was
     * processed.
     */
    @Test
    @Timeout(value = 1)
    public void noStarvationOfNonLocalCommittedRequestsTest() throws Exception {
        final String path = "/noStarvationOfCommittedRequests";
        processor.queuedRequests = new MockRequestsQueue();
        Set<Request> nonLocalCommits = new HashSet<Request>();
        for (int i = 0; i < 10; i++) {
            Request nonLocalCommitReq = newRequest(
                new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
                OpCode.create, 51, i + 1);
            processor.committedRequests.add(nonLocalCommitReq);
            nonLocalCommits.add(nonLocalCommitReq);
        }
        for (int i = 0; i < 10; i++) {
            processor.initThreads(defaultSizeOfThreadPool);
            processor.stoppedMainLoop = true;
            processor.run();
        }
        assertTrue(processedRequests.containsAll(nonLocalCommits), "commit request was not processed");
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
            new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
            OpCode.create,
            0x3,
            1);
        processor.queuedRequests.add(firstCommittedReq);
        processor.queuedWriteRequests.add(firstCommittedReq);
        processor.committedRequests.add(firstCommittedReq);
        Set<Request> allReads = new HashSet<Request>();

        // +1 read request to queuedRequests
        Request firstRead = newRequest(new GetDataRequest(path, false), OpCode.getData, 0x1, 0);
        allReads.add(firstRead);
        processor.queuedRequests.add(firstRead);

        // +1 non local commit
        Request secondCommittedReq = newRequest(
            new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
            OpCode.create,
            0x99,
            2);
        processor.committedRequests.add(secondCommittedReq);

        Set<Request> waitingCommittedRequests = new HashSet<Request>();
        // +99 non local committed requests
        for (int writeReqId = 3; writeReqId < 102; ++writeReqId) {
            Request writeReq = newRequest(
                new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
                OpCode.create,
                0x8,
                writeReqId);
            processor.committedRequests.add(writeReq);
            waitingCommittedRequests.add(writeReq);
        }

        // +50 read requests to queuedRequests
        for (int readReqId = 1; readReqId <= 50; ++readReqId) {
            Request readReq = newRequest(
                new GetDataRequest(path, false),
                OpCode.getData,
                0x5,
                readReqId);
            allReads.add(readReq);
            processor.queuedRequests.add(readReq);
        }

        processor.initThreads(defaultSizeOfThreadPool);

        processor.stoppedMainLoop = true;
        processor.run();
        assertTrue(processedRequests.contains(firstCommittedReq), "Did not process the first write request");
        for (Request r : allReads) {
            assertTrue(!processedRequests.contains(r), "Processed read request");
        }
        processor.run();
        assertTrue(processedRequests.containsAll(allReads), "did not processed all reads");
        assertTrue(processedRequests.contains(secondCommittedReq), "Did not process the second write request");
        for (Request r : waitingCommittedRequests) {
            assertTrue(!processedRequests.contains(r), "Processed additional committed request");
        }
    }

    /**
     * In the following test, we verify that we can handle the case that we got a commit
     * of a request we never seen since the session that we just established. This can happen
     * when a session is just established and there is request waiting to be committed in the
     * session queue but it sees a commit for a request that belongs to the previous connection.
     */
    @Test
    @Timeout(value = 5)
    public void noCrashOnCommittedRequestsOfUnseenRequestTest() throws Exception {
        final String path = "/noCrash/OnCommittedRequests/OfUnseenRequestTest";
        final int numberofReads = 10;
        final int sessionid = 0x123456;
        final int firstCXid = 0x100;
        int readReqId = firstCXid;
        processor.stoppedMainLoop = true;
        HashSet<Request> localRequests = new HashSet<Request>();
        // queue the blocking write request to queuedRequests
        Request firstCommittedReq = newRequest(
            new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
            OpCode.create, sessionid, readReqId++);
        processor.queuedRequests.add(firstCommittedReq);
        processor.queuedWriteRequests.add(firstCommittedReq);
        localRequests.add(firstCommittedReq);

        // queue read requests to queuedRequests
        for (; readReqId <= numberofReads + firstCXid; ++readReqId) {
            Request readReq = newRequest(new GetDataRequest(path, false), OpCode.getData, sessionid, readReqId);
            processor.queuedRequests.add(readReq);
            localRequests.add(readReq);
        }

        //run once
        assertTrue(processor.queuedRequests.containsAll(localRequests));
        processor.initThreads(defaultSizeOfThreadPool);
        processor.run();
        Thread.sleep(1000);

        //We verify that the processor is waiting for the commit
        assertTrue(processedRequests.isEmpty());

        // We add a commit that belongs to the same session but with smaller cxid,
        // i.e., commit of an update from previous connection of this session.
        Request preSessionCommittedReq = newRequest(
            new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
            OpCode.create, sessionid, firstCXid - 2);
        processor.committedRequests.add(preSessionCommittedReq);
        processor.committedRequests.add(firstCommittedReq);
        processor.run();
        Thread.sleep(1000);

        //We verify that the commit processor processed the old commit prior to the newer messages
        assertTrue(processedRequests.peek() == preSessionCommittedReq);

        processor.run();
        Thread.sleep(1000);

        //We verify that the commit processor handle all messages.
        assertTrue(processedRequests.containsAll(localRequests));
    }

    /**
     * In the following test, we verify if we handle the case in which we get a commit
     * for a request that has higher Cxid than the one we are waiting. This can happen
     * when a session connection is lost but there is a request waiting to be committed in the
     * session queue. However, since the session has moved, new requests can get to
     * the leader out of order. Hence, the commits can also arrive "out of order" w.r.t. cxid.
     * We should commit the requests according to the order we receive from the leader, i.e., wait for the relevant commit.
     */
    @Test
    @Timeout(value = 5)
    public void noCrashOnOutofOrderCommittedRequestTest() throws Exception {
        final String path = "/noCrash/OnCommittedRequests/OfUnSeenRequestTest";
        final int sessionid = 0x123456;
        final int lastCXid = 0x100;
        final int numberofReads = 10;
        int readReqId = lastCXid;
        processor.stoppedMainLoop = true;
        HashSet<Request> localRequests = new HashSet<Request>();

        // queue the blocking write request to queuedRequests
        Request orphanCommittedReq = newRequest(
            new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
            OpCode.create, sessionid, lastCXid);
        processor.queuedRequests.add(orphanCommittedReq);
        processor.queuedWriteRequests.add(orphanCommittedReq);
        localRequests.add(orphanCommittedReq);

        // queue read requests to queuedRequests
        for (; readReqId <= numberofReads + lastCXid; ++readReqId) {
            Request readReq = newRequest(new GetDataRequest(path, false), OpCode.getData, sessionid, readReqId);
            processor.queuedRequests.add(readReq);
            localRequests.add(readReq);
        }

        //run once
        processor.initThreads(defaultSizeOfThreadPool);
        processor.run();
        Thread.sleep(1000);

        //We verify that the processor is waiting for the commit
        assertTrue(processedRequests.isEmpty());

        // We add a commit that belongs to the same session but with larger cxid,
        // i.e., commit of an update from the next connection of this session.
        Request otherSessionCommittedReq = newRequest(
            new CreateRequest(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL.toFlag()),
            OpCode.create, sessionid, lastCXid + 10);
        processor.committedRequests.add(otherSessionCommittedReq);
        processor.committedRequests.add(orphanCommittedReq);
        processor.run();
        Thread.sleep(1000);

        //We verify that the commit processor processed the old commit prior to the newer messages
        assertTrue(processedRequests.size() == 1);
        assertTrue(processedRequests.contains(otherSessionCommittedReq));

        processor.run();
        Thread.sleep(1000);

        //We verify that the commit processor handle all messages.
        assertTrue(processedRequests.containsAll(localRequests));
    }

}
