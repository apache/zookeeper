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

import java.io.Flushable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.server.quorum.LeaderZooKeeperServer;
import org.apache.zookeeper.server.quorum.WitnessHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This RequestProcessor logs requests to disk. It batches the requests to do
 * the io efficiently. The request is not passed to the next RequestProcessor
 * until its log has been synced to disk.
 *
 * SyncRequestProcessor is used in 3 different cases
 * 1. Leader - Sync request to disk and forward it to AckRequestProcessor which
 *             send ack back to itself.
 * 2. Follower - Sync request to disk and forward request to
 *             SendAckRequestProcessor which send the packets to leader.
 *             SendAckRequestProcessor is flushable which allow us to force
 *             push packets to leader.
 * 3. Observer - Sync committed request to disk (received as INFORM packet).
 *             It never send ack back to the leader, so the nextProcessor will
 *             be null. This change the semantic of txnlog on the observer
 *             since it only contains committed txns.
 */
public class SyncRequestProcessor extends ZooKeeperCriticalThread implements RequestProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(SyncRequestProcessor.class);

    private static final Request REQUEST_OF_DEATH = Request.requestOfDeath;

    /** The number of log entries to log before starting a snapshot */
    private static int snapCount = ZooKeeperServer.getSnapCount();

    /**
     * The total size of log entries before starting a snapshot
     */
    private static long snapSizeInBytes = ZooKeeperServer.getSnapSizeInBytes();

    /**
     * Random numbers used to vary snapshot timing
     */
    private int randRoll;
    private long randSize;

    private final BlockingQueue<Request> queuedRequests = new LinkedBlockingQueue<Request>();

    private final Semaphore snapThreadMutex = new Semaphore(1);

    private final ZooKeeperServer zks;

    private final RequestProcessor nextProcessor;

    /**
     * Transactions that have been written and are waiting to be flushed to
     * disk. Basically this is the list of SyncItems whose callbacks will be
     * invoked after flush returns successfully.
     * Changed declaration of toFlush from Queue to Deque, since we are initializing it as an ArrayDequeue. And we need the ability to get
     * the first and last element of the queue for send the request to the witness.
     */
    private final Deque<Request> toFlush;
    private long lastFlushTime;

    public SyncRequestProcessor(ZooKeeperServer zks, RequestProcessor nextProcessor) {
        super("SyncThread:" + zks.getServerId(), zks.getZooKeeperServerListener());
        this.zks = zks;
        this.nextProcessor = nextProcessor;
        this.toFlush = new ArrayDeque<>(zks.getMaxBatchSize());
    }

    /**
     * used by tests to check for changing
     * snapcounts
     * @param count
     */
    public static void setSnapCount(int count) {
        snapCount = count;
    }

    /**
     * used by tests to get the snapcount
     * @return the snapcount
     */
    public static int getSnapCount() {
        return snapCount;
    }

    private long getRemainingDelay() {
        long flushDelay = zks.getFlushDelay();
        long duration = Time.currentElapsedTime() - lastFlushTime;
        if (duration < flushDelay) {
            return flushDelay - duration;
        }
        return 0;
    }

    /** If both flushDelay and maxMaxBatchSize are set (bigger than 0), flush
     * whenever either condition is hit. If only one or the other is
     * set, flush only when the relevant condition is hit.
     */
    private boolean shouldFlush() {
        long flushDelay = zks.getFlushDelay();
        long maxBatchSize = zks.getMaxBatchSize();
        if ((flushDelay > 0) && (getRemainingDelay() == 0)) {
            return true;
        }
        return (maxBatchSize > 0) && (toFlush.size() >= maxBatchSize);
    }

    /**
     * used by tests to check for changing
     * snapcounts
     * @param size
     */
    public static void setSnapSizeInBytes(long size) {
        snapSizeInBytes = size;
    }

    private boolean shouldSnapshot() {
        int logCount = zks.getZKDatabase().getTxnCount();
        long logSize = zks.getZKDatabase().getTxnSize();
        return (logCount > (snapCount / 2 + randRoll))
               || (snapSizeInBytes > 0 && logSize > (snapSizeInBytes / 2 + randSize));
    }

    private void resetSnapshotStats() {
        randRoll = ThreadLocalRandom.current().nextInt(snapCount / 2);
        randSize = Math.abs(ThreadLocalRandom.current().nextLong() % (snapSizeInBytes / 2));
    }

    /***
     * Priyatham Notes: When proccessRequest() is invoked on this SyncRequestProcessor, the request added to the queuedRequests queue.
     * In this run method loop, queuedRequests is polled and the Requests are appended to the Log. But, the log is not written to the disk immediately.
     * Multiple requests are batched together either based on elapsed time or batchSize and committed at once. Committed means writing the log to the disk.
     * This happens in the flush() method call. Once committed, all the requests in the current batch are iterated over and sent to the nextProcessor one by one.
     * In case of Leader, the nextRequestProcessor is ACKProcessor. Where the leader self ACKs each request.
     *
     * So related to Witness's Implementation:
     * We send a Request to the witness in SyncRequestProcessor (i.e In proposal stage), only when the witness is active
     * And We guarantee that, we send a proposal to a witness at least after it is written to the log of the leader.
     * So, I can queue a Request to the WitnessHandler any time after the zks.getZKDatabase().commit(); call in flush() method.
     *
     *Choice or Decision Required for the following:
     * Option 1: Send each request/proposal in the batch to the witness.
     * Option 2: Send only the last request in the batch to the witness and use the ACK sent by witness for the last request as an indirect ACK for requests in that batch.
     *   Op2 Impl Approach1: Augment WitnessRequestObject with batchStartZxid field. So when we create WitnessRequest, populate both batchStartZxid and Zxid of last request. Once
     *   ACK is recieved from witness for that request, WH will invoke processACK() on request from batchStartZxid to Zxid.
     *   Op2 Total Time Taken for Leader to ACK a request would be: timeWaitedToFormBatch + TimeToWriteToDisk + TimeTakenByWitHandlerToWriteToWitness(assuming there are other requests in the WH request queue, before this request)
     *   Op2 Analysis on time taken: When witness is not used, the total time taken would still be (timeWaitedToFormBatch + TimeToWriteToDisk). By introducing witness, we
     *   additionally add TimeTakenByWitHandlerToWriteToWitness. We should take this additional time into consideration when setting any related time limits.
     * */
    @Override
    public void run() {
        try {
            // we do this in an attempt to ensure that not all of the servers
            // in the ensemble take a snapshot at the same time
            resetSnapshotStats();
            lastFlushTime = Time.currentElapsedTime();
            while (true) {
                ServerMetrics.getMetrics().SYNC_PROCESSOR_QUEUE_SIZE.add(queuedRequests.size());

                long pollTime = Math.min(zks.getMaxWriteQueuePollTime(), getRemainingDelay());
                Request si = queuedRequests.poll(pollTime, TimeUnit.MILLISECONDS);
                if (si == null) {
                    /* We timed out looking for more writes to batch, go ahead and flush immediately */
                    flush();
                    si = queuedRequests.take();
                }

                if (si == REQUEST_OF_DEATH) {
                    break;
                }

                long startProcessTime = Time.currentElapsedTime();
                ServerMetrics.getMetrics().SYNC_PROCESSOR_QUEUE_TIME.add(startProcessTime - si.syncQueueStartTime);

                // track the number of records written to the log
                if (!si.isThrottled() && zks.getZKDatabase().append(si)) {
                    if (shouldSnapshot()) {
                        resetSnapshotStats();
                        // roll the log
                        zks.getZKDatabase().rollLog();
                        // take a snapshot
                        if (!snapThreadMutex.tryAcquire()) {
                            LOG.warn("Too busy to snap, skipping");
                        } else {
                            new ZooKeeperThread("Snapshot Thread") {
                                public void run() {
                                    try {
                                        zks.takeSnapshot();
                                    } catch (Exception e) {
                                        LOG.warn("Unexpected exception", e);
                                    } finally {
                                        snapThreadMutex.release();
                                    }
                                }
                            }.start();
                        }
                    }
                } else if (toFlush.isEmpty()) {
                    // optimization for read heavy workloads
                    // iff this is a read or a throttled request(which doesn't need to be written to the disk),
                    // and there are no pending flushes (writes), then just pass this to the next processor
                    if (nextProcessor != null) {
                        nextProcessor.processRequest(si);
                        if (nextProcessor instanceof Flushable) {
                            ((Flushable) nextProcessor).flush();
                        }
                    }
                    continue;
                }
                toFlush.add(si);
                if (shouldFlush()) {
                    flush();
                }
                ServerMetrics.getMetrics().SYNC_PROCESS_TIME.add(Time.currentElapsedTime() - startProcessTime);
            }
        } catch (Throwable t) {
            handleException(this.getName(), t);
        }
        LOG.info("SyncRequestProcessor exited!");
    }

    private void flush() throws IOException, RequestProcessorException {
        if (this.toFlush.isEmpty()) {
            return;
        }

        ServerMetrics.getMetrics().BATCH_SIZE.add(toFlush.size());

        long flushStartTime = Time.currentElapsedTime();
        zks.getZKDatabase().commit();
        ServerMetrics.getMetrics().SYNC_PROCESSOR_FLUSH_TIME.add(Time.currentElapsedTime() - flushStartTime);

        if (this.nextProcessor == null) {
            this.toFlush.clear();
        } else {
            sendRequestToWitness();
            while (!this.toFlush.isEmpty()) {
                final Request i = this.toFlush.remove();
                long latency = Time.currentElapsedTime() - i.syncQueueStartTime;
                ServerMetrics.getMetrics().SYNC_PROCESSOR_QUEUE_AND_FLUSH_TIME.add(latency);
                this.nextProcessor.processRequest(i);
            }
            if (this.nextProcessor instanceof Flushable) {
                ((Flushable) this.nextProcessor).flush();
            }
        }
        lastFlushTime = Time.currentElapsedTime();
    }

    public void shutdown() {
        LOG.info("Shutting down");
        queuedRequests.add(REQUEST_OF_DEATH);
        try {
            this.join();
            this.flush();
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while wating for {} to finish", this);
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.warn("Got IO exception during shutdown");
        } catch (RequestProcessorException e) {
            LOG.warn("Got request processor exception during shutdown");
        }
        if (nextProcessor != null) {
            nextProcessor.shutdown();
        }
    }

    public void processRequest(final Request request) {
        Objects.requireNonNull(request, "Request cannot be null");

        request.syncQueueStartTime = Time.currentElapsedTime();
        queuedRequests.add(request);
        ServerMetrics.getMetrics().SYNC_PROCESSOR_QUEUED.add(1);
    }

    /**
     * We attempt to send a request to a witness only when
     *  - the peer is a leader,
     *  - there are some writes that needs to be sent
     *  - there is a witness configured
     *  - and the witness is active.
     *
     *  Note: the code behaves as if there are multiple witnesses, and queues requests only to active witnesses.
     *  In actual, we will have only a single witness..
     *  I still have to investigate the Potential Complexities in having multiple witnesses.
     * */
    private void sendRequestToWitness() {
        if(this.zks instanceof LeaderZooKeeperServer && !this.toFlush.isEmpty()) {
            LeaderZooKeeperServer lzks = (LeaderZooKeeperServer)this.zks;
            List<WitnessHandler> witnessHandlerList = lzks.getLeader().getWitnesses();
            //If there are witnesses
            if(witnessHandlerList.size() != 0) {
                LOG.info("We have {} witnesses ", witnessHandlerList.size());
                long firstRequestZxid = this.toFlush.peekFirst().zxid;
                long lastRequestZxid = this.toFlush.peekLast().zxid;
                //WitnessHandler.WitnessRequest witnessRequest = new WitnessHandler.WitnessRequest()
                for(WitnessHandler wh : witnessHandlerList) {
                    if(wh.isActive()) {
                        LOG.info("Witness {} is active, queueing zxid batch {}(firstZxid) - {}(lastZxid)", wh.getSid(), Long.toHexString(firstRequestZxid), Long.toHexString(lastRequestZxid));
                        WitnessHandler.WitnessRequest wr = new WitnessHandler.WitnessRequest(lastRequestZxid, firstRequestZxid, true);
                        wh.queueRequest(wr);
                    }
                    else {
                        LOG.info("Witness {} is passive, so not sending proposal at this stage.");
                    }
                }
            }

        }
    }

}
