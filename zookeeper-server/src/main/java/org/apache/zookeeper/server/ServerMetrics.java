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
import org.apache.zookeeper.server.metric.AvgMinMaxCounterSet;
import org.apache.zookeeper.server.metric.AvgMinMaxPercentileCounter;
import org.apache.zookeeper.server.metric.AvgMinMaxPercentileCounterSet;
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
    READ_LATENCY(new AvgMinMaxPercentileCounter("readlatency")),

    /**
     * Stats for request that need quorum voting. Timing is the same as read
     * request. We only keep track of stats for request that originated from
     * this machine only.
     */
    UPDATE_LATENCY(new AvgMinMaxPercentileCounter("updatelatency")),

    /**
     * Stats for all quorum request. The timing start from when the leader
     * see the request until it reach the learner.
     */
    PROPAGATION_LATENCY(new AvgMinMaxPercentileCounter("propagation_latency")),
    PROPOSAL_LATENCY(new AvgMinMaxPercentileCounter("proposal_latency")),
    PROPOSAL_ACK_CREATION_LATENCY(new AvgMinMaxPercentileCounter("proposal_ack_creation_latency")),
    COMMIT_PROPAGATION_LATENCY(new AvgMinMaxPercentileCounter("commit_propagation_latency")),
    QUORUM_ACK_LATENCY(new AvgMinMaxPercentileCounter("quorum_ack_latency")),
    ACK_LATENCY(new AvgMinMaxPercentileCounterSet("ack_latency")),

    FOLLOWER_SYNC_TIME(new AvgMinMaxCounter("follower_sync_time")),
    ELECTION_TIME(new AvgMinMaxCounter("election_time")),
    UNAVAILABLE_TIME(new AvgMinMaxCounter("unavailable_time")),
    LEADER_UNAVAILABLE_TIME(new AvgMinMaxCounter("leader_unavailable_time")),
    LOOKING_COUNT(new SimpleCounter("looking_count")),
    DIFF_COUNT(new SimpleCounter("diff_count")),
    SNAP_COUNT(new SimpleCounter("snap_count")),
    PROPOSAL_COUNT(new SimpleCounter("proposal_count")),
    COMMIT_COUNT(new SimpleCounter("commit_count")),
    REVALIDATE_COUNT(new SimpleCounter("revalidate_count")),
    CONNECTION_REQUEST_COUNT(new SimpleCounter("connection_request_count")),
    CONNECTION_DROP_COUNT(new SimpleCounter("connection_drop_count")),
    CONNECTION_REVALIDATE_COUNT(new SimpleCounter("connection_revalidate_count")),

    // Expiry queue stats
    SESSIONLESS_CONNECTIONS_EXPIRED(new SimpleCounter("sessionless_connections_expired")),
    STALE_SESSIONS_EXPIRED(new SimpleCounter("stale_sessions_expired")),

    SYNC_PROCESSOR_QUEUE_TIME(new AvgMinMaxPercentileCounter("sync_processor_queue_time_ms")),
    SYNC_PROCESSOR_QUEUE_SIZE(new AvgMinMaxCounter("sync_processor_queue_size")),
    SYNC_PROCESSOR_QUEUED(new SimpleCounter("sync_processor_request_queued")),
    SYNC_PROCESSOR_REAL_QUEUE_TIME(new AvgMinMaxPercentileCounter("sync_processor_real_queue_time_ms")),
    SYNC_PROCESSOR_FLUSH_TIME(new AvgMinMaxPercentileCounter("sync_processor_queue_flush_time_ms")),

    BATCH_SIZE(new AvgMinMaxCounter("sync_processor_batch_size")),

    PREP_PROCESSOR_QUEUE_TIME(new AvgMinMaxPercentileCounter("prep_processor_queue_time_ms")),
    PREP_PROCESSOR_QUEUE_SIZE(new AvgMinMaxCounter("prep_processor_queue_size")),
    PREP_PROCESSOR_QUEUED(new SimpleCounter("prep_processor_request_queued")),

    /**
     * Time spent by a read request in the commit processor.
     */
    READ_COMMITPROC_TIME(new AvgMinMaxPercentileCounter("read_commitproc_time_ms")),

    /**
     * Time spent by a write request in the commit processor.
     */
    WRITE_COMMITPROC_TIME(new AvgMinMaxPercentileCounter("write_commitproc_time_ms")),

    /**
     * Time spent by a committed request, for a locally issued write, in the
     * commit processor.
     */
    LOCAL_WRITE_COMMITTED_TIME(new AvgMinMaxPercentileCounter("local_write_committed_time_ms")),

    /**
     * Time spent by a committed request for a write, issued by other server, in the
     * commit processor.
     */
    SERVER_WRITE_COMMITTED_TIME(new AvgMinMaxPercentileCounter("server_write_committed_time_ms")),

    /**
     * Time spent by the final processor. This is tracked in the commit processor.
     */
    READ_FINAL_PROC_TIME(new AvgMinMaxPercentileCounter("read_final_proc_time_ms")),
    WRITE_FINAL_PROC_TIME(new AvgMinMaxPercentileCounter("write_final_proc_time_ms")),

    /**
     * Fired watcher stats.
     */
    NODE_CREATED_WATCHER(new AvgMinMaxCounter("node_created_watch_count")),
    NODE_DELETED_WATCHER(new AvgMinMaxCounter("node_deleted_watch_count")),
    NODE_CHANGED_WATCHER(new AvgMinMaxCounter("node_changed_watch_count")),
    NODE_CHILDREN_WATCHER(new AvgMinMaxCounter("node_children_watch_count")),


    /*
     * Number of dead watchers in DeadWatcherListener
     */
    ADD_DEAD_WATCHER_STALL_TIME(new SimpleCounter("add_dead_watcher_stall_time")),
    DEAD_WATCHERS_QUEUED(new SimpleCounter("dead_watchers_queued")),
    DEAD_WATCHERS_CLEARED(new SimpleCounter("dead_watchers_cleared")),
    DEAD_WATCHERS_CLEANER_LATENCY(new AvgMinMaxPercentileCounter("dead_watchers_cleaner_latency")),

    /**
     * Learner handler quorum packet metrics.
     */
    LEARNER_HANDLER_QP_SIZE(new AvgMinMaxCounterSet("learner_handler_qp_size")),
    LEARNER_HANDLER_QP_TIME(new AvgMinMaxPercentileCounterSet("learner_handler_qp_time_ms")),

    WRITE_PER_NAMESPACE(new AvgMinMaxCounterSet("write_per_namespace")),
    READ_PER_NAMESPACE(new AvgMinMaxCounterSet("read_per_namespace")),

    UNRECOVERABLE_ERROR_COUNT(new SimpleCounter("unrecoverable_error_count")),

    /*
     * Number of requests that are in the session queue.
     */
    REQUESTS_IN_SESSION_QUEUE(new AvgMinMaxCounter("requests_in_session_queue")),
    PENDING_SESSION_QUEUE_SIZE(new AvgMinMaxCounter("pending_session_queue_size")),
    /*
     * Consecutive number of read requests that are in the session queue right after a commit request.
     */
    READS_AFTER_WRITE_IN_SESSION_QUEUE(new AvgMinMaxCounter("reads_after_write_in_session_queue")),
    READ_ISSUED_FROM_SESSION_QUEUE(new AvgMinMaxCounter("reads_issued_from_session_queue")),
    SESSION_QUEUES_DRAINED(new AvgMinMaxCounter("session_queues_drained")),

    TIME_WAITING_EMPTY_POOL_IN_COMMIT_PROCESSOR_READ(new AvgMinMaxCounter("time_waiting_empty_pool_in_commit_processor_read_ms")),
    TIME_WAITING_EMPTY_POOL_IN_COMMIT_PROCESSOR_WRITE(new AvgMinMaxCounter("time_waiting_empty_pool_in_commit_processor_write_ms")),

    CONCURRENT_REQUEST_PROCESSING_IN_COMMIT_PROCESSOR(new AvgMinMaxCounter("concurrent_request_processing_in_commit_processor")),

    READS_QUEUED_IN_COMMIT_PROCESSOR(new AvgMinMaxCounter("read_commit_proc_req_queued")),
    WRITES_QUEUED_IN_COMMIT_PROCESSOR(new AvgMinMaxCounter("write_commit_proc_req_queued")),
    COMMITS_QUEUED_IN_COMMIT_PROCESSOR(new AvgMinMaxCounter("commit_commit_proc_req_queued")),
    COMMITS_QUEUED(new SimpleCounter("request_commit_queued")),
    READS_ISSUED_IN_COMMIT_PROC(new AvgMinMaxCounter("read_commit_proc_issued")),
    WRITES_ISSUED_IN_COMMIT_PROC(new AvgMinMaxCounter("write_commit_proc_issued")),

    CLOSE_SESSION_PREP_TIME(new AvgMinMaxPercentileCounter("close_session_prep_time")),

    OUTSTANDING_CHANGES_QUEUED(new SimpleCounter("outstanding_changes_queued")),
    OUTSTANDING_CHANGES_REMOVED(new SimpleCounter("outstanding_changes_removed")),

    STARTUP_TXNS_LOADED(new AvgMinMaxCounter("startup_txns_loaded")),
    STARTUP_TXNS_LOAD_TIME(new AvgMinMaxCounter("startup_txns_load_time")),
    STARTUP_SNAP_LOAD_TIME(new AvgMinMaxCounter("startup_snap_load_time")),

    PREP_PROCESS_TIME(new AvgMinMaxCounter("prep_process_time")),
    SYNC_PROCESS_TIME(new AvgMinMaxCounter("sync_process_time")),
    COMMIT_PROCESS_TIME(new AvgMinMaxCounter("commit_process_time")),

    LEARNER_PROPOSAL_RECEIVED_COUNT(new SimpleCounter("learner_proposal_received_count")),
    LEARNER_COMMIT_RECEIVED_COUNT(new SimpleCounter("learner_commit_received_count")),

    QUIT_LEADING_DUE_TO_DISLOYAL_VOTER(new SimpleCounter("quit_leading_due_to_disloyal_voter")),
    BYTES_RECEIVED_COUNT(new SimpleCounter("bytes_received_count")),

    RESPONSE_PACKET_CACHE_HITS(new SimpleCounter("response_packet_cache_hits")),
    RESPONSE_PACKET_CACHE_MISSING(new SimpleCounter("response_packet_cache_misses"));
  
    private final Metric metric;

    ServerMetrics(Metric metric) {
        this.metric = metric;
    }

    public void add(long value) {
        metric.add(value);
    }

    public void add(int key, long value) {
        metric.add(key, value);
    }

    public void add(String key, long value) {
        metric.add(key, value);
    }

    public void reset() {
        metric.reset();
    }

    Map<String, Object> getValues() {
        return metric.values();
    }

    static public Map<String, Object> getAllValues() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
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
