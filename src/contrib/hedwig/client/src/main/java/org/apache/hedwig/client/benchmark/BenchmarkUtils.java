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
package org.apache.hedwig.client.benchmark;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;

import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.util.Callback;

public class BenchmarkUtils {
    static final Logger logger = Logger.getLogger(BenchmarkUtils.class);

    public static double calcTp(final int count, long startTime) {
        return 1000. * count / (System.currentTimeMillis() - startTime);
    }

    /**
     * Stats aggregator for callback (round-trip) operations. Measures both
     * throughput and latency.
     */
    public static class ThroughputLatencyAggregator {
        int numBuckets;
        final ThroughputAggregator tpAgg;
        final Semaphore outstanding;
        final AtomicLong sum = new AtomicLong();

        final AtomicLong[] latencyBuckets;

        // bucket[i] is count of number of operations that took >= i ms and <
        // (i+1) ms.

        public ThroughputLatencyAggregator(String label, int count, int limit) throws InterruptedException {
            numBuckets = Integer.getInteger("numBuckets", 101);
            latencyBuckets = new AtomicLong[numBuckets];
            tpAgg = new ThroughputAggregator(label, count);
            outstanding = new Semaphore(limit);
            for (int i = 0; i < numBuckets; i++) {
                latencyBuckets[i] = new AtomicLong();
            }
        }

        public void reportLatency(long latency) {
            sum.addAndGet(latency);

            int bucketIndex;
            if (latency >= numBuckets) {
                bucketIndex = (int) numBuckets - 1;
            } else {
                bucketIndex = (int) latency;
            }
            latencyBuckets[bucketIndex].incrementAndGet();
        }

        private String getPercentile(double percentile) {
            int numInliersNeeded = (int) (percentile / 100 * tpAgg.count);
            int numInliersFound = 0;
            for (int i = 0; i < numBuckets - 1; i++) {
                numInliersFound += latencyBuckets[i].intValue();
                if (numInliersFound > numInliersNeeded) {
                    return i + "";
                }
            }
            return " >= " + (numBuckets - 1);
        }

        public String summarize(long startTime) {
            double percentile = Double.parseDouble(System.getProperty("percentile", "99.9"));
            return tpAgg.summarize(startTime) + ", avg latency = " + sum.get() / tpAgg.count + ", " + percentile
                    + "%ile latency = " + getPercentile(percentile);
        }
    }

    /**
     * Stats aggregator for non-callback (single-shot) operations. Measures just
     * throughput.
     */
    public static class ThroughputAggregator {
        final String label;
        final int count;
        final AtomicInteger done = new AtomicInteger();
        final AtomicLong earliest = new AtomicLong();
        final AtomicInteger numFailed = new AtomicInteger();
        final LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<Integer>();

        public ThroughputAggregator(final String label, final int count) {
            this.label = label;
            this.count = count;
            if (count == 0)
                queue.add(0);
            if (Boolean.getBoolean("progress")) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            for (int doneSnap = 0, prev = 0; doneSnap < count; prev = doneSnap, doneSnap = done.get()) {
                                if (doneSnap > prev) {
                                    System.out.println(label + " progress: " + doneSnap + " of " + count);
                                }
                                Thread.sleep(1000);
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                }).start();
            }
        }

        public void ding(boolean failed) {
            int snapDone = done.incrementAndGet();
            earliest.compareAndSet(0, System.currentTimeMillis());
            if (failed)
                numFailed.incrementAndGet();
            if (logger.isDebugEnabled())
                logger.debug(label + " " + (failed ? "failed" : "succeeded") + ", done so far = " + snapDone);
            if (snapDone == count) {
                queue.add(numFailed.get());
            }
        }

        public String summarize(long startTime) {
            return "Finished " + label + ": count = " + done.get() + ", tput = " + calcTp(count, startTime)
                    + " ops/s, numFailed = " + numFailed;
        }
    }

    public static class BenchmarkCallback implements Callback<Void> {

        final ThroughputLatencyAggregator agg;
        final long startTime;

        public BenchmarkCallback(ThroughputLatencyAggregator agg) throws InterruptedException {
            this.agg = agg;
            agg.outstanding.acquire();
            // Must set the start time *after* taking acquiring on outstanding.
            startTime = System.currentTimeMillis();
        }

        private void finish(boolean failed) {
            agg.reportLatency(System.currentTimeMillis() - startTime);
            agg.tpAgg.ding(failed);
            agg.outstanding.release();
        }

        @Override
        public void operationFinished(Object ctx, Void resultOfOperation) {
            finish(false);
        }

        @Override
        public void operationFailed(Object ctx, PubSubException exception) {
            finish(true);
        }
    };

}
