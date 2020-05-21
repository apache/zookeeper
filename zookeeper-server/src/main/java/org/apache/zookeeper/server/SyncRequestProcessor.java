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
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.server.metric.AvgMinMaxCounter;
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

    private AvgMinMaxCounter lastRequestFlushLatency = new AvgMinMaxCounter("flushDelay");

    private static final double DEFAULT_SNAPSHOT_FACTOR = 0.5;
    private static final double SAFETY_SNAPSHOT_FACTOR = 10;

    private final BlockingQueue<Request> queuedRequests = new LinkedBlockingQueue<Request>();

    private final SnapshotGenerator snapGenerator;

    private final ZooKeeperServer zks;

    private final RequestProcessor nextProcessor;

    /**
     * Transactions that have been written and are waiting to be flushed to
     * disk. Basically this is the list of SyncItems whose callbacks will be
     * invoked after flush returns successfully.
     */
    private final Queue<Request> toFlush;
    private long lastFlushTime;

    private boolean onlySnapWhenSafetyIsThreatened = false;

    private static volatile Threshold selfSnapTxnRollThreshold = new Threshold(
            (int) (snapCount * DEFAULT_SNAPSHOT_FACTOR), (long) (snapSizeInBytes * DEFAULT_SNAPSHOT_FACTOR));
    private static volatile Threshold safetySnapThreshold = new Threshold(
            (int) (snapCount * SAFETY_SNAPSHOT_FACTOR), (long) (snapSizeInBytes * SAFETY_SNAPSHOT_FACTOR));


    public SyncRequestProcessor(ZooKeeperServer zks, RequestProcessor nextProcessor) {
        super("SyncThread:" + zks.getServerId(), zks.getZooKeeperServerListener());
        this.zks = zks;
        this.nextProcessor = nextProcessor;
        this.toFlush = new ArrayDeque<>(zks.getMaxBatchSize());
        this.snapGenerator = zks.getSnapshotGenerator();
    }

    public void setOnlySnapWhenSafetyIsThreatened(boolean mode) {
        if (mode != this.onlySnapWhenSafetyIsThreatened) {
            LOG.info("Set onlySnapWhenSafetyIsThreatened to {}", mode);
        }
        this.onlySnapWhenSafetyIsThreatened = mode;
    }

    public boolean isOnlySnapWhenSafetyIsThreatened() {
        return this.onlySnapWhenSafetyIsThreatened;
    }

    /**
     * used by tests to check for changing
     * snapcounts
     * @param count
     */
    public static void setSnapCount(int count) {
        snapCount = count;
        selfSnapTxnRollThreshold = new Threshold(
                (int) (snapCount * DEFAULT_SNAPSHOT_FACTOR), (long) (snapSizeInBytes * DEFAULT_SNAPSHOT_FACTOR));
        safetySnapThreshold = new Threshold(
                (int) (snapCount * SAFETY_SNAPSHOT_FACTOR), (long) (snapSizeInBytes * SAFETY_SNAPSHOT_FACTOR));
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

    public static class Threshold {

        private final int countLimit;
        private final long sizeLimit;

        private int randCount;
        private long randSize;

        public Threshold(int countLimit, long sizeLimit) {
            this.countLimit = countLimit;
            this.sizeLimit = sizeLimit;
            resetRandomNum();
        }

        public boolean meet(int count, long size) {
            boolean result = (count > (countLimit + randCount))
                    || (sizeLimit > 0 && size > (sizeLimit + randSize));
            if (result) {
                resetRandomNum();
            }
            return result;
        }

        /**
         * we do this in an attempt to ensure that not all of the servers
         * in the ensemble take a snapshot at the same time
         */
        private void resetRandomNum() {
            randCount = ThreadLocalRandom.current().nextInt(countLimit);
            randSize = Math.abs(ThreadLocalRandom.current().nextLong() % (sizeLimit));
        }
    }

    @Override
    public void run() {
        try {
            lastFlushTime = Time.currentElapsedTime();
            final ZKDatabase zkDB = zks.getZKDatabase();
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
                if (!si.isThrottled() && zkDB.append(si)) {
                    if (selfSnapTxnRollThreshold.meet(zkDB.getTxnCount(), zkDB.getTxnSize())) {
                        // roll the log
                        zkDB.rollLog();

                        if (onlySnapWhenSafetyIsThreatened) {
                            if (safetySnapThreshold.meet(zkDB.getTxnsSinceLastSnap(), zkDB.getTxnsSizeSinceLastSnap())) {
                                snapGenerator.takeSnapshot(false);
                                ServerMetrics.getMetrics().TAKING_SAFE_SNAPSHOT.add(1);
                                LOG.info("The txns size since last snapshot is larger than 10x, "
                                        + "going to take a snapshot for safe.");
                            }
                        } else {
                            snapGenerator.takeSnapshot(false);
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
            while (!this.toFlush.isEmpty()) {
                final Request i = this.toFlush.remove();
                long latency = Time.currentElapsedTime() - i.syncQueueStartTime;
                lastRequestFlushLatency.addDataPoint(latency);
                ServerMetrics.getMetrics().SYNC_PROCESSOR_QUEUE_AND_FLUSH_TIME.add(latency);
                this.nextProcessor.processRequest(i);
            }
            if (this.nextProcessor instanceof Flushable) {
                ((Flushable) this.nextProcessor).flush();
            }
        }
        lastFlushTime = Time.currentElapsedTime();
    }

    public long getLastRequestFlushLatency() {
        long result = (long) lastRequestFlushLatency.getAvg();
        lastRequestFlushLatency.reset();
        return result;
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

    public int getQueuedRequestsSize() {
        return queuedRequests.size();
    }
}
