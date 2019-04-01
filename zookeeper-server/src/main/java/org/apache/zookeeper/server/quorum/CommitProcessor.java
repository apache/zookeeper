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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.WorkerService;
import org.apache.zookeeper.server.ZooKeeperCriticalThread;
import org.apache.zookeeper.server.ZooKeeperServerListener;

/**
 * This RequestProcessor matches the incoming committed requests with the
 * locally submitted requests. The trick is that locally submitted requests that
 * change the state of the system will come back as incoming committed requests,
 * so we need to match them up. Instead of just waiting for the committed requests, 
 * we process the uncommitted requests that belong to other sessions.
 *
 * The CommitProcessor is multi-threaded. Communication between threads is
 * handled via queues, atomics, and wait/notifyAll synchronized on the
 * processor. The CommitProcessor acts as a gateway for allowing requests to
 * continue with the remainder of the processing pipeline. It will allow many
 * read requests but only a single write request to be in flight simultaneously,
 * thus ensuring that write requests are processed in transaction id order.
 *
 *   - 1   commit processor main thread, which watches the request queues and
 *         assigns requests to worker threads based on their sessionId so that
 *         read and write requests for a particular session are always assigned
 *         to the same thread (and hence are guaranteed to run in order).
 *   - 0-N worker threads, which run the rest of the request processor pipeline
 *         on the requests. If configured with 0 worker threads, the primary
 *         commit processor thread runs the pipeline directly.
 *
 * Typical (default) thread counts are: on a 32 core machine, 1 commit
 * processor thread and 32 worker threads.
 *
 * Multi-threading constraints:
 *   - Each session's requests must be processed in order.
 *   - Write requests must be processed in zxid order
 *   - Must ensure no race condition between writes in one session that would
 *     trigger a watch being set by a read request in another session
 *
 * The current implementation solves the third constraint by simply allowing no
 * read requests to be processed in parallel with write requests.
 */
