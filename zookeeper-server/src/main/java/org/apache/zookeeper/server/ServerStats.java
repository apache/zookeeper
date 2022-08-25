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

import java.util.concurrent.atomic.AtomicLong;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.server.metric.AvgMinMaxCounter;
import org.apache.zookeeper.server.quorum.BufferStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Basic Server Statistics
 */
public class ServerStats {

    private static final Logger LOG = LoggerFactory.getLogger(ServerStats.class);

    private AtomicLong packetsSent = new AtomicLong();
    private AtomicLong packetsReceived = new AtomicLong();

    private final AvgMinMaxCounter requestLatency = new AvgMinMaxCounter("request_latency");

    private final AtomicLong fsyncThresholdExceedCount = new AtomicLong(0);

    private final BufferStats clientResponseStats = new BufferStats();

    private long totalReadCount = 0;
    private long totalWriteCount = 0;

    private long readCount = 0;
    private long totalReadLatency = 0;
    private long maxReadLatency;
    private long minReadLatency = Long.MAX_VALUE;
    private long writeCount = 0;
    private long totalWriteLatency = 0;
    private long maxWriteLatency;
    private long minWriteLatency = Long.MAX_VALUE;

    private long connectionCreateCount = 0;
    private long connectionCloseCount = 0;
    private long sessionCloseCount = 0;
    private long sessionCreateCount = 0;

    private AtomicLong nonMTLSRemoteConnCntr = new AtomicLong(0);

    private AtomicLong nonMTLSLocalConnCntr = new AtomicLong(0);

    private AtomicLong authFailedCntr = new AtomicLong(0);

    private final Provider provider;
    private final long startTime = Time.currentElapsedTime();

    public interface Provider {

        long getOutstandingRequests();
        long getLastProcessedZxid();
        String getState();
        int getNumAliveConnections();
        long getDataDirSize();
        long getLogDirSize();

    }

    public ServerStats(Provider provider) {
        this.provider = provider;
    }

    // getters
    public long getMinLatency() {
        return requestLatency.getMin();
    }

    public double getAvgLatency() {
        return requestLatency.getAvg();
    }

    public long getMaxLatency() {
        return requestLatency.getMax();
    }

    public synchronized long getMinReadLatency() {
        return minReadLatency == Long.MAX_VALUE ? 0 : minReadLatency;
    }

    public synchronized long getMinWriteLatency() {
        return minWriteLatency == Long.MAX_VALUE ? 0 : minWriteLatency;
    }

    public synchronized long getAvgReadLatency() {
        if (readCount != 0) {
            return totalReadLatency / readCount;
        }
        return 0;
    }

    public synchronized long getAvgWriteLatency() {
        if (writeCount != 0) {
            return totalWriteLatency / writeCount;
        }
        return 0;
    }

    public synchronized long getMaxReadLatency() {
        return maxReadLatency;
    }

    public synchronized long getMaxWriteLatency() {
        return maxWriteLatency;
    }

    public synchronized long getReadCount() {
        return totalReadCount;
    }

    public synchronized long getWriteCount() {
        return totalWriteCount;
    }

    public long getOutstandingRequests() {
        return provider.getOutstandingRequests();
    }

    public long getLastProcessedZxid() {
        return provider.getLastProcessedZxid();
    }

    public long getDataDirSize() {
        return provider.getDataDirSize();
    }

    public long getLogDirSize() {
        return provider.getLogDirSize();
    }

    public long getPacketsReceived() {
        return packetsReceived.get();
    }

    public long getPacketsSent() {
        return packetsSent.get();
    }

    public String getServerState() {
        return provider.getState();
    }

    /** The number of client connections alive to this server */
    public int getNumAliveClientConnections() {
        return provider.getNumAliveConnections();
    }

    public long getUptime() {
        return Time.currentElapsedTime() - startTime;
    }

    public boolean isProviderNull() {
        return provider == null;
    }

    public synchronized long getConnectionCreateCount() {
        return connectionCreateCount;
    }

    public synchronized long getConnectionCloseCount() {
        return connectionCloseCount;
    }

    public synchronized long getSessionCloseCount() {
        return sessionCloseCount;
    }

    public synchronized long getSessionCreateCount() {
        return sessionCreateCount;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Latency min/avg/max: " + getMinLatency() + "/" + getAvgLatency() + "/" + getMaxLatency() + "\n");
        sb.append("Received: " + getPacketsReceived() + "\n");
        sb.append("Sent: " + getPacketsSent() + "\n");
        sb.append("Connections: " + getNumAliveClientConnections() + "\n");

        if (provider != null) {
            sb.append("Outstanding: " + getOutstandingRequests() + "\n");
            sb.append("Zxid: 0x" + Long.toHexString(getLastProcessedZxid()) + "\n");
        }
        sb.append("Mode: " + getServerState() + "\n");
        return sb.toString();
    }

