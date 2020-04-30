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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * When enabled, the RequestThrottler limits the number of outstanding requests
 * currently submitted to the request processor pipeline. The throttler augments
 * the limit imposed by the <code>globalOutstandingLimit</code> that is enforced
 * by the connection layer ({@link NIOServerCnxn}, {@link NettyServerCnxn}).
 *
 * The connection layer limit applies backpressure against the TCP connection by
 * disabling selection on connections once the request limit is reached. However,
 * the connection layer always allows a connection to send at least one request
 * before disabling selection on that connection. Thus, in a scenario with 40000
 * client connections, the total number of requests inflight may be as high as
 * 40000 even if the <code>globalOustandingLimit</code> was set lower.
 *
 * The RequestThrottler addresses this issue by adding additional queueing. When
 * enabled, client connections no longer submit requests directly to the request
 * processor pipeline but instead to the RequestThrottler. The RequestThrottler
 * is then responsible for issuing requests to the request processors, and
 * enforces a separate <code>maxRequests</code> limit. If the total number of
 * outstanding requests is higher than <code>maxRequests</code>, the throttler
 * will continually stall for <code>stallTime</code> milliseconds until
 * underlimit.
 *
 * The RequestThrottler can also optionally drop stale requests rather than
 * submit them to the processor pipeline. A stale request is a request sent
 * by a connection that is already closed, and/or a request whose latency
 * will end up being higher than its associated session timeout. The notion
 * of staleness is configurable, @see Request for more details.
 *
 * To ensure ordering guarantees, if a request is ever dropped from a connection
 * that connection is closed and flagged as invalid. All subsequent requests
 * inflight from that connection are then dropped as well.
 */
public class RequestThrottler extends ZooKeeperCriticalThread {

    private static final Logger LOG = LoggerFactory.getLogger(RequestThrottler.class);

    private final LinkedBlockingQueue<Request> submittedRequests = new LinkedBlockingQueue<Request>();

    private final ZooKeeperServer zks;
    private volatile boolean stopping;
    private volatile boolean killed;

    private static final String SHUTDOWN_TIMEOUT = "zookeeper.request_throttler.shutdownTimeout";
    private static int shutdownTimeout = 10000;

    static {
        shutdownTimeout = Integer.getInteger(SHUTDOWN_TIMEOUT, 10000);
        LOG.info("{} = {}", SHUTDOWN_TIMEOUT, shutdownTimeout);
    }

    /**
     * The total number of outstanding requests allowed before the throttler
     * starts stalling.
     *
     * When maxRequests = 0, throttling is disabled.
     */
    private static volatile int maxRequests = Integer.getInteger("zookeeper.request_throttle_max_requests", 0);

    /**
     * The time (in milliseconds) this is the maximum time for which throttler
     * thread may wait to be notified that it may proceed processing a request.
     */
    private static volatile int stallTime = Integer.getInteger("zookeeper.request_throttle_stall_time", 100);

    /**
     * When true, the throttler will drop stale requests rather than issue
     * them to the request pipeline. A stale request is a request sent by
     * a connection that is now closed, and/or a request that will have a
     * request latency higher than the sessionTimeout. The staleness of
     * a request is tunable property, @see Request for details.
     */
    private static volatile boolean dropStaleRequests = Boolean.parseBoolean(System.getProperty("zookeeper.request_throttle_drop_stale", "true"));

    protected boolean shouldThrottleOp(Request request, long elapsedTime) {
        return request.isThrottlable()
                && ZooKeeperServer.getThrottledOpWaitTime() > 0
                && elapsedTime > ZooKeeperServer.getThrottledOpWaitTime();
    }


    public RequestThrottler(ZooKeeperServer zks) {
        super("RequestThrottler", zks.getZooKeeperServerListener());
        this.zks = zks;
        this.stopping = false;
        this.killed = false;
    }

    public static int getMaxRequests() {
        return maxRequests;
    }

    public static void setMaxRequests(int requests) {
        maxRequests = requests;
    }

    public static int getStallTime() {
        return stallTime;
    }

    public static void setStallTime(int time) {
        stallTime = time;
    }

    public static boolean getDropStaleRequests() {
        return dropStaleRequests;
    }

