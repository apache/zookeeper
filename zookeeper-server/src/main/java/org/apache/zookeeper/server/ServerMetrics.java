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

import org.apache.zookeeper.metrics.Counter;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.metrics.MetricsContext.DetailLevel;

import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.Summary;
import org.apache.zookeeper.metrics.SummarySet;
import org.apache.zookeeper.metrics.impl.DefaultMetricsProvider;
import org.apache.zookeeper.metrics.impl.NullMetricsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(ServerMetrics.class);

    /**
     * Dummy instance useful for tests.
     */
    public static final ServerMetrics NULL_METRICS
            = new ServerMetrics(NullMetricsProvider.INSTANCE);

    /**
     * Dummy instance useful for tests.
     */
    public static final ServerMetrics DEFAULT_METRICS_FOR_TESTS
            = new ServerMetrics(new DefaultMetricsProvider());

    /**
     * Real instance used for tracking server side metrics. The final value is
     * assigned after the {@link MetricsProvider} bootstrap.
     */
    private static volatile ServerMetrics CURRENT = DEFAULT_METRICS_FOR_TESTS;

    /**
     * Access current ServerMetrics.
     *
     * @return a reference to the current Metrics
     */
    public static ServerMetrics getMetrics() {
        return CURRENT;
    }

    public static void metricsProviderInitialized(MetricsProvider metricsProvider) {
        LOG.info("ServerMetrics initialized with provider {}", metricsProvider);
        CURRENT = new ServerMetrics(metricsProvider);
    }

    private ServerMetrics(MetricsProvider metricsProvider) {
        this.metricsProvider = metricsProvider;
        MetricsContext metricsContext = this.metricsProvider.getRootContext();

        FSYNC_TIME = metricsContext.getSummary("fsynctime", DetailLevel.BASIC);

        SNAPSHOT_TIME = metricsContext.getSummary("snapshottime", DetailLevel.BASIC);
        DB_INIT_TIME = metricsContext.getSummary("dbinittime", DetailLevel.BASIC);
        READ_LATENCY = metricsContext.getSummary("readlatency", DetailLevel.ADVANCED);
        UPDATE_LATENCY = metricsContext.getSummary("updatelatency", DetailLevel.ADVANCED);
        PROPAGATION_LATENCY = metricsContext.getSummary("propagation_latency", DetailLevel.ADVANCED);
        FOLLOWER_SYNC_TIME = metricsContext.getSummary("follower_sync_time", DetailLevel.BASIC);
        ELECTION_TIME = metricsContext.getSummary("election_time", DetailLevel.BASIC);
        LOOKING_COUNT = metricsContext.getCounter("looking_count");
        DIFF_COUNT = metricsContext.getCounter("diff_count");
        SNAP_COUNT = metricsContext.getCounter("snap_count");
        COMMIT_COUNT = metricsContext.getCounter("commit_count");
        CONNECTION_REQUEST_COUNT = metricsContext.getCounter("connection_request_count");
        CONNECTION_TOKEN_DEFICIT = metricsContext.getSummary("connection_token_deficit", DetailLevel.BASIC);
        CONNECTION_REJECTED = metricsContext.getCounter("connection_rejected");

        WRITE_PER_NAMESPACE = metricsContext.getSummarySet("write_per_namespace", DetailLevel.BASIC);
        READ_PER_NAMESPACE = metricsContext.getSummarySet("read_per_namespace", DetailLevel.BASIC);

        BYTES_RECEIVED_COUNT = metricsContext.getCounter("bytes_received_count");
        UNRECOVERABLE_ERROR_COUNT = metricsContext.getCounter("unrecoverable_error_count");

        NODE_CREATED_WATCHER = metricsContext.getSummary("node_created_watch_count", DetailLevel.BASIC);
        NODE_DELETED_WATCHER = metricsContext.getSummary("node_deleted_watch_count", DetailLevel.BASIC);
        NODE_CHANGED_WATCHER = metricsContext.getSummary("node_changed_watch_count", DetailLevel.BASIC);
        NODE_CHILDREN_WATCHER = metricsContext.getSummary("node_children_watch_count", DetailLevel.BASIC);


        /*
     * Number of dead watchers in DeadWatcherListener
         */
        ADD_DEAD_WATCHER_STALL_TIME = metricsContext.getCounter("add_dead_watcher_stall_time");
        DEAD_WATCHERS_QUEUED = metricsContext.getCounter("dead_watchers_queued");
        DEAD_WATCHERS_CLEARED = metricsContext.getCounter("dead_watchers_cleared");
        DEAD_WATCHERS_CLEANER_LATENCY = metricsContext.getSummary("dead_watchers_cleaner_latency", DetailLevel.ADVANCED);

        RESPONSE_PACKET_CACHE_HITS = metricsContext.getCounter("response_packet_cache_hits");
        RESPONSE_PACKET_CACHE_MISSING = metricsContext.getCounter("response_packet_cache_misses");

        ENSEMBLE_AUTH_SUCCESS = metricsContext.getCounter("ensemble_auth_success");

        ENSEMBLE_AUTH_FAIL = metricsContext.getCounter("ensemble_auth_fail");

        ENSEMBLE_AUTH_SKIP = metricsContext.getCounter("ensemble_auth_skip");

    }

    /**
     * Txnlog fsync time
     */
    public final Summary FSYNC_TIME;

    /**
     * Snapshot writing time
     */
    public final Summary SNAPSHOT_TIME;

    /**
     * Db init time (snapshot loading + txnlog replay)
     */
    public final Summary DB_INIT_TIME;

    /**
     * Stats for read request. The timing start from when the server see the
     * request until it leave final request processor.
     */
    public final Summary READ_LATENCY;

    /**
     * Stats for request that need quorum voting. Timing is the same as read
     * request. We only keep track of stats for request that originated from
     * this machine only.
     */
    public final Summary UPDATE_LATENCY;

    /**
     * Stats for all quorum request. The timing start from when the leader see
     * the request until it reach the learner.
     */
    public final Summary PROPAGATION_LATENCY;

    public final Summary FOLLOWER_SYNC_TIME;

    public final Summary ELECTION_TIME;

    public final Counter LOOKING_COUNT;
    public final Counter DIFF_COUNT;
    public final Counter SNAP_COUNT;
    public final Counter COMMIT_COUNT;
    public final Counter CONNECTION_REQUEST_COUNT;
    // Connection throttling related
    public final Summary CONNECTION_TOKEN_DEFICIT;
    public final Counter CONNECTION_REJECTED;

    public final Counter UNRECOVERABLE_ERROR_COUNT;
    public final SummarySet WRITE_PER_NAMESPACE;
    public final SummarySet READ_PER_NAMESPACE;
    public final Counter BYTES_RECEIVED_COUNT;

    PREP_PROCESSOR_QUEUE_TIME(new AvgMinMaxPercentileCounter("prep_processor_queue_time_ms")),
    PREP_PROCESSOR_QUEUE_SIZE(new AvgMinMaxCounter("prep_processor_queue_size")),
    PREP_PROCESSOR_QUEUED(new SimpleCounter("prep_processor_request_queued")),
    OUTSTANDING_CHANGES_QUEUED(new SimpleCounter("outstanding_changes_queued")),
    OUTSTANDING_CHANGES_REMOVED(new SimpleCounter("outstanding_changes_removed")),
    PREP_PROCESS_TIME(new AvgMinMaxCounter("prep_process_time")),
    CLOSE_SESSION_PREP_TIME(new AvgMinMaxPercentileCounter("close_session_prep_time")),


    /**
     * Fired watcher stats.
     */
    public final Summary NODE_CREATED_WATCHER;
    public final Summary NODE_DELETED_WATCHER;
    public final Summary NODE_CHANGED_WATCHER;
    public final Summary NODE_CHILDREN_WATCHER;

    /*
     * Number of dead watchers in DeadWatcherListener
     */
    public final Counter ADD_DEAD_WATCHER_STALL_TIME;
    public final Counter DEAD_WATCHERS_QUEUED;
    public final Counter DEAD_WATCHERS_CLEARED;
    public final Summary DEAD_WATCHERS_CLEANER_LATENCY;
    public final Counter RESPONSE_PACKET_CACHE_HITS;
    public final Counter RESPONSE_PACKET_CACHE_MISSING;

    /*
     * Number of successful matches of expected ensemble name in EnsembleAuthenticationProvider.
     */
    public final Counter ENSEMBLE_AUTH_SUCCESS;

    /*
     * Number of unsuccessful matches of expected ensemble name in EnsembleAuthenticationProvider.
     */
    public final Counter ENSEMBLE_AUTH_FAIL;

    /*
     * Number of client auth requests with no ensemble set in EnsembleAuthenticationProvider.
     */
    public final Counter ENSEMBLE_AUTH_SKIP;

    private final MetricsProvider metricsProvider;

    public void resetAll() {
        metricsProvider.resetAllValues();
    }

    public MetricsProvider getMetricsProvider() {
        return metricsProvider;
    }

}
