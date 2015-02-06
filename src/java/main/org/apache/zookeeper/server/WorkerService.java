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

package org.apache.zookeeper.server;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorkerService is a worker thread pool for running tasks and is implemented
 * using one or more ExecutorServices. A WorkerService can support assignable
 * threads, which it does by creating N separate single thread ExecutorServices,
 * or non-assignable threads, which it does by creating a single N-thread
 * ExecutorService.
 *   - NIOServerCnxnFactory uses a non-assignable WorkerService because the
 *     socket IO requests are order independent and allowing the
 *     ExecutorService to handle thread assignment gives optimal performance.
 *   - CommitProcessor uses an assignable WorkerService because requests for
 *     a given session must be processed in order.
 * ExecutorService provides queue management and thread restarting, so it's
 * useful even with a single thread.
 */
public class WorkerService {
    private static final Logger LOG =
        LoggerFactory.getLogger(WorkerService.class);

    private final ArrayList<ExecutorService> workers =
        new ArrayList<ExecutorService>();

    private final String threadNamePrefix;
    private int numWorkerThreads;
    private boolean threadsAreAssignable;
    private long shutdownTimeoutMS = 5000;

    private volatile boolean stopped = true;

    /**
     * @param name                  worker threads are named <name>Thread-##
     * @param numThreads            number of worker threads (0 - N)
     *                              If 0, scheduled work is run immediately by
     *                              the calling thread.
     * @param useAssignableThreads  whether the worker threads should be
     *                              individually assignable or not
     */
    public WorkerService(String name, int numThreads,
                         boolean useAssignableThreads) {
        this.threadNamePrefix = (name == null ? "" : name) + "Thread";
        this.numWorkerThreads = numThreads;
        this.threadsAreAssignable = useAssignableThreads;
        start();
    }

    /**
     * Callers should implement a class extending WorkRequest in order to
     * schedule work with the service.
     */
    public static abstract class WorkRequest {
        /**
         * Must be implemented. Is called when the work request is run.
         */
        public abstract void doWork() throws Exception;

        /**
         * (Optional) If implemented, is called if the service is stopped
         * or unable to schedule the request.
         */
        public void cleanup() {
        }
    }

    /**
     * Schedule work to be done.  If a worker thread pool is not being
     * used, work is done directly by this thread. This schedule API is
     * for use with non-assignable WorkerServices. For assignable
     * WorkerServices, will always run on the first thread.
     */
    public void schedule(WorkRequest workRequest) {
        schedule(workRequest, 0);
    }

    /**
     * Schedule work to be done by the thread assigned to this id. Thread
     * assignment is a single mod operation on the number of threads.  If a
     * worker thread pool is not being used, work is done directly by
     * this thread.
     */
    public void schedule(WorkRequest workRequest, long id) {
        if (stopped) {
            workRequest.cleanup();
            return;
        }

        ScheduledWorkRequest scheduledWorkRequest =
            new ScheduledWorkRequest(workRequest);

        // If we have a worker thread pool, use that; otherwise, do the work
        // directly.
        int size = workers.size();
        if (size > 0) {
            try {
                // make sure to map negative ids as well to [0, size-1]
                int workerNum = ((int) (id % size) + size) % size;
                ExecutorService worker = workers.get(workerNum);
                worker.execute(scheduledWorkRequest);
            } catch (RejectedExecutionException e) {
                LOG.warn("ExecutorService rejected execution", e);
                workRequest.cleanup();
            }
        } else {
            // When there is no worker thread pool, do the work directly
            // and wait for its completion
            scheduledWorkRequest.start();
            try {
                scheduledWorkRequest.join();
            } catch (InterruptedException e) {
                LOG.warn("Unexpected exception", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    private class ScheduledWorkRequest extends ZooKeeperThread {
        private final WorkRequest workRequest;

        ScheduledWorkRequest(WorkRequest workRequest) {
            super("ScheduledWorkRequest");
            this.workRequest = workRequest;
        }

        @Override
        public void run() {
            try {
                // Check if stopped while request was on queue
                if (stopped) {
                    workRequest.cleanup();
                    return;
                }
                workRequest.doWork();
            } catch (Exception e) {
                LOG.warn("Unexpected exception", e);
                workRequest.cleanup();
            }
        }
    }

    /**
     * ThreadFactory for the worker thread pool. We don't use the default
     * thread factory because (1) we want to give the worker threads easier
     * to identify names; and (2) we want to make the worker threads daemon
     * threads so they don't block the server from shutting down.
     */
    private static class DaemonThreadFactory implements ThreadFactory {
        final ThreadGroup group;
        final AtomicInteger threadNumber = new AtomicInteger(1);
        final String namePrefix;

        DaemonThreadFactory(String name) {
            this(name, 1);
        }

        DaemonThreadFactory(String name, int firstThreadNum) {
            threadNumber.set(firstThreadNum);
            SecurityManager s = System.getSecurityManager();
            group = (s != null)? s.getThreadGroup() :
                                 Thread.currentThread().getThreadGroup();
            namePrefix = name + "-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r,
                                  namePrefix + threadNumber.getAndIncrement(),
                                  0);
            if (!t.isDaemon())
                t.setDaemon(true);
            if (t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    public void start() {
        if (numWorkerThreads > 0) {
            if (threadsAreAssignable) {
                for(int i = 1; i <= numWorkerThreads; ++i) {
                    workers.add(Executors.newFixedThreadPool(
                        1, new DaemonThreadFactory(threadNamePrefix, i)));
                }
            } else {
                workers.add(Executors.newFixedThreadPool(
                    numWorkerThreads, new DaemonThreadFactory(threadNamePrefix)));
            }
        }
        stopped = false;
    }

    public void stop() {
        stopped = true;

        // Signal for graceful shutdown
        for(ExecutorService worker : workers) {
            worker.shutdown();
        }
    }

    public void join(long shutdownTimeoutMS) {
        // Give the worker threads time to finish executing
        long now = Time.currentElapsedTime();
        long endTime = now + shutdownTimeoutMS;
        for(ExecutorService worker : workers) {
            boolean terminated = false;
            while ((now = Time.currentElapsedTime()) <= endTime) {
                try {
                    terminated = worker.awaitTermination(
                        endTime - now, TimeUnit.MILLISECONDS);
                    break;
                } catch (InterruptedException e) {
                    // ignore
                }
            }
            if (!terminated) {
                // If we've timed out, do a hard shutdown
                worker.shutdownNow();
            }
        }
    }
}