public class CommitProcessor extends ZooKeeperCriticalThread implements
        RequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(CommitProcessor.class);

    /** Default: numCores */
    public static final String ZOOKEEPER_COMMIT_PROC_NUM_WORKER_THREADS =
        "zookeeper.commitProcessor.numWorkerThreads";
    /** Default worker pool shutdown timeout in ms: 5000 (5s) */
    public static final String ZOOKEEPER_COMMIT_PROC_SHUTDOWN_TIMEOUT =
        "zookeeper.commitProcessor.shutdownTimeout";

    /**
     * Incoming requests.
     */
    protected LinkedBlockingQueue<Request> queuedRequests =
        new LinkedBlockingQueue<Request>();

    /**
     * Requests that have been committed.
     */
    protected final LinkedBlockingQueue<Request> committedRequests =
        new LinkedBlockingQueue<Request>();

    /**
     * Requests that we are holding until commit comes in. Keys represent
     * session ids, each value is a linked list of the session's requests.
     */
    protected final Map<Long, LinkedList<Request>> pendingRequests =
            new HashMap<Long, LinkedList<Request>>(10000);

    /** The number of requests currently being processed */
    protected final AtomicInteger numRequestsProcessing = new AtomicInteger(0);

    RequestProcessor nextProcessor;

    /** For testing purposes, we use a separated stopping condition for the
     * outer loop.*/
    protected volatile boolean stoppedMainLoop = true; 
    protected volatile boolean stopped = true;
    private long workerShutdownTimeoutMS;
    protected WorkerService workerPool;
    private Object emptyPoolSync = new Object();

    /**
     * This flag indicates whether we need to wait for a response to come back from the
     * leader or we just let the sync operation flow through like a read. The flag will
     * be false if the CommitProcessor is in a Leader pipeline.
     */
    boolean matchSyncs;

    public CommitProcessor(RequestProcessor nextProcessor, String id,
                           boolean matchSyncs, ZooKeeperServerListener listener) {
        super("CommitProcessor:" + id, listener);
        this.nextProcessor = nextProcessor;
        this.matchSyncs = matchSyncs;
    }

    private boolean isProcessingRequest() {
        return numRequestsProcessing.get() != 0;
    }

    protected boolean needCommit(Request request) {
        switch (request.type) {
            case OpCode.create:
            case OpCode.create2:
            case OpCode.createTTL:
            case OpCode.createContainer:
            case OpCode.delete:
            case OpCode.deleteContainer:
            case OpCode.setData:
            case OpCode.reconfig:
            case OpCode.multi:
            case OpCode.setACL:
                return true;
            case OpCode.sync:
                return matchSyncs;    
            case OpCode.createSession:
            case OpCode.closeSession:
                return !request.isLocalSession();
            default:
                return false;
        }
    }

    @Override
    public void run() {
        try {
            /*
             * In each iteration of the following loop we process at most
             * requestsToProcess requests of queuedRequests. We have to limit
             * the number of request we poll from queuedRequests, since it is
             * possible to endlessly poll read requests from queuedRequests, and
             * that will lead to a starvation of non-local committed requests.
             */
            int requestsToProcess = 0;
            boolean commitIsWaiting = false;
			do {
                /*
                 * Since requests are placed in the queue before being sent to
                 * the leader, if commitIsWaiting = true, the commit belongs to
                 * the first update operation in the queuedRequests or to a
                 * request from a client on another server (i.e., the order of
                 * the following two lines is important!).
                 */
                commitIsWaiting = !committedRequests.isEmpty();
                requestsToProcess =  queuedRequests.size();
                // Avoid sync if we have something to do
                if (requestsToProcess == 0 && !commitIsWaiting){
                    // Waiting for requests to process
                    synchronized (this) {
                        while (!stopped && requestsToProcess == 0
                                && !commitIsWaiting) {
                            wait();
                            commitIsWaiting = !committedRequests.isEmpty();
                            requestsToProcess = queuedRequests.size();
                        }
                    }
                }
                /*
                 * Processing up to requestsToProcess requests from the incoming
                 * queue (queuedRequests), possibly less if a committed request
                 * is present along with a pending local write. After the loop,
                 * we process one committed request if commitIsWaiting.
                 */
                Request request = null;
                while (!stopped && requestsToProcess > 0
                        && (request = queuedRequests.poll()) != null) {
                    requestsToProcess--;
                    if (needCommit(request)
                            || pendingRequests.containsKey(request.sessionId)) {
                        // Add request to pending
                        LinkedList<Request> requests = pendingRequests
                                .get(request.sessionId);
                        if (requests == null) {
                            requests = new LinkedList<Request>();
                            pendingRequests.put(request.sessionId, requests);
                        }
                        requests.addLast(request);
                    }
                    else {
                        sendToNextProcessor(request);
                    }
                    /*
                     * Stop feeding the pool if there is a local pending update
                     * and a committed request that is ready. Once we have a
                     * pending request with a waiting committed request, we know
                     * we can process the committed one. This is because commits
                     * for local requests arrive in the order they appeared in
                     * the queue, so if we have a pending request and a
                     * committed request, the committed request must be for that
                     * pending write or for a write originating at a different
                     * server.
                     */
                    if (!pendingRequests.isEmpty() && !committedRequests.isEmpty()){
                        /*
                         * We set commitIsWaiting so that we won't check
                         * committedRequests again.
                         */
                        commitIsWaiting = true;
                        break;
                    }
                }

                // Handle a single committed request
                if (commitIsWaiting && !stopped){
                    waitForEmptyPool();

                    if (stopped){
                        return;
                    }

                    // Process committed head
                    if ((request = committedRequests.poll()) == null) {
                        throw new IOException("Error: committed head is null");
                    }

                    /*
                     * Check if request is pending, if so, update it with the committed info
                     */
                    LinkedList<Request> sessionQueue = pendingRequests
                            .get(request.sessionId);
                    if (sessionQueue != null) {
                        // If session queue != null, then it is also not empty.
                        Request topPending = sessionQueue.poll();
                        if (request.cxid != topPending.cxid) {
                            /*
                             * TL;DR - we should not encounter this scenario often under normal load.
                             * We pass the commit to the next processor and put the pending back with a warning.
                             *
                             * Generally, we can get commit requests that are not at the queue head after
                             * a session moved (see ZOOKEEPER-2684). Let's denote the previous server of the session
                             * with A, and the server that the session moved to with B (keep in mind that it is
                             * possible that the session already moved from B to a new server C, and maybe C=A).
                             * 1. If request.cxid < topPending.cxid : this means that the session requested this update
                             * from A, then moved to B (i.e., which is us), and now B receives the commit
                             * for the update after the session already performed several operations in B
                             * (and therefore its cxid is higher than that old request).
                             * 2. If request.cxid > topPending.cxid : this means that the session requested an updated
                             * from B with cxid that is bigger than the one we know therefore in this case we
                             * are A, and we lost the connection to the session. Given that we are waiting for a commit
                             * for that update, it means that we already sent the request to the leader and it will
                             * be committed at some point (in this case the order of cxid won't follow zxid, since zxid
                             * is an increasing order). It is not safe for us to delete the session's queue at this
                             * point, since it is possible that the session has newer requests in it after it moved
                             * back to us. We just leave the queue as it is, and once the commit arrives (for the old
                             * request), the finalRequestProcessor will see a closed cnxn handle, and just won't send a
                             * response.
                             * Also note that we don't have a local session, therefore we treat the request
                             * like any other commit for a remote request, i.e., we perform the update without sending
                             * a response.
                             */
                            LOG.warn("Got request " + request +
                                    " but we are expecting request " + topPending);
                            sessionQueue.addFirst(topPending);
                        } else {
                            /*
                             * Generally, we want to send to the next processor our version of the request,
                             * since it contains the session information that is needed for post update processing.
                             * In more details, when a request is in the local queue, there is (or could be) a client
                             * attached to this server waiting for a response, and there is other bookkeeping of
                             * requests that are outstanding and have originated from this server
                             * (e.g., for setting the max outstanding requests) - we need to update this info when an
                             * outstanding request completes. Note that in the other case (above), the operation
                             * originated from a different server and there is no local bookkeeping or a local client
                             * session that needs to be notified.
                             */
                            topPending.setHdr(request.getHdr());
                            topPending.setTxn(request.getTxn());
                            topPending.zxid = request.zxid;
                            request = topPending;
                        }
                    }

                    sendToNextProcessor(request);

                    waitForEmptyPool();

                    /*
                     * Process following reads if any, remove session queue if
                     * empty.
                     */
                    if (sessionQueue != null) {
                        while (!stopped && !sessionQueue.isEmpty()
                                && !needCommit(sessionQueue.peek())) {
                            sendToNextProcessor(sessionQueue.poll());
                        }
                        // Remove empty queues
                        if (sessionQueue.isEmpty()) {
                            pendingRequests.remove(request.sessionId);
                        }
                    }
                }
            } while (!stoppedMainLoop);
        } catch (Throwable e) {
            handleException(this.getName(), e);
        }
        LOG.info("CommitProcessor exited loop!");
    }

    private void waitForEmptyPool() throws InterruptedException {
        synchronized(emptyPoolSync) {
            while ((!stopped) && isProcessingRequest()) {
                emptyPoolSync.wait();
            }
        }
    }

    @Override
    public void start() {
        int numCores = Runtime.getRuntime().availableProcessors();
        int numWorkerThreads = Integer.getInteger(
            ZOOKEEPER_COMMIT_PROC_NUM_WORKER_THREADS, numCores);
        workerShutdownTimeoutMS = Long.getLong(
            ZOOKEEPER_COMMIT_PROC_SHUTDOWN_TIMEOUT, 5000);

        LOG.info("Configuring CommitProcessor with "
                 + (numWorkerThreads > 0 ? numWorkerThreads : "no")
                 + " worker threads.");
        if (workerPool == null) {
            workerPool = new WorkerService(
                "CommitProcWork", numWorkerThreads, true);
        }
        stopped = false;
        stoppedMainLoop = false;
        super.start();
    }

    /**
     * Schedule final request processing; if a worker thread pool is not being
     * used, processing is done directly by this thread.
     */
    private void sendToNextProcessor(Request request) {
        numRequestsProcessing.incrementAndGet();
        workerPool.schedule(new CommitWorkRequest(request), request.sessionId);
    }

    /**
     * CommitWorkRequest is a small wrapper class to allow
     * downstream processing to be run using the WorkerService
     */
    private class CommitWorkRequest extends WorkerService.WorkRequest {
        private final Request request;

        CommitWorkRequest(Request request) {
            this.request = request;
        }

        @Override
        public void cleanup() {
            if (!stopped) {
                LOG.error("Exception thrown by downstream processor,"
                          + " unable to continue.");
                CommitProcessor.this.halt();
            }
        }

        public void doWork() throws RequestProcessorException {
            try {
                nextProcessor.processRequest(request);
            } finally {
                if (numRequestsProcessing.decrementAndGet() == 0){
                    wakeupOnEmpty();
                }
            }
        }
    }

    @SuppressFBWarnings("NN_NAKED_NOTIFY")
    synchronized private void wakeup() {
        notifyAll();
    }

    private void wakeupOnEmpty() {
        synchronized(emptyPoolSync){
            emptyPoolSync.notifyAll();
        }
    }
    
    public void commit(Request request) {
        if (stopped || request == null) {
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Committing request:: " + request);
        }
        committedRequests.add(request);
        wakeup();
    }

    @Override
    public void processRequest(Request request) {
        if (stopped) {
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing request:: " + request);
        }
        queuedRequests.add(request);
        wakeup();
    }

    private void halt() {
        stoppedMainLoop = true;
        stopped = true;
        wakeupOnEmpty();
        wakeup();
        queuedRequests.clear();
        if (workerPool != null) {
            workerPool.stop();
        }
    }

    public void shutdown() {
        LOG.info("Shutting down");

        halt();

        if (workerPool != null) {
            workerPool.join(workerShutdownTimeoutMS);
        }

        if (nextProcessor != null) {
            nextProcessor.shutdown();
        }
    }

}
