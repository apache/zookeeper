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

/**
 * ZooKeeper server MBean.
 */
public interface ZooKeeperServerMXBean {

    /**
     * @return the server socket port number
     */
    String getClientPort();
    /**
     * @return the zookeeper server version
     */
    String getVersion();
    /**
     * @return time the server was started
     */
    String getStartTime();
    /**
     * @return min request latency in ms
     */
    long getMinRequestLatency();
    /**
     * @return average request latency in ms
     */
    double getAvgRequestLatency();
    /**
     * @return max request latency in ms
     */
    long getMaxRequestLatency();
    /**
     * @return number of packets received so far
     */
    long getPacketsReceived();
    /**
     * @return number of packets sent so far
     */
    long getPacketsSent();
    /**
     * @return number of fsync threshold exceeds so far
     */
    long getFsyncThresholdExceedCount();
    /**
     * @return number of AuthFailedCount so far
     */
    long getAuthFailedCount();
    /**
     * @return number of NonMTLSLocalConnCount so far
     */
    long getNonMTLSLocalConnCount();
    /**
     * @return number of NonMTLSRemoteConnCount so far
     */
    long getNonMTLSRemoteConnCount();
    /**
     * @return number of outstanding requests.
     */
    long getOutstandingRequests();
    /**
     * Current TickTime of server in milliseconds
     */
    int getTickTime();
    /**
     * Set TickTime of server in milliseconds
     */
    void setTickTime(int tickTime);

    /** Current maxClientCnxns allowed from a particular host */
    int getMaxClientCnxnsPerHost();

    /** Set maxClientCnxns allowed from a particular host */
    void setMaxClientCnxnsPerHost(int max);

    /**
     * Current minSessionTimeout of the server in milliseconds
     */
    int getMinSessionTimeout();
    /**
     * Set minSessionTimeout of server in milliseconds
     */
    void setMinSessionTimeout(int min);

    /**
     * Current maxSessionTimeout of the server in milliseconds
     */
    int getMaxSessionTimeout();
    /**
     * Set maxSessionTimeout of server in milliseconds
     */
    void setMaxSessionTimeout(int max);

    boolean getResponseCachingEnabled();
    void setResponseCachingEnabled(boolean isEnabled);

    /* Connection throttling settings */
    int getConnectionMaxTokens();
    void setConnectionMaxTokens(int val);

    int getConnectionTokenFillTime();
    void setConnectionTokenFillTime(int val);

    int getConnectionTokenFillCount();
    void setConnectionTokenFillCount(int val);

    int getConnectionFreezeTime();
    void setConnectionFreezeTime(int val);

    double getConnectionDropIncrease();
    void setConnectionDropIncrease(double val);

    double getConnectionDropDecrease();
    void setConnectionDropDecrease(double val);

    double getConnectionDecreaseRatio();
    void setConnectionDecreaseRatio(double val);

    int getCommitProcMaxReadBatchSize();
    void setCommitProcMaxReadBatchSize(int size);

    int getCommitProcMaxCommitBatchSize();
    void setCommitProcMaxCommitBatchSize(int size);

    int getRequestThrottleLimit();
    void setRequestThrottleLimit(int requests);

    int getRequestThrottleStallTime();
    void setRequestThrottleStallTime(int time);

    boolean getRequestThrottleDropStale();
    void setRequestThrottleDropStale(boolean drop);

    int getThrottledOpWaitTime();
    void setThrottledOpWaitTime(int val);

    boolean getRequestStaleLatencyCheck();
    void setRequestStaleLatencyCheck(boolean check);

    boolean getRequestStaleConnectionCheck();
    void setRequestStaleConnectionCheck(boolean check);

    int getLargeRequestMaxBytes();
    void setLargeRequestMaxBytes(int bytes);

    int getLargeRequestThreshold();
    void setLargeRequestThreshold(int threshold);

    /**
     * Reset packet and latency statistics
     */
    void resetStatistics();
    /**
     * Reset min/avg/max latency statistics
     */
    void resetLatency();
    /**
     * Reset max latency statistics only.
     */
    void resetMaxLatency();
    /**
     * Reset Fsync Threshold Exceed Count statistics only.
     */
    void resetFsyncThresholdExceedCount();
    /**
     * Reset NonMTLS(Local+Remote)ConnCount statistics only.
     */
    void resetNonMTLSConnCount();
    /**
     * Reset AuthFailedCount statistics only.
     */
    void resetAuthFailedCount();
    /**
     * @return number of alive client connections
     */
    long getNumAliveConnections();

    /**
     * @return estimated size of data directory in bytes
     */
    long getDataDirSize();
    /**
     * @return estimated size of log directory in bytes
     */
    long getLogDirSize();

    /**
     * @return secure client port
     */
    String getSecureClientPort();
    /**
     * @return secure client address
     */
    String getSecureClientAddress();

    /**
     * Returns the elapsed sync of time of transaction log in milliseconds.
     */
    long getTxnLogElapsedSyncTime();

    /**
     * @return Returns the value of the following config setting: jute.maxbuffer
     */
    int getJuteMaxBufferSize();

    /**
     * @return size of latest generated client response
     */
    int getLastClientResponseSize();

    /**
     * @return size of smallest generated client response
     */
    int getMinClientResponseSize();

    /**
     * @return size of largest generated client response
     */
    int getMaxClientResponseSize();

    long getFlushDelay();
    void setFlushDelay(long delay);

    long getMaxWriteQueuePollTime();
    void setMaxWriteQueuePollTime(long delay);

    int getMaxBatchSize();
    void setMaxBatchSize(int size);

    /**
     * @return Current maxCnxns allowed to a single ZooKeeper server
     */
   int getMaxCnxns();

}
