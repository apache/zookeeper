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

import java.util.Date;
import org.apache.jute.BinaryInputArchive;
import org.apache.zookeeper.Version;
import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.apache.zookeeper.server.quorum.CommitProcessor;

/**
 * This class implements the ZooKeeper server MBean interface.
 */
public class ZooKeeperServerBean implements ZooKeeperServerMXBean, ZKMBeanInfo {

    private final Date startTime;
    private final String name;

    protected final ZooKeeperServer zks;

    public ZooKeeperServerBean(ZooKeeperServer zks) {
        startTime = new Date();
        this.zks = zks;
        name = "StandaloneServer_port" + zks.getClientPort();
    }

    public String getClientPort() {
        return Integer.toString(zks.getClientPort());
    }

    public String getName() {
        return name;
    }

    public boolean isHidden() {
        return false;
    }

    public String getStartTime() {
        return startTime.toString();
    }

    public String getVersion() {
        return Version.getFullVersion();
    }

    public double getAvgRequestLatency() {
        return zks.serverStats().getAvgLatency();
    }

    public long getMaxRequestLatency() {
        return zks.serverStats().getMaxLatency();
    }

    public long getMinRequestLatency() {
        return zks.serverStats().getMinLatency();
    }

    public long getOutstandingRequests() {
        return zks.serverStats().getOutstandingRequests();
    }

    public int getTickTime() {
        return zks.getTickTime();
    }

    public void setTickTime(int tickTime) {
        zks.setTickTime(tickTime);
    }

    public int getMaxClientCnxnsPerHost() {
        return zks.getMaxClientCnxnsPerHost();
    }

    public void setMaxClientCnxnsPerHost(int max) {
        if (zks.serverCnxnFactory != null) {
            zks.serverCnxnFactory.setMaxClientCnxnsPerHost(max);
        }
        if (zks.secureServerCnxnFactory != null) {
            zks.secureServerCnxnFactory.setMaxClientCnxnsPerHost(max);
        }
    }

    public int getMinSessionTimeout() {
        return zks.getMinSessionTimeout();
    }

    public void setMinSessionTimeout(int min) {
        zks.setMinSessionTimeout(min);
    }

    public int getMaxSessionTimeout() {
        return zks.getMaxSessionTimeout();
    }

    public void setMaxSessionTimeout(int max) {
        zks.setMaxSessionTimeout(max);
    }

    public long getDataDirSize() {
        return zks.getDataDirSize();
    }

    public long getLogDirSize() {
        return zks.getLogDirSize();
    }

    public long getPacketsReceived() {
        return zks.serverStats().getPacketsReceived();
    }

    public long getPacketsSent() {
        return zks.serverStats().getPacketsSent();
    }

    public long getFsyncThresholdExceedCount() {
        return zks.serverStats().getFsyncThresholdExceedCount();
    }

    public void resetLatency() {
        zks.serverStats().resetLatency();
    }

    public void resetMaxLatency() {
        zks.serverStats().resetMaxLatency();
    }

    public void resetFsyncThresholdExceedCount() {
        zks.serverStats().resetFsyncThresholdExceedCount();
    }

    public void resetStatistics() {
        ServerStats serverStats = zks.serverStats();
        serverStats.resetRequestCounters();
        serverStats.resetLatency();
        serverStats.resetFsyncThresholdExceedCount();
    }

    public long getNumAliveConnections() {
        return zks.getNumAliveConnections();
    }

    @Override
    public String getSecureClientPort() {
        if (zks.secureServerCnxnFactory != null) {
            return Integer.toString(zks.secureServerCnxnFactory.getLocalPort());
        }
        return "";
    }

    @Override
    public String getSecureClientAddress() {
        if (zks.secureServerCnxnFactory != null) {
            return String.format("%s:%d",
                                 zks.secureServerCnxnFactory.getLocalAddress().getHostString(),
                                 zks.secureServerCnxnFactory.getLocalPort());
        }
        return "";
    }

    @Override
    public long getTxnLogElapsedSyncTime() {
        return zks.getTxnLogElapsedSyncTime();
    }

    @Override
    public int getJuteMaxBufferSize() {
        return BinaryInputArchive.maxBuffer;
    }

    @Override
    public int getLastClientResponseSize() {
        return zks.serverStats().getClientResponseStats().getLastBufferSize();
    }

    @Override
    public int getMinClientResponseSize() {
        return zks.serverStats().getClientResponseStats().getMinBufferSize();
    }

    @Override
    public int getMaxClientResponseSize() {
        return zks.serverStats().getClientResponseStats().getMaxBufferSize();
    }

    @Override
    public boolean getResponseCachingEnabled() {
        return zks.isResponseCachingEnabled();
    }

    @Override
    public void setResponseCachingEnabled(boolean isEnabled) {
        zks.setResponseCachingEnabled(isEnabled);
    }

    // Connection throttling settings
    ///////////////////////////////////////////////////////////////////////////

    public int getConnectionMaxTokens() {
        return zks.connThrottle().getMaxTokens();
    }

    public void setConnectionMaxTokens(int val) {
        zks.connThrottle().setMaxTokens(val);
    }

    ///////////////////////////////////////////////////////////////////////////

    public int getConnectionTokenFillTime() {
        return zks.connThrottle().getFillTime();
    }

