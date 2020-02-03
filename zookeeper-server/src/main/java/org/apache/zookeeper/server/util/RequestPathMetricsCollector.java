/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.util;

import static org.apache.zookeeper.ZooDefs.OpCode.checkWatches;
import static org.apache.zookeeper.ZooDefs.OpCode.create;
import static org.apache.zookeeper.ZooDefs.OpCode.create2;
import static org.apache.zookeeper.ZooDefs.OpCode.createContainer;
import static org.apache.zookeeper.ZooDefs.OpCode.delete;
import static org.apache.zookeeper.ZooDefs.OpCode.deleteContainer;
import static org.apache.zookeeper.ZooDefs.OpCode.exists;
import static org.apache.zookeeper.ZooDefs.OpCode.getACL;
import static org.apache.zookeeper.ZooDefs.OpCode.getChildren;
import static org.apache.zookeeper.ZooDefs.OpCode.getChildren2;
import static org.apache.zookeeper.ZooDefs.OpCode.getData;
import static org.apache.zookeeper.ZooDefs.OpCode.removeWatches;
import static org.apache.zookeeper.ZooDefs.OpCode.setACL;
import static org.apache.zookeeper.ZooDefs.OpCode.setData;
import static org.apache.zookeeper.ZooDefs.OpCode.setWatches2;
import static org.apache.zookeeper.ZooDefs.OpCode.sync;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class holds the requests path ( up till a certain depth) stats per request type
 */
