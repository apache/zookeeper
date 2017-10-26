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



import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Basic Server Statistics
 */
public class ServerStats {
    private static final Logger LOG = LoggerFactory.getLogger(ServerStats.class);

    private long packetsSent;
    private long packetsReceived;
    private long maxLatency;
    private long minLatency = Long.MAX_VALUE;
    private long totalLatency = 0;
    private long count = 0;
    private long numRequestsAboveThresholdTime = 0;

    final static long requestWarnThresholdMs = QuorumPeerConfig.getRequestWarnResponseThresholdMs();
    final static Timer timer = new Timer();
    final static AtomicReference<Boolean> waitForLoggingWarnThresholdMsg = new AtomicReference<>(false);
    private long startCount;
    private int delayTimeForLoggingWarnThresholdMsg = 60000;

    private final Provider provider;

    public interface Provider {
        public long getOutstandingRequests();
        public long getLastProcessedZxid();
        public String getState();
        public int getNumAliveConnections();
        public long getDataDirSize();
        public long getLogDirSize();
    }
    
    public ServerStats(Provider provider) {
        this.provider = provider;
    }
    
    // getters
    synchronized public long getMinLatency() {
        return minLatency == Long.MAX_VALUE ? 0 : minLatency;
    }

    synchronized public long getAvgLatency() {
        if (count != 0) {
            return totalLatency / count;
        }
        return 0;
    }

    synchronized public long getMaxLatency() {
        return maxLatency;
    }

    public long getOutstandingRequests() {
        return provider.getOutstandingRequests();
    }
    
    public long getLastProcessedZxid(){
        return provider.getLastProcessedZxid();
    }

    public long getDataDirSize() {
        return provider.getDataDirSize();
    }

    public long getLogDirSize() {
        return provider.getLogDirSize();
    }
    
    synchronized public long getPacketsReceived() {
        return packetsReceived;
    }

    synchronized public long getPacketsSent() {
        return packetsSent;
    }

    synchronized public long getNumRequestsAboveThresholdTime() {
        return numRequestsAboveThresholdTime;
    }

    public String getServerState() {
        return provider.getState();
    }
    
    /** The number of client connections alive to this server */
    public int getNumAliveClientConnections() {
    	return provider.getNumAliveConnections();
    }

    public boolean isProviderNull() {
        return provider == null;
    }

    @Override
    public String toString(){
        StringBuilder sb = new StringBuilder();
        sb.append("Latency min/avg/max: " + getMinLatency() + "/"
                + getAvgLatency() + "/" + getMaxLatency() + "\n");
        sb.append("Num Requests that exceeded threshold latency: " + getNumRequestsAboveThresholdTime() + "\n");
        sb.append("Received: " + getPacketsReceived() + "\n");
        sb.append("Sent: " + getPacketsSent() + "\n");
        sb.append("Connections: " + getNumAliveClientConnections() + "\n");

        if (provider != null) {
            sb.append("Outstanding: " + getOutstandingRequests() + "\n");
            sb.append("Zxid: 0x"+ Long.toHexString(getLastProcessedZxid())+ "\n");
        }
        sb.append("Mode: " + getServerState() + "\n");
        return sb.toString();
    }

    synchronized void incNumRequestsAboveThresholdTime() {
        numRequestsAboveThresholdTime++;
    }

    // mutators
    synchronized void updateLatency(long requestCreateTime) {
        long latency = Time.currentElapsedTime() - requestCreateTime;
        totalLatency += latency;
        count++;
        if (latency < minLatency) {
            minLatency = latency;
        }
        if (latency > maxLatency) {
            maxLatency = latency;
        }
    }
    synchronized public void resetLatency(){
        totalLatency = 0;
        count = 0;
        maxLatency = 0;
        minLatency = Long.MAX_VALUE;
    }
    synchronized public void resetMaxLatency(){
        maxLatency = getMinLatency();
    }
    synchronized public void incrementPacketsReceived() {
        packetsReceived++;
    }
    synchronized public void incrementPacketsSent() {
        packetsSent++;
    }
    synchronized public void resetRequestCounters(){
        packetsReceived = 0;
        packetsSent = 0;
    }
    synchronized public void resetNumRequestsAboveThresholdTime() {
        numRequestsAboveThresholdTime = 0;
    }
    synchronized public void reset() {
        resetLatency();
        resetRequestCounters();
        resetNumRequestsAboveThresholdTime();
    }

    public void checkLatency(final ZooKeeperServer zks, Request request) {
        long requestLatency = Time.currentElapsedTime() - request.createTime;
        boolean enabledAndAboveThreshold = (requestWarnThresholdMs == 0) ||
                (requestWarnThresholdMs > -1 && requestLatency > requestWarnThresholdMs);
        if (enabledAndAboveThreshold) {
            zks.serverStats().incNumRequestsAboveThresholdTime();

            // Try acquiring lock only if not waiting
            boolean success = waitForLoggingWarnThresholdMsg.compareAndSet(Boolean.FALSE, Boolean.TRUE);
            if(success) {
                LOG.warn("Request {} exceeded threshold. Took {} ms", request, requestLatency);
                startCount = zks.serverStats().getNumRequestsAboveThresholdTime();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        long count = zks.serverStats().getNumRequestsAboveThresholdTime() - startCount;
                        LOG.warn("Number of requests that exceeded {} ms in past {} ms: {}",
                                requestWarnThresholdMs, delayTimeForLoggingWarnThresholdMsg, count);
                        waitForLoggingWarnThresholdMsg.set(Boolean.FALSE);
                    }
                }, delayTimeForLoggingWarnThresholdMsg);
            }
        }
    }

    public void setDelayTimeForLoggingWarnThresholdMsg(int delay) {
        this.delayTimeForLoggingWarnThresholdMsg = delay;
    }

    public void setWaitForLoggingWarnThresholdMsgToFalse() {
        ServerStats.waitForLoggingWarnThresholdMsg.set(Boolean.FALSE);
    }

}