    public void setConnectionTokenFillTime(int val) {
        zks.connThrottle().setFillTime(val);
    }

    ///////////////////////////////////////////////////////////////////////////

    public int getConnectionTokenFillCount() {
        return zks.connThrottle().getFillCount();
    }

    public void setConnectionTokenFillCount(int val) {
        zks.connThrottle().setFillCount(val);
    }

    ///////////////////////////////////////////////////////////////////////////

    public int getConnectionFreezeTime() {
        return zks.connThrottle().getFreezeTime();
    }

    public void setConnectionFreezeTime(int val) {
        zks.connThrottle().setFreezeTime(val);
    }

    ///////////////////////////////////////////////////////////////////////////

    public double getConnectionDropIncrease() {
        return zks.connThrottle().getDropIncrease();
    }

    public void setConnectionDropIncrease(double val) {
        zks.connThrottle().setDropIncrease(val);
    }

    ///////////////////////////////////////////////////////////////////////////

    public double getConnectionDropDecrease() {
        return zks.connThrottle().getDropDecrease();
    }

    public void setConnectionDropDecrease(double val) {
        zks.connThrottle().setDropDecrease(val);
    }

    ///////////////////////////////////////////////////////////////////////////

    public double getConnectionDecreaseRatio() {
        return zks.connThrottle().getDecreasePoint();
    }

    public void setConnectionDecreaseRatio(double val) {
        zks.connThrottle().setDecreasePoint(val);
    }

    ///////////////////////////////////////////////////////////////////////////

    public int getCommitProcMaxReadBatchSize() {
        return CommitProcessor.getMaxReadBatchSize();
    }

    public void setCommitProcMaxReadBatchSize(int size) {
        CommitProcessor.setMaxReadBatchSize(size);
    }

    ///////////////////////////////////////////////////////////////////////////

    public int getCommitProcMaxCommitBatchSize() {
        return CommitProcessor.getMaxCommitBatchSize();
    }

    public void setCommitProcMaxCommitBatchSize(int size) {
        CommitProcessor.setMaxCommitBatchSize(size);
    }

    ///////////////////////////////////////////////////////////////////////////

    @Override
    public long getFlushDelay() {
        return zks.getFlushDelay();
    }

    @Override
    public void setFlushDelay(long delay) {
        ZooKeeperServer.setFlushDelay(delay);
    }

    // Request throttling settings
    ///////////////////////////////////////////////////////////////////////////

    public int getThrottledOpWaitTime() {
        return ZooKeeperServer.getThrottledOpWaitTime();
    }

    public void setThrottledOpWaitTime(int val) {
        ZooKeeperServer.setThrottledOpWaitTime(val);
    }

    ///////////////////////////////////////////////////////////////////////////

    public int getRequestThrottleLimit() {
        return RequestThrottler.getMaxRequests();
    }

    public void setRequestThrottleLimit(int requests) {
        RequestThrottler.setMaxRequests(requests);
    }

    ///////////////////////////////////////////////////////////////////////////

    public int getRequestThrottleStallTime() {
        return RequestThrottler.getStallTime();
    }

    public void setRequestThrottleStallTime(int time) {
        RequestThrottler.setStallTime(time);
    }

    ///////////////////////////////////////////////////////////////////////////

    public boolean getRequestThrottleDropStale() {
        return RequestThrottler.getDropStaleRequests();
    }

    public void setRequestThrottleDropStale(boolean drop) {
        RequestThrottler.setDropStaleRequests(drop);
    }

    ///////////////////////////////////////////////////////////////////////////

    @Override
    public long getMaxWriteQueuePollTime() {
        return zks.getMaxWriteQueuePollTime();
    }

    @Override
    public void setMaxWriteQueuePollTime(long delay) {
        ZooKeeperServer.setMaxWriteQueuePollTime(delay);
    }

    public boolean getRequestStaleLatencyCheck() {
        return Request.getStaleLatencyCheck();
    }

    public void setRequestStaleLatencyCheck(boolean check) {
        Request.setStaleLatencyCheck(check);
    }

    ///////////////////////////////////////////////////////////////////////////

    @Override
    public int getMaxBatchSize() {
        return zks.getMaxBatchSize();
    }

    @Override
    public void setMaxBatchSize(int size) {
        ZooKeeperServer.setMaxBatchSize(size);
    }

    public boolean getRequestStaleConnectionCheck() {
        return Request.getStaleConnectionCheck();
    }

    public void setRequestStaleConnectionCheck(boolean check) {
        Request.setStaleConnectionCheck(check);
    }


    ///////////////////////////////////////////////////////////////////////////

    public int getLargeRequestMaxBytes() {
        return zks.getLargeRequestMaxBytes();
    }

    public void setLargeRequestMaxBytes(int bytes) {
        zks.setLargeRequestMaxBytes(bytes);
    }

    ///////////////////////////////////////////////////////////////////////////

    public int getLargeRequestThreshold() {
        return zks.getLargeRequestThreshold();
    }

    public void setLargeRequestThreshold(int threshold) {
        zks.setLargeRequestThreshold(threshold);
    }

    public int getMaxCnxns() {
        return ServerCnxnHelper.getMaxCnxns(zks.secureServerCnxnFactory, zks.serverCnxnFactory);
    }
}