public class RequestPathMetricsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(RequestPathMetricsCollector.class);
    // How many seconds does each slot represent, default is 15 seconds.
    private final int REQUEST_STATS_SLOT_DURATION;
    // How many slots we keep, default is 60 so it's 15 minutes total history.
    private final int REQUEST_STATS_SLOT_CAPACITY;
    // How far down the path we keep, default is 6.
    private final int REQUEST_PREPROCESS_PATH_DEPTH;
    // Sample rate, default is 0.1 (10%).
    private final float REQUEST_PREPROCESS_SAMPLE_RATE;
    private final long COLLECTOR_INITIAL_DELAY;
    private final long COLLECTOR_DELAY;
    private final int REQUEST_PREPROCESS_TOPPATH_MAX;
    private final boolean enabled;

    public static final String PATH_STATS_SLOT_CAPACITY = "zookeeper.pathStats.slotCapacity";
    public static final String PATH_STATS_SLOT_DURATION = "zookeeper.pathStats.slotDuration";
    public static final String PATH_STATS_MAX_DEPTH = "zookeeper.pathStats.maxDepth";
    public static final String PATH_STATS_SAMPLE_RATE = "zookeeper.pathStats.sampleRate";
    public static final String PATH_STATS_COLLECTOR_INITIAL_DELAY = "zookeeper.pathStats.initialDelay";
    public static final String PATH_STATS_COLLECTOR_DELAY = "zookeeper.pathStats.delay";
    public static final String PATH_STATS_TOP_PATH_MAX = "zookeeper.pathStats.topPathMax";
    public static final String PATH_STATS_ENABLED = "zookeeper.pathStats.enabled";
    private static final String PATH_SEPERATOR = "/";

    private final Map<String, PathStatsQueue> immutableRequestsMap;
    private final Random sampler;
    private final ScheduledThreadPoolExecutor scheduledExecutor;
    private final boolean accurateMode;

    public RequestPathMetricsCollector() {
        this(false);
    }

    public RequestPathMetricsCollector(boolean accurateMode) {
        final Map<String, PathStatsQueue> requestsMap = new HashMap<>();
        this.sampler = new Random(System.currentTimeMillis());
        this.accurateMode = accurateMode;

        REQUEST_PREPROCESS_TOPPATH_MAX = Integer.getInteger(PATH_STATS_TOP_PATH_MAX, 20);
        REQUEST_STATS_SLOT_DURATION = Integer.getInteger(PATH_STATS_SLOT_DURATION, 15);
        REQUEST_STATS_SLOT_CAPACITY = Integer.getInteger(PATH_STATS_SLOT_CAPACITY, 60);
        REQUEST_PREPROCESS_PATH_DEPTH = Integer.getInteger(PATH_STATS_MAX_DEPTH, 6);
        REQUEST_PREPROCESS_SAMPLE_RATE = Float.parseFloat(System.getProperty(PATH_STATS_SAMPLE_RATE, "0.1"));
        COLLECTOR_INITIAL_DELAY = Long.getLong(PATH_STATS_COLLECTOR_INITIAL_DELAY, 5);
        COLLECTOR_DELAY = Long.getLong(PATH_STATS_COLLECTOR_DELAY, 5);
        enabled = Boolean.getBoolean(PATH_STATS_ENABLED);

        LOG.info("{} = {}", PATH_STATS_SLOT_CAPACITY, REQUEST_STATS_SLOT_CAPACITY);
        LOG.info("{} = {}", PATH_STATS_SLOT_DURATION, REQUEST_STATS_SLOT_DURATION);
        LOG.info("{} = {}", PATH_STATS_MAX_DEPTH, REQUEST_PREPROCESS_PATH_DEPTH);
        LOG.info("{} = {}", PATH_STATS_COLLECTOR_INITIAL_DELAY, COLLECTOR_INITIAL_DELAY);
        LOG.info("{} = {}", PATH_STATS_COLLECTOR_DELAY, COLLECTOR_DELAY);
        LOG.info("{} = {}", PATH_STATS_ENABLED, enabled);

        this.scheduledExecutor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());
        scheduledExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduledExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        requestsMap.put(Request.op2String(create), new PathStatsQueue(create));
        requestsMap.put(Request.op2String(create2), new PathStatsQueue(create2));
        requestsMap.put(Request.op2String(createContainer), new PathStatsQueue(createContainer));
        requestsMap.put(Request.op2String(deleteContainer), new PathStatsQueue(deleteContainer));
        requestsMap.put(Request.op2String(delete), new PathStatsQueue(delete));
        requestsMap.put(Request.op2String(exists), new PathStatsQueue(exists));
        requestsMap.put(Request.op2String(setData), new PathStatsQueue(setData));
        requestsMap.put(Request.op2String(getData), new PathStatsQueue(getData));
        requestsMap.put(Request.op2String(getACL), new PathStatsQueue(getACL));
        requestsMap.put(Request.op2String(setACL), new PathStatsQueue(setACL));
        requestsMap.put(Request.op2String(getChildren), new PathStatsQueue(getChildren));
        requestsMap.put(Request.op2String(getChildren2), new PathStatsQueue(getChildren2));
        requestsMap.put(Request.op2String(checkWatches), new PathStatsQueue(checkWatches));
        requestsMap.put(Request.op2String(removeWatches), new PathStatsQueue(removeWatches));
        requestsMap.put(Request.op2String(setWatches2), new PathStatsQueue(setWatches2));
        requestsMap.put(Request.op2String(sync), new PathStatsQueue(sync));
        this.immutableRequestsMap = java.util.Collections.unmodifiableMap(requestsMap);
    }

    static boolean isWriteOp(int requestType) {
        switch (requestType) {
        case ZooDefs.OpCode.sync:
        case ZooDefs.OpCode.create:
        case ZooDefs.OpCode.create2:
        case ZooDefs.OpCode.createContainer:
        case ZooDefs.OpCode.delete:
        case ZooDefs.OpCode.deleteContainer:
        case ZooDefs.OpCode.setData:
        case ZooDefs.OpCode.reconfig:
        case ZooDefs.OpCode.setACL:
        case ZooDefs.OpCode.multi:
        case ZooDefs.OpCode.check:
            return true;
        }
        return false;
    }

    static String trimPathDepth(String path, int maxDepth) {
        int count = 0;
        StringBuilder sb = new StringBuilder();
        StringTokenizer pathTokenizer = new StringTokenizer(path, PATH_SEPERATOR);
        while (pathTokenizer.hasMoreElements() && count++ < maxDepth) {
            sb.append(PATH_SEPERATOR);
            sb.append(pathTokenizer.nextToken());
        }
        path = sb.toString();
        return path;
    }

    public void shutdown() {
        if (!enabled) {
            return;
        }

        LOG.info("shutdown scheduledExecutor");
        scheduledExecutor.shutdownNow();
    }

    public void start() {
        if (!enabled) {
            return;
        }

        LOG.info("Start the RequestPath collector");
        immutableRequestsMap.forEach((opType, pathStatsQueue) -> pathStatsQueue.start());

        // Schedule to log the top used read/write paths every 5 mins
        scheduledExecutor.scheduleWithFixedDelay(() -> {
            LOG.info("%nHere are the top Read paths:");
            logTopPaths(aggregatePaths(4, queue -> !queue.isWriteOperation()),
                        entry -> LOG.info("{} : {}", entry.getKey(), entry.getValue()));
            LOG.info("%nHere are the top Write paths:");
            logTopPaths(aggregatePaths(4, queue -> queue.isWriteOperation()),
                        entry -> LOG.info("{} : {}", entry.getKey(), entry.getValue()));
        }, COLLECTOR_INITIAL_DELAY, COLLECTOR_DELAY, TimeUnit.MINUTES);
    }

    /**
     * The public interface of the buffer. FinalRequestHandler will call into this for
     * each request that has a path and this needs to be fast. we sample the path so that
     * we don't have to store too many paths in memory
     */
    public void registerRequest(int type, String path) {
        if (!enabled) {
            return;
        }
        if (sampler.nextFloat() <= REQUEST_PREPROCESS_SAMPLE_RATE) {
            PathStatsQueue pathStatsQueue = immutableRequestsMap.get(Request.op2String(type));
            if (pathStatsQueue != null) {
                pathStatsQueue.registerRequest(path);
            } else {
                LOG.error("We should not handle {}", type);
            }
        }
    }

    public void dumpTopRequestPath(PrintWriter pwriter, String requestTypeName, int queryMaxDepth) {
        if (queryMaxDepth < 1) {
            return;
        }
        PathStatsQueue pathStatsQueue = immutableRequestsMap.get(requestTypeName);
        if (pathStatsQueue == null) {
            pwriter.println("Can not find path stats for type: " + requestTypeName);
            return;
        } else {
            pwriter.println("The top requests of type: " + requestTypeName);
        }
        Map<String, Integer> combinedMap;
        final int maxDepth = Math.min(queryMaxDepth, REQUEST_PREPROCESS_PATH_DEPTH);
        combinedMap = pathStatsQueue.collectStats(maxDepth);
        logTopPaths(combinedMap, entry -> pwriter.println(entry.getKey() + " : " + entry.getValue()));
    }

    public void dumpTopReadPaths(PrintWriter pwriter, int queryMaxDepth) {
        pwriter.println("The top read requests are");
        dumpTopAggregatedPaths(pwriter, queryMaxDepth, queue -> !queue.isWriteOperation);
    }

    public void dumpTopWritePaths(PrintWriter pwriter, int queryMaxDepth) {
        pwriter.println("The top write requests are");
        dumpTopAggregatedPaths(pwriter, queryMaxDepth, queue -> queue.isWriteOperation);
    }

    public void dumpTopPaths(PrintWriter pwriter, int queryMaxDepth) {
        pwriter.println("The top requests are");
        dumpTopAggregatedPaths(pwriter, queryMaxDepth, queue -> true);
    }

    /**
     * Combine all the path Stats Queue that matches the predicate together
     * and then write to the pwriter
     */
    private void dumpTopAggregatedPaths(PrintWriter pwriter, int queryMaxDepth, final Predicate<PathStatsQueue> predicate) {
        if (!enabled) {
            return;
        }
        final Map<String, Integer> combinedMap = aggregatePaths(queryMaxDepth, predicate);
        logTopPaths(combinedMap, entry -> pwriter.println(entry.getKey() + " : " + entry.getValue()));
    }

    Map<String, Integer> aggregatePaths(int queryMaxDepth, Predicate<PathStatsQueue> predicate) {
        final Map<String, Integer> combinedMap = new HashMap<>(REQUEST_PREPROCESS_TOPPATH_MAX);
        final int maxDepth = Math.min(queryMaxDepth, REQUEST_PREPROCESS_PATH_DEPTH);
        immutableRequestsMap.values()
                            .stream()
                            .filter(predicate)
                            .forEach(pathStatsQueue -> pathStatsQueue.collectStats(maxDepth).forEach(
                                (path, count) -> combinedMap.put(path, combinedMap.getOrDefault(path, 0) + count)));
        return combinedMap;
    }

    void logTopPaths(Map<String, Integer> combinedMap, final Consumer<Map.Entry<String, Integer>> output) {
        combinedMap.entrySet()
                   .stream()
                   // sort by path count
                   .sorted(Comparator.comparing(Map.Entry<String, Integer>::getValue).reversed())
                   .limit(REQUEST_PREPROCESS_TOPPATH_MAX).forEach(output);
    }

    class PathStatsQueue {

        private final String requestTypeName;
        private final AtomicReference<ConcurrentLinkedQueue<String>> currentSlot;
        private final LinkedBlockingQueue<Map<String, Integer>> requestPathStats;
        private final boolean isWriteOperation;

        public PathStatsQueue(int requestType) {
            this.requestTypeName = Request.op2String(requestType);
            this.isWriteOperation = isWriteOp(requestType);
            requestPathStats = new LinkedBlockingQueue<>(REQUEST_STATS_SLOT_CAPACITY);
            currentSlot = new AtomicReference<>(new ConcurrentLinkedQueue<>());
        }

        /*
         * The only write entry into this class, need to be fast.
         * Just queue up the path to the current slot queue locking free.
         */
        public void registerRequest(String path) {
            if (!enabled) {
                return;
            }
            currentSlot.get().offer(path);
        }

        ConcurrentLinkedQueue<String> getCurrentSlot() {
            return currentSlot.get();
        }

        /**
         * Helper function to MR the paths in the queue to map with count
         * 1. cut each path up to max depth
         * 2. aggregate the paths based on its count
         *
         * @param tobeProcessedSlot queue of paths called
         * @return a map containing aggregated path in the queue
         */
        Map<String, Integer> mapReducePaths(int maxDepth, Collection<String> tobeProcessedSlot) {
            Map<String, Integer> newSlot = new ConcurrentHashMap<>();
            tobeProcessedSlot.stream().filter(path -> path != null).forEach((path) -> {
                path = trimPathDepth(path, maxDepth);
                newSlot.put(path, newSlot.getOrDefault(path, 0) + 1);
            });
            return newSlot;
        }

        /**
         * The only read point of this class
         *
         * @return the aggregated path to count map
         */
        public Map<String, Integer> collectStats(int maxDepth) {
            Map<String, Integer> combinedMap;
            // Take a snapshot of the current slot and convert it to map.
            // Set the initial size as 0 since we don't want it to padding nulls in the end.
            Map<String, Integer> snapShot = mapReducePaths(
                maxDepth,
                Arrays.asList(currentSlot.get().toArray(new String[0])));
            // Starting from the snapshot and go through the queue to reduce them into one map
            // the iterator can run concurrently with write but we want to use a real lock in the test
            synchronized (accurateMode ? requestPathStats : new Object()) {
                combinedMap = requestPathStats.stream().reduce(snapShot, (firstMap, secondMap) -> {
                    secondMap.forEach((key, value) -> {
                        String trimmedPath = trimPathDepth(key, maxDepth);
                        firstMap.put(trimmedPath, firstMap.getOrDefault(trimmedPath, 0) + value);
                    });
                    return firstMap;
                });
            }
            return combinedMap;
        }

        /**
         * Start to schedule the pre-processing of the current slot
         */
        public void start() {
            if (!enabled) {
                return;
            }
            // Staggered start and then run every 15 seconds no matter what
            int delay = sampler.nextInt(REQUEST_STATS_SLOT_DURATION);
            // We need to use fixed Delay as the fixed rate will start the next one right
            // after the previous one finishes if it runs overtime instead of overlapping it.
            scheduledExecutor.scheduleWithFixedDelay(() -> {
                // Generate new slot so new requests will go here.
                ConcurrentLinkedQueue<String> tobeProcessedSlot = currentSlot.getAndSet(new ConcurrentLinkedQueue<>());
                try {
                    // pre process the last slot and queue it up, only one thread scheduled modified
                    // this but we can mess up the collect part so we put a lock in the test.
                    Map<String, Integer> latestSlot = mapReducePaths(REQUEST_PREPROCESS_PATH_DEPTH, tobeProcessedSlot);
                    synchronized (accurateMode ? requestPathStats : new Object()) {
                        if (requestPathStats.remainingCapacity() <= 0) {
                            requestPathStats.poll();
                        }
                        if (!requestPathStats.offer(latestSlot)) {
                            LOG.error("Failed to insert the new request path stats for {}", requestTypeName);
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Failed to insert the new request path stats for {} with exception {}", requestTypeName, e);
                }
            }, delay, REQUEST_STATS_SLOT_DURATION, TimeUnit.SECONDS);
        }

        boolean isWriteOperation() {
            return isWriteOperation;
        }

    }

}


