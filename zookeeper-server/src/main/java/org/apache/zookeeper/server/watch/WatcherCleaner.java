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

package org.apache.zookeeper.server.watch;

import java.util.Set;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.server.RateLogger;
import org.apache.zookeeper.server.WorkerService;
import org.apache.zookeeper.server.WorkerService.WorkRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread used to lazily clean up the closed watcher, it will trigger the
 * clean up when the dead watchers get certain number or some number of
 * seconds has elapsed since last clean up.
 *
 * Cost of running it:
 *
 * - need to go through all the paths even if the watcher may only
 *   watching a single path
 * - block in the path BitHashSet when we try to check the dead watcher
 *   which won't block other stuff
 */
public class WatcherCleaner extends Thread {

    private static final Logger LOG = LoggerFactory.getLogger(WatcherCleaner.class);
    private final RateLogger RATE_LOGGER = new RateLogger(LOG);

    private volatile boolean stopped = false;
    private final Object cleanEvent = new Object();
    private final Object processingCompletedEvent = new Object();
    private final Random r = new Random(System.nanoTime());
    private final WorkerService cleaners;

    private final Set<Integer> deadWatchers;
    private final IDeadWatcherListener listener;
    private final int watcherCleanThreshold;
    private final int watcherCleanIntervalInSeconds;
    private final int maxInProcessingDeadWatchers;
    private final AtomicInteger totalDeadWatchers = new AtomicInteger();

    public WatcherCleaner(IDeadWatcherListener listener) {
        this(listener,
            Integer.getInteger("zookeeper.watcherCleanThreshold", 1000),
            Integer.getInteger("zookeeper.watcherCleanIntervalInSeconds", 600),
            Integer.getInteger("zookeeper.watcherCleanThreadsNum", 2),
            Integer.getInteger("zookeeper.maxInProcessingDeadWatchers", -1));
    }

    public WatcherCleaner(IDeadWatcherListener listener,
            int watcherCleanThreshold, int watcherCleanIntervalInSeconds,
            int watcherCleanThreadsNum, int maxInProcessingDeadWatchers) {
        this.listener = listener;
        this.watcherCleanThreshold = watcherCleanThreshold;
        this.watcherCleanIntervalInSeconds = watcherCleanIntervalInSeconds;
        int suggestedMaxInProcessingThreshold =
                watcherCleanThreshold * watcherCleanThreadsNum;
        if (maxInProcessingDeadWatchers > 0 &&
                maxInProcessingDeadWatchers < suggestedMaxInProcessingThreshold) {
            maxInProcessingDeadWatchers = suggestedMaxInProcessingThreshold;
            LOG.info("The maxInProcessingDeadWatchers config is smaller " +
                    "than the suggested one, change it to use {}",
                    maxInProcessingDeadWatchers);
        }
        this.maxInProcessingDeadWatchers = maxInProcessingDeadWatchers;
        this.deadWatchers = new HashSet<Integer>();
        this.cleaners = new WorkerService("DeadWatcherCleanner",
                watcherCleanThreadsNum, false);

        LOG.info("watcherCleanThreshold={}, watcherCleanIntervalInSeconds={}" +
                ", watcherCleanThreadsNum={}, maxInProcessingDeadWatchers={}",
                watcherCleanThreshold, watcherCleanIntervalInSeconds,
                watcherCleanThreadsNum, maxInProcessingDeadWatchers);
    }

    public void addDeadWatcher(int watcherBit) {
        // Wait if there are too many watchers waiting to be closed,
        // this is will slow down the socket packet processing and
        // the adding watches in the ZK pipeline.
        while (maxInProcessingDeadWatchers > 0 && !stopped &&
                totalDeadWatchers.get() >= maxInProcessingDeadWatchers) {
            try {
                RATE_LOGGER.rateLimitLog("Waiting for dead watchers cleaning");
                synchronized(processingCompletedEvent) {
                    processingCompletedEvent.wait(100);
                }
            } catch (InterruptedException e) {
                LOG.info("Got interrupted while waiting for dead watches " +
                        "queue size");
                break;
            }
        }
        synchronized (this) {
            if (deadWatchers.add(watcherBit)) {
                totalDeadWatchers.incrementAndGet();
                if (deadWatchers.size() >= watcherCleanThreshold) {
                    synchronized (cleanEvent) {
                        cleanEvent.notifyAll();
                    }
                }
            }
        }
    }

    @Override
    public void run() {
        while (!stopped) {
            synchronized (cleanEvent) {
                try {
                    // add some jitter to avoid cleaning dead watchers at the
                    // same time in the quorum
                    if (!stopped && deadWatchers.size() < watcherCleanThreshold) {
                        int maxWaitMs = (watcherCleanIntervalInSeconds +
                            r.nextInt(watcherCleanIntervalInSeconds / 2 + 1)) * 1000;
                        cleanEvent.wait(maxWaitMs);
                    }
                } catch (InterruptedException e) {
                    LOG.info("Received InterruptedException while " +
                            "waiting for cleanEvent");
                    break;
                }
            }

            if (deadWatchers.isEmpty()) {
                continue;
            }

            synchronized (this) {
                // Clean the dead watchers need to go through all the current 
                // watches, which is pretty heavy and may take a second if 
                // there are millions of watches, that's why we're doing lazily 
                // batch clean up in a separate thread with a snapshot of the 
                // current dead watchers.
                final Set<Integer> snapshot = new HashSet<Integer>(deadWatchers);
                deadWatchers.clear();
                int total = snapshot.size();
                LOG.info("Processing {} dead watchers", total);
                cleaners.schedule(new WorkRequest() {
                    @Override
                    public void doWork() throws Exception {
                        long startTime = Time.currentElapsedTime();
                        listener.processDeadWatchers(snapshot);
                        long latency = Time.currentElapsedTime() - startTime;
                        LOG.info("Takes {} to process {} watches", latency, total);
                        totalDeadWatchers.addAndGet(-total);
                        synchronized(processingCompletedEvent) {
                            processingCompletedEvent.notifyAll();
                        }
                    }
                });
            }
        }
        LOG.info("WatcherCleaner thread exited");
    }

    public void shutdown() {
        stopped = true;
        deadWatchers.clear();
        cleaners.stop();
        this.interrupt();
        if (LOG.isInfoEnabled()) {
            LOG.info("WatcherCleaner thread shutdown is initiated");
        }
    }

}
