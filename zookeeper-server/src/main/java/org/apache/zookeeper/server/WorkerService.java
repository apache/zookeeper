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
 * WorkerService是用于运行任务的工作线程池，使用一个或多个ExecutorServices实现。
 * WorkerService可以支持可分配的线程，它通过创建N个单独的线程ExecutorServices，
 * 或不可分配的线程来完成，它通过创建单个N线程* ExecutorService来完成。
 *   - NIOServerCnxnFactory uses a non-assignable WorkerService because the
 *     socket IO requests are order independent and allowing the
 *     ExecutorService to handle thread assignment gives optimal performance.
 *   - CommitProcessor uses an assignable WorkerService because requests for
 *     a given session must be processed in order.
 * ExecutorService provides queue management and thread restarting, so it's
 * useful even with a single thread.
 */
public class WorkerService {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerService.class);

    private final ArrayList<ExecutorService> workers = new ArrayList<ExecutorService>();

    private final String threadNamePrefix; // 线程名前缀
    private int numWorkerThreads;// 工作线程数量
    private boolean threadsAreAssignable; //线程是可分配的
    private long shutdownTimeoutMS = 5000; // 关闭等待时间

    private volatile boolean stopped = true;

    /**
     * @param name                  worker threads are named <name>Thread-##
     * @param numThreads            number of worker threads (0 - N)
     *                              If 0, scheduled work is run immediately by
     *                              the calling thread.
     * @param useAssignableThreads  whether the worker threads should be individually assignable or not
     *                              工作线程是否应单独分配
     */
    public WorkerService(String name, int numThreads,
                         boolean useAssignableThreads) {
        this.threadNamePrefix = (name == null ? "" : name) + "Thread";
        this.numWorkerThreads = numThreads;
        this.threadsAreAssignable = useAssignableThreads;
        start();
    }

    /**
     * Callers should implement a class extending WorkRequest in order to schedule work with the service.
     * 调用者应该实现一个扩展WorkRequest的类，以便安排使用该服务。
     */
    public static abstract class WorkRequest {
        /**
         * Must be implemented. Is called when the work request is run.
         */
        public abstract void doWork() throws Exception;

        /**
         * (Optional) If implemented, is called if the service is stopped
         * or unable to schedule the request.
         * （可选）如果已实现，则在服务停止*或无法安排请求时调用。
         */
        public void cleanup() {
        }
    }

    /**
     * Schedule work to be done.  If a worker thread pool is not being
     * used, work is done directly by this thread. This schedule API is
     * for use with non-assignable WorkerServices. For assignable
     * WorkerServices, will always run on the first thread.
     * 安排工作要做。如果未使用工作线程池，则此线程直接完成工作。
     * 此计划API用于不可分配的WorkerServices。
     * 对于可分配的WorkerServices，将始终在第一个线程上运行。
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

        ScheduledWorkRequest scheduledWorkRequest = new ScheduledWorkRequest(workRequest);

        // If we have a worker thread pool, use that; otherwise, do the work
        // directly.
        int size = workers.size();
        if (size > 0) {
            try {
                // make sure to map negative ids as well to [0, size-1]
                // 确保将负数ID映射到[0，size-1]
                // 如果threadsAreAssignable为true,size应该等于numWorkerThreads
                // 如果threadsAreAssignable为false,则size应该为1
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
            // 当没有工作线程池时，直接执行并等待其完成
            scheduledWorkRequest.run();
        }
    }

    // 执行WorkRequest的dowork任务
    private class ScheduledWorkRequest implements Runnable {
        private final WorkRequest workRequest;

        ScheduledWorkRequest(WorkRequest workRequest) {
            this.workRequest = workRequest;
        }

        @Override
        public void run() {
            try {
                // Check if stopped while request was on queue
                // 如果WorkerService停止服务，则执行workRequest.cleanup();程序
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
     * 守护线程工厂
     * ThreadFactory for the worker thread pool. We don't use the default
     * thread factory because
     * (1) we want to give the worker threads easierto identify names; and
     * (2) we want to make the worker threads daemon threads so they don't block the server from shutting down.
     * 工作线程池的ThreadFactory。我们不使用默认的*线程工厂，因为
     * （1）我们想让工作线程有更容易识别的名称;
     * （2）我们希望使工作线程守护程序线程，以便它们不会阻止服务器关闭。
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
                t.setDaemon(true);// 设置为守护线程
            if (t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);// 优先级5
            return t;
        }
    }

    public void start() {
        if (numWorkerThreads > 0) {
            if (threadsAreAssignable) {// 可分配线程，workers有numWorkerThreads个线程池，每个线程池1个线程
                for(int i = 1; i <= numWorkerThreads; ++i) {
                    workers.add(Executors.newFixedThreadPool(
                        1, new DaemonThreadFactory(threadNamePrefix, i)));
                }
            } else {// 不可分配线程，workers有1个线程池，每个线程池numWorkerThreads个线程
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
        // 为工作线程提供完成执行的时间
        long now = Time.currentElapsedTime();
        long endTime = now + shutdownTimeoutMS;
        // 在shutdownTimeoutMS时间范围内关闭所有workers，如果超过时间shutdownTimeoutMS则强制关闭
        for(ExecutorService worker : workers) {
            boolean terminated = false;
            while ((now = Time.currentElapsedTime()) <= endTime) {
                try {
                    // shutdown方法：平滑的关闭ExecutorService，当此方法被调用时，ExecutorService停止接收新的任务并且等待已经提交的任务（包含提交正在执行和提交未执行）执行完成。当所有提交任务执行完毕，线程池即被关闭。
                    // awaitTermination方法：接收人timeout和TimeUnit两个参数，用于设定超时时间及单位。当等待超过设定时间时，会监测ExecutorService是否已经关闭，若关闭则返回true，否则返回false。一般情况下会和shutdown方法组合使用。
                    terminated = worker.awaitTermination(endTime - now, TimeUnit.MILLISECONDS);//单位是毫秒
                    break;
                } catch (InterruptedException e) {
                    // ignore
                }
            }
            if (!terminated) {
                // If we've timed out, do a hard shutdown
                // 如果我们超时，请进行硬关机
                worker.shutdownNow();
            }
        }
    }
}
