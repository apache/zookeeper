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

/**
 * ZooKeeper server MBean.
 */
public interface ZooKeeperServerMXBean {
    /**
     * @return the server socket port number
     */
    public String getClientPort();
    /**
     * @return the zookeeper server version
     */
    public String getVersion();
    /**
     * @return time the server was started
     */
    public String getStartTime();
    /**
     * @return min request latency in ms
     */
    public long getMinRequestLatency();
    /**
     * @return average request latency in ms
     */
    public long getAvgRequestLatency();
    /**
     * @return max request latency in ms
     */
    public long getMaxRequestLatency();
    /**
     * @return number of packets received so far
     */
    public long getPacketsReceived();
    /**
     * @return number of packets sent so far
     */
    public long getPacketsSent();
    /**
     * @return number of fsync threshold exceeds so far
     */
    public long getFsyncThresholdExceedCount();
    /**
     * @return number of outstanding requests.
     */
    public long getOutstandingRequests();
    /**
     * Current TickTime of server in milliseconds
     */
    public int getTickTime();
    /**
     * Set TickTime of server in milliseconds
     */
    public void setTickTime(int tickTime);

    /** Current maxClientCnxns allowed from a particular host */
    public int getMaxClientCnxnsPerHost();

    /** Set maxClientCnxns allowed from a particular host */
    public void setMaxClientCnxnsPerHost(int max);

    /**
     * Current minSessionTimeout of the server in milliseconds
     */
    public int getMinSessionTimeout();
    /**
     * Set minSessionTimeout of server in milliseconds
     */
    public void setMinSessionTimeout(int min);

    /**
     * Current maxSessionTimeout of the server in milliseconds
     */
    public int getMaxSessionTimeout();
    /**
     * Set maxSessionTimeout of server in milliseconds
     */
    public void setMaxSessionTimeout(int max);

    /**
     * Reset packet and latency statistics 
     */
    public void resetStatistics();
    /**
     * Reset min/avg/max latency statistics
     */
    public void resetLatency();
    /**
     * Reset max latency statistics only.
     */
    public void resetMaxLatency();
    /**
     * Reset Fsync Threshold Exceed Count statistics only.
     */
    public void resetFsyncThresholdExceedCount();
    /**
     * @return number of alive client connections
     */
    public long getNumAliveConnections();

    /**
     * @return estimated size of data directory in bytes
    */
    public long getDataDirSize();
    /**
     * @return estimated size of log directory in bytes
     */
    public long getLogDirSize();

    /**
     * @return secure client port
    */
    public String getSecureClientPort();
    /**
     * @return secure client address
     */
    public String getSecureClientAddress();

    /**
     * Returns the elapsed sync of time of transaction log in milliseconds.
     */
    public long getTxnLogElapsedSyncTime();

    /**
     * @return Returns the value of the following config setting: jute.maxbuffer
     */
    public int getJuteMaxBufferSize();

    /**
     * @return size of latest generated client response
     */
    public int getLastClientResponseSize();

    /**
     * @return size of smallest generated client response
     */
    public int getMinClientResponseSize();

    /**
     * @return size of largest generated client response
     */
    public int getMaxClientResponseSize();
}