    public static void setDropStaleRequests(boolean drop) {
        dropStaleRequests = drop;
    }

    @Override
    public void run() {
        try {
            while (true) {
                if (killed) {
                    break;
                }

                Request request = submittedRequests.take();
                if (Request.requestOfDeath == request) {
                    break;
                }

                if (request.mustDrop()) {
                    continue;
                }

                // Throttling is disabled when maxRequests = 0
                if (maxRequests > 0) {
                    while (!killed) {
                        if (dropStaleRequests && request.isStale()) {
                            // Note: this will close the connection
                            dropRequest(request);
                            ServerMetrics.getMetrics().STALE_REQUESTS_DROPPED.add(1);
                            request = null;
                            break;
                        }
                        if (zks.getInProcess() < maxRequests) {
                            break;
                        }
                        throttleSleep(stallTime);
                    }
                }

                if (killed) {
                    break;
                }

                // A dropped stale request will be null
                if (request != null) {
                    if (request.isStale()) {
                        ServerMetrics.getMetrics().STALE_REQUESTS.add(1);
                    }
                    final long elapsedTime = Time.currentElapsedTime() - request.requestThrottleQueueTime;
                    ServerMetrics.getMetrics().REQUEST_THROTTLE_QUEUE_TIME.add(elapsedTime);
                    if (shouldThrottleOp(request, elapsedTime)) {
                      request.setIsThrottled(true);
                      ServerMetrics.getMetrics().THROTTLED_OPS.add(1);
                    }
                    zks.submitRequestNow(request);
                }
            }
        } catch (InterruptedException e) {
            LOG.error("Unexpected interruption", e);
        }
        int dropped = drainQueue();
        LOG.info("RequestThrottler shutdown. Dropped {} requests", dropped);
    }

    private synchronized void throttleSleep(int stallTime) {
        try {
            ServerMetrics.getMetrics().REQUEST_THROTTLE_WAIT_COUNT.add(1);
            this.wait(stallTime);
        } catch (InterruptedException ie) {
            return;
        }
    }

    @SuppressFBWarnings(value = "NN_NAKED_NOTIFY", justification = "state change is in ZooKeeperServer.decInProgress() ")
    public synchronized void throttleWake() {
        this.notify();
    }

    private int drainQueue() {
        // If the throttler shutdown gracefully, the queue will be empty.
        // However, if the shutdown time limit was reached and the throttler
        // was killed, we have no other option than to drop all remaining
        // requests on the floor.
        int dropped = 0;
        Request request;
        LOG.info("Draining request throttler queue");
        while ((request = submittedRequests.poll()) != null) {
            dropped += 1;
            dropRequest(request);
        }
        return dropped;
    }

    private void dropRequest(Request request) {
        // Since we're dropping a request on the floor, we must mark the
        // connection as invalid to ensure any future requests from this
        // connection are also dropped in order to ensure ordering
        // semantics.
        ServerCnxn conn = request.getConnection();
        if (conn != null) {
            // Note: this will close the connection
            conn.setInvalid();
        }
        // Notify ZooKeeperServer that the request has finished so that it can
        // update any request accounting/throttling limits.
        zks.requestFinished(request);
    }

    public void submitRequest(Request request) {
        if (stopping) {
            LOG.debug("Shutdown in progress. Request cannot be processed");
            dropRequest(request);
        } else {
            request.requestThrottleQueueTime = Time.currentElapsedTime();
            submittedRequests.add(request);
        }
    }

    public int getInflight() {
        return submittedRequests.size();
    }

    public void shutdown() {
        // Try to shutdown gracefully
        LOG.info("Shutting down");
        stopping = true;
        submittedRequests.add(Request.requestOfDeath);
        try {
            this.join(shutdownTimeout);
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting for {} to finish", this);
        }

        // Forcibly shutdown if necessary in order to ensure request
        // queue is drained.
        killed = true;
        try {
            this.join();
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting for {} to finish", this);
            //TODO apply ZOOKEEPER-575 and remove this line.
            ServiceUtils.requestSystemExit(ExitCode.UNEXPECTED_ERROR.getValue());
        }
    }

}
