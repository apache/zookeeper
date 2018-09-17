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

import org.apache.zookeeper.server.metric.AvgMinMaxCounter;
import org.apache.zookeeper.server.metric.Metric;
import org.apache.zookeeper.server.metric.SimpleCounter;

import java.util.LinkedHashMap;
import java.util.Map;

public enum ServerMetrics {
    /**
     * Txnlog fsync time
     */
    FSYNC_TIME(new AvgMinMaxCounter("fsynctime")),

    /**
     * Snapshot writing time
     */
    SNAPSHOT_TIME(new AvgMinMaxCounter("snapshottime")),

    /**
     * Db init time (snapshot loading + txnlog replay)
     */
    DB_INIT_TIME(new AvgMinMaxCounter("dbinittime")),

    /**
     * Stats for read request. The timing start from when the server see the
     * request until it leave final request processor.
     */
    READ_LATENCY(new AvgMinMaxCounter("readlatency")),

    /**
     * Stats for request that need quorum voting. Timing is the same as read
     * request. We only keep track of stats for request that originated from
     * this machine only.
     */
    UPDATE_LATENCY(new AvgMinMaxCounter("updatelatency")),

    /**
     * Stats for all quorum request. The timing start from when the leader
     * see the request until it reach the learner.
     */
    PROPAGATION_LATENCY(new AvgMinMaxCounter("propagation_latency")),

    FOLLOWER_SYNC_TIME(new AvgMinMaxCounter("follower_sync_time")),
    ELECTION_TIME(new AvgMinMaxCounter("election_time")),
    LOOKING_COUNT(new SimpleCounter("looking_count")),
    DIFF_COUNT(new SimpleCounter("diff_count")),
    SNAP_COUNT(new SimpleCounter("snap_count")),
    COMMIT_COUNT(new SimpleCounter("commit_count")),
    CONNECTION_REQUEST_COUNT(new SimpleCounter("connection_request_count")),
    BYTES_RECEIVED_COUNT(new SimpleCounter("bytes_received_count"));

    private final Metric metric;

    ServerMetrics(Metric metric) {
        this.metric = metric;
    }

    public void add(long value) {
        metric.add(value);
    }

    public void reset() {
        metric.reset();
    }

    Map<String, Long> getValues() {
        return metric.values();
    }

    static public Map<String, Long> getAllValues() {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        for (ServerMetrics metric : ServerMetrics.values()) {
            m.putAll(metric.getValues());
        }
        return m;
    }

    static public void resetAll() {
        for (ServerMetrics metric : ServerMetrics.values()) {
            metric.reset();
        }
    }
}
