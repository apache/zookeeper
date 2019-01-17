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


import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.server.metric.AvgMinMaxCounter;
import org.apache.zookeeper.server.quorum.BufferStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Basic Server Statistics
 */
public class ServerStats {
    private static final Logger LOG = LoggerFactory.getLogger(ServerStats.class);

    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong packetsReceived = new AtomicLong();

    private final AvgMinMaxCounter requestLatency = new AvgMinMaxCounter("request_latency");

    private AtomicLong fsyncThresholdExceedCount = new AtomicLong(0);

    private final BufferStats clientResponseStats = new BufferStats();

    // Request state counters
    public enum RequestState {
        QUEUED(0, "queued"),
        ISSUED(1, "issued"),
        COMPLETE(2, "complete"),
        DROPPED(3, "dropped");

        private final int code;
        private final String name;

        RequestState(int code, String name) {
            this.code = code;
            this.name = name;
        }
    };

    public enum RequestType {
        ALL(0, "all"),
        CREATE_SESSION(1, "create_session"),
        CLOSE_SESSION(2, "close_session"),
        WRITE(3, "write"),
        SET_WATCHES(4, "set_watches"),
        READ(5, "read"),
        SYNC(6, "sync"),
        AUTH(7, "auth"),
        OTHER(8, "other");

        private final int code;
        private final String name;

        RequestType(int code, String name) {
            this.code = code;
            this.name = name;
        }
    };

    public final AtomicLongArray requestStates =
        new AtomicLongArray(RequestState.values().length * RequestType.values().length);

    private final Provider provider;
    private final long startTime = Time.currentElapsedTime();
    private final AtomicLong authFailed = new AtomicLong();

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
    public long getMinLatency() {
        return requestLatency.getMin();
    }

    public double getAvgLatency() {
        return requestLatency.getAvg();
    }

    public long getMaxLatency() {
        return requestLatency.getMax();
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

    @Override
    public String toString(){
        StringBuilder sb = new StringBuilder();
        sb.append("Latency min/avg/max: " + getMinLatency() + "/"
                + getAvgLatency() + "/" + getMaxLatency() + "\n");
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

    /**
     * Update request statistic. This should only be called from a request
     * that originated from that machine.
     */
    public void updateLatency(Request request, long currentTime) {
        long latency = currentTime - request.createTime;
        if (latency < 0) {
            return;
        }
        requestLatency.addDataPoint(latency);
        if (request.getHdr() != null) {
            // Only quorum request should have header
            ServerMetrics.UPDATE_LATENCY.add(latency);
        } else {
            // All read request should goes here
            ServerMetrics.READ_LATENCY.add(latency);
        }
    }

    public void resetLatency() {
        requestLatency.reset();
    }

    public void resetMaxLatency() {
        requestLatency.resetMax();
    }

    public void incrementPacketsReceived() {
        packetsReceived.incrementAndGet();
    }

    public void incrementPacketsSent() {
        packetsSent.incrementAndGet();
    }

    private int requestStateIndex(RequestState state, RequestType type) {
        return (type.code * RequestState.values().length) + state.code;
    }

    public void incrementRequestState(Request request, RequestState state) {
        RequestType type;
        switch (request.type) {
            case OpCode.createSession:
                type = RequestType.CREATE_SESSION;
                break;
            case OpCode.closeSession:
                type = RequestType.CLOSE_SESSION;
                break;
            case OpCode.multi:
            case OpCode.create:
            case OpCode.create2:
            case OpCode.delete:
            case OpCode.setData:
            case OpCode.setACL:
                type = RequestType.WRITE;
                break;
            case OpCode.setWatches:
                type = RequestType.SET_WATCHES;
                break;
            case OpCode.getData:
            case OpCode.getChildren:
            case OpCode.getChildren2:
            case OpCode.exists:
                type = RequestType.READ;
                break;
            case OpCode.sync:
                type = RequestType.SYNC;
                break;
            case OpCode.auth:
                type = RequestType.AUTH;
                break;
            default:
                type = RequestType.OTHER;
        }
        requestStates.incrementAndGet(requestStateIndex(state, RequestType.ALL));
        requestStates.incrementAndGet(requestStateIndex(state, type));
    }

    public Map<String, Long> getRequestStates() {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        for (RequestType type : RequestType.values()) {
            for (RequestState state : RequestState.values()) {
                m.put("request_" + type.name + "_" + state.name,
                        requestStates.get(requestStateIndex(state, type)));
            }
        }
        return m;
    }

    public void resetRequestCounters(){
        packetsReceived.set(0);
        packetsSent.set(0);
        authFailed.set(0);

        for (int idx = 0; idx < requestStates.length(); ++idx) {
            requestStates.set(idx, 0);
        }
    }

    public void incrementAuthFailed() {
        authFailed.incrementAndGet();
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

    synchronized public void reset() {
        resetLatency();
        resetRequestCounters();
        clientResponseStats.reset();
        ServerMetrics.resetAll();
    }

    public void updateClientResponseSize(int size) {
        clientResponseStats.setLastBufferSize(size);
    }

    public BufferStats getClientResponseStats() {
        return clientResponseStats;
    }
}
