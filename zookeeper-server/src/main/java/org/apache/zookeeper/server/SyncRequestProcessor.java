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

import java.io.Flushable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

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
// 继承ZooKeeperCriticalThread，是一个关键重要线程
// SyncRequestProcessor，该处理器将请求存入磁盘，其将请求批量的存入磁盘以提高效率，请求在写入磁盘之前是不会被转发到下个处理器的。
// SyncRequestProcessor也继承了Thread类并实现了RequestProcessor接口，表示其可以作为线程使用。
// SyncRequestProcessor维护了ZooKeeperServer实例，其用于获取ZooKeeper的数据库和其他信息；
// 维护了一个处理请求的队列，其用于存放请求；
// 维护了一个处理快照的线程，用于处理快照；
// 同时还维护了一个等待被刷新到磁盘的请求队列。
public class SyncRequestProcessor extends ZooKeeperCriticalThread implements RequestProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(SyncRequestProcessor.class);

    private static final int FLUSH_SIZE = 1000;

    private static final Request REQUEST_OF_DEATH = Request.requestOfDeath;

    /** The number of log entries to log before starting a snapshot启动快照之前要记录的日志条目数 */
     // 最少是2个
    private static int snapCount = ZooKeeperServer.getSnapCount();

    // 请求队列
    private final BlockingQueue<Request> queuedRequests = new LinkedBlockingQueue<Request>();
    // 信号量
    private final Semaphore snapThreadMutex = new Semaphore(1);
    // Zookeeper服务器
    private final ZooKeeperServer zks;
    // 下一个请求处理链的处理类
    private final RequestProcessor nextProcessor;

    /**
     * Transactions that have been written and are waiting to be flushed to
     * disk. Basically this is the list of SyncItems whose callbacks will be
     * invoked after flush returns successfully.
     * 已写入并等待刷新到磁盘的事务。
     * 基本上这是SyncItems的列表，其回调将在flush返回成功后被调用。
     */
     // 等待被刷新到磁盘的请求队列
    private final Queue<Request> toFlush = new ArrayDeque<>(FLUSH_SIZE);

    public SyncRequestProcessor(ZooKeeperServer zks, RequestProcessor nextProcessor) {
        super("SyncThread:" + zks.getServerId(), zks.getZooKeeperServerListener());
        this.zks = zks;
        this.nextProcessor = nextProcessor;
    }

    /**
     * used by tests to check for changing snapcounts
     * 测试用于检查更改快照计数
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

    @Override
    public void run() {
        try {
             // 写日志数量初始化为0
            int logCount = 0;

            // we do this in an attempt to ensure that not all of the servers
            // in the ensemble take a snapshot at the same time
            // 我们这样做是为了确保并非整体中的所有服务器都同时拍摄快照
            int randRoll = ThreadLocalRandom.current().nextInt(snapCount / 2, snapCount);
            while (true) {
                Request si = queuedRequests.poll();
                if (si == null) {
                    flush();
                    si = queuedRequests.take();
                }
  
                if (si == REQUEST_OF_DEATH) {
                    // 执行了关闭操作
                    break;
                }

                // track the number of records written to the log
                if (zks.getZKDatabase().append(si)) {
                    logCount++;
                    if (logCount > randRoll) {
                        randRoll = ThreadLocalRandom.current().nextInt(snapCount / 2, snapCount);
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
                        logCount = 0;
                    }
                } else if (toFlush.isEmpty()) {
                    // optimization for read heavy workloads
                    // iff this is a read, and there are no pending
                    // flushes (writes), then just pass this to the next
                    // processor
                    if (nextProcessor != null) {
                        nextProcessor.processRequest(si);
                        if (nextProcessor instanceof Flushable) {
                            ((Flushable)nextProcessor).flush();
                        }
                    }
                    continue;
                }
                toFlush.add(si);
                if (toFlush.size() == FLUSH_SIZE) {
                    flush();
                }
            }
        } catch (Throwable t) {
            handleException(this.getName(), t);
        }
        LOG.info("SyncRequestProcessor exited!");
    }

    // 主要是刷出toFlush队列中的Request
    private void flush() throws IOException, RequestProcessorException {
      if (this.toFlush.isEmpty()) {
          return;
      }

      zks.getZKDatabase().commit();

      if (this.nextProcessor == null) {
          // 如果处理链没有下一个处理程序，就直接清除toFlush中的内容
        this.toFlush.clear();
      } else {
          while (!this.toFlush.isEmpty()) {
              // 移除并取出Request执行下一个处理程序
              final Request i = this.toFlush.remove();
              this.nextProcessor.processRequest(i);
          }
          if (this.nextProcessor instanceof Flushable) {
              //如果下一个请求处理程序是Flushable
              ((Flushable)this.nextProcessor).flush();
          } 
      }
    }

    public void shutdown() {
        LOG.info("Shutting down");
        // 向请求队列中添加REQUEST_OF_DEATH
        queuedRequests.add(REQUEST_OF_DEATH);
        try {
            this.join();// 等待子线程处理结束
            this.flush();
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while wating for " + this + " to finish");
            Thread.currentThread().interrupt(); // 中断线程
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
        queuedRequests.add(request);
    }

}