    /**
     * Update request statistic. This should only be called from a request
     * that originated from that machine.
     */
    public synchronized void updateLatency(int type, Request request, long currentTime) {
        long latency = currentTime - request.createTime;
        if (latency < 0) {
            return;
        }
        requestLatency.addDataPoint(latency);
        if (request.getHdr() != null) {
            // Only quorum request should have header
            ServerMetrics.getMetrics().UPDATE_LATENCY.add(latency);
        } else {
            // All read request should goes here
            ServerMetrics.getMetrics().READ_LATENCY.add(latency);
        }

        switch (getType(type)) {
            case READ:
                totalReadLatency += latency;
                totalReadCount++;
                readCount++;
                if (latency < minReadLatency) {
                    minReadLatency = latency;
                }
                if (latency > maxReadLatency) {
                    maxReadLatency = latency;
                }
                break;
            case WRITE:
                totalWriteLatency += latency;
                totalWriteCount++;
                writeCount++;
                if (latency < minWriteLatency) {
                    minWriteLatency = latency;
                }
                if (latency > maxWriteLatency) {
                    maxWriteLatency = latency;
                }
                break;
            default:
                // ignore the other types
                break;
        }
    }

    private enum OpType {
        READ,
        WRITE,
        DEFAULT
    }

    private OpType getType(int type) {
        switch (type) {
            case ZooDefs.OpCode.exists:
            case ZooDefs.OpCode.getACL:
            case ZooDefs.OpCode.getChildren:
            case ZooDefs.OpCode.getChildren2:
            case ZooDefs.OpCode.getData:
                return OpType.READ;
            case ZooDefs.OpCode.create:
            case ZooDefs.OpCode.delete:
            case ZooDefs.OpCode.setACL:
            case ZooDefs.OpCode.setData:
            case ZooDefs.OpCode.multi:
                return OpType.WRITE;
            case ZooDefs.OpCode.setWatches:
            case ZooDefs.OpCode.auth:
            case ZooDefs.OpCode.sasl:
            case ZooDefs.OpCode.error:
            case ZooDefs.OpCode.notification:
            case ZooDefs.OpCode.closeSession:
            case ZooDefs.OpCode.createSession:
            case ZooDefs.OpCode.check:
            case ZooDefs.OpCode.sync:
            case ZooDefs.OpCode.ping:
            default:
                return OpType.DEFAULT;
        }
    }

    public synchronized void resetLatency() {
        requestLatency.reset();
        totalReadLatency = 0;
        totalWriteLatency = 0;
        readCount = 0;
        writeCount = 0;
        maxWriteLatency = 0;
        minWriteLatency = Long.MAX_VALUE;
        maxReadLatency = 0;
        minReadLatency = Long.MAX_VALUE;
    }

    public synchronized void resetMaxLatency() {
        requestLatency.resetMax();
        maxReadLatency = getMinReadLatency();
        maxWriteLatency = getMinWriteLatency();
    }

    public void incrementPacketsReceived() {
        packetsReceived.incrementAndGet();
    }

    public void incrementPacketsSent() {
        packetsSent.incrementAndGet();
    }

    public synchronized void resetRequestCounters() {
        packetsReceived.set(0);
        packetsSent.set(0);
        totalReadCount = 0;
        totalWriteCount = 0;
    }

    public long getFsyncThresholdExceedCount() {
        return fsyncThresholdExceedCount.get();
    }

    public void incrementFsyncThresholdExceedCount() {
        fsyncThresholdExceedCount.incrementAndGet();
    }

    public void resetFsyncThresholdExceedCount() {
        fsyncThresholdExceedCount.set(0);
    }

    public synchronized void incrementSessionCreated() {
        sessionCreateCount++;
    }
    public synchronized void incrementSessionClosed() {
        sessionCloseCount++;
    }
    public synchronized void incrementConnectionCreated() {
        connectionCreateCount++;
    }
    public synchronized void incrementConnectionClosed() {
        connectionCloseCount++;
    }
    public synchronized void resetConnectionCounters() {
        sessionCreateCount = 0;
        sessionCloseCount = 0;
        connectionCreateCount = 0;
        connectionCloseCount = 0;
    }

    public long getNonMTLSLocalConnCount() {
        return nonMTLSLocalConnCntr.get();
    }

    public void incrementNonMTLSLocalConnCount() {
        nonMTLSLocalConnCntr.incrementAndGet();
    }

    public void resetNonMTLSLocalConnCount() {
        nonMTLSLocalConnCntr.set(0);
    }

    public long getNonMTLSRemoteConnCount() {
        return nonMTLSRemoteConnCntr.get();
    }

    public void incrementNonMTLSRemoteConnCount() {
        nonMTLSRemoteConnCntr.incrementAndGet();
    }

    public void resetNonMTLSRemoteConnCount() {
        nonMTLSRemoteConnCntr.set(0);
    }

    public long getAuthFailedCount() {
        return authFailedCntr.get();
    }

    public void incrementAuthFailedCount() {
        authFailedCntr.incrementAndGet();
    }

    public void resetAuthFailedCount() {
        authFailedCntr.set(0);
    }

    public void reset() {
        resetLatency();
        resetRequestCounters();
        resetConnectionCounters();
        clientResponseStats.reset();
        ServerMetrics.getMetrics().resetAll();
    }

    public void updateClientResponseSize(int size) {
        clientResponseStats.setLastBufferSize(size);
    }

    public BufferStats getClientResponseStats() {
        return clientResponseStats;
    }

}
