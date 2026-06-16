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
package org.apache.zookeeper.metrics.timeline;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a point-in-time snapshot of ZooKeeper metrics.
 *
 * <p>This class is a data transfer object that captures metric values at a specific
 * timestamp for export to Timeline/Ambari Metrics Collector. It contains three types
 * of metrics:</p>
 * <ul>
 *   <li><b>Counters</b> - Monotonically increasing values (e.g., request_count)</li>
 *   <li><b>Gauges</b> - Current values that can go up or down (e.g., num_alive_connections)</li>
 *   <li><b>Summaries</b> - Computed statistics like avg, min, max, percentiles (e.g., latency_avg)</li>
 * </ul>
 *
 * <p>Instances of this class are immutable after creation and are sent to the
 * Timeline sink for persistence and visualization.</p>
 *
 * @see TimelineMetricsProvider
 * @see TimelineMetricsSink
 */
public class MetricSnapshot {

    private final long timestamp;
    private final String hostname;
    private final String appId;

    // Separate collections for different metric types
    private final Map<String, Long> counters = new HashMap<>();
    private final Map<String, Double> gauges = new HashMap<>();
    private final Map<String, Double> summaries = new HashMap<>();

    /**
     * Creates a new metric snapshot.
     *
     * @param timestamp the timestamp in milliseconds since epoch
     * @param hostname the hostname of the ZooKeeper server
     * @param appId the application ID (typically "zookeeper")
     */
    public MetricSnapshot(long timestamp, String hostname, String appId) {
        this.timestamp = timestamp;
        this.hostname = hostname;
        this.appId = appId;
    }

    /**
     * Adds a counter metric to the snapshot.
     *
     * <p>Counters represent monotonically increasing values such as total requests,
     * total bytes received, etc.</p>
     *
     * @param name the metric name
     * @param value the counter value
     */
    public void addCounter(String name, long value) {
        counters.put(name, value);
    }

    /**
     * Adds a gauge metric to the snapshot.
     *
     * <p>Gauges represent current values that can increase or decrease, such as
     * number of active connections, queue size, etc.</p>
     *
     * @param name the metric name
     * @param value the gauge value
     */
    public void addGauge(String name, double value) {
        gauges.put(name, value);
    }

    /**
     * Adds a summary metric to the snapshot.
     *
     * <p>Summaries represent computed statistics such as averages, minimums, maximums,
     * and percentiles. The existing {@link org.apache.zookeeper.server.metric.AvgMinMaxCounter}
     * and {@link org.apache.zookeeper.server.metric.AvgMinMaxPercentileCounter} classes
     * already compute these values and provide them as separate metrics (e.g., "latency_avg",
     * "latency_min", "latency_max", "latency_p99").</p>
     *
     * @param name the metric name (e.g., "request_latency_avg")
     * @param value the computed statistic value
     */
    public void addSummary(String name, double value) {
        summaries.put(name, value);
    }

    /**
     * Returns the total number of metrics in this snapshot.
     *
     * @return the sum of counters, gauges, and summaries
     */
    public int getMetricCount() {
        return counters.size() + gauges.size() + summaries.size();
    }

    /**
     * Returns the timestamp of this snapshot.
     *
     * @return timestamp in milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the hostname of the ZooKeeper server.
     *
     * @return the hostname
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Returns the application ID.
     *
     * @return the application ID (typically "zookeeper")
     */
    public String getAppId() {
        return appId;
    }

    /**
     * Returns all counter metrics in this snapshot.
     *
     * @return a view of the counters map
     */
    public Map<String, Long> getCounters() {
        return counters;
    }

    /**
     * Returns all gauge metrics in this snapshot.
     *
     * @return a view of the gauges map
     */
    public Map<String, Double> getGauges() {
        return gauges;
    }

    /**
     * Returns all summary metrics in this snapshot.
     *
     * @return a view of the summaries map
     */
    public Map<String, Double> getSummaries() {
        return summaries;
    }

    @Override
    public String toString() {
        return String.format("MetricSnapshot{timestamp=%d, hostname='%s', appId='%s', "
            + "counters=%d, gauges=%d, summaries=%d}",
            timestamp, hostname, appId, counters.size(), gauges.size(), summaries.size());
    }

    /**
     * Helper method to repeat a character n times (Java 8 compatible).
     */
    private String repeatChar(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Prints all metrics in this snapshot to a formatted string.
     *
     * <p>This method is useful for debugging and logging. It prints all counters,
     * gauges, and summaries in a human-readable format.</p>
     *
     * @return a formatted string containing all metrics
     */
    public String printAllMetrics() {
        StringBuilder sb = new StringBuilder();
        sb.append(repeatChar('=', 80)).append("\n");
        sb.append("MetricSnapshot Details\n");
        sb.append(repeatChar('=', 80)).append("\n");
        sb.append(String.format("Timestamp: %d (%s)%n", timestamp,
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(timestamp))));
        sb.append(String.format("Hostname:  %s%n", hostname));
        sb.append(String.format("AppId:     %s%n", appId));
        sb.append(String.format("Total Metrics: %d (Counters: %d, Gauges: %d, Summaries: %d)%n",
            getMetricCount(), counters.size(), gauges.size(), summaries.size()));
        sb.append(repeatChar('=', 80)).append("\n\n");

        // Print Counters
        if (!counters.isEmpty()) {
            sb.append("COUNTERS (").append(counters.size()).append("):\n");
            sb.append(repeatChar('-', 80)).append("\n");
            counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append(String.format("  %-50s : %,d%n",
                    entry.getKey(), entry.getValue())));
            sb.append("\n");
        }

        // Print Gauges
        if (!gauges.isEmpty()) {
            sb.append("GAUGES (").append(gauges.size()).append("):\n");
            sb.append(repeatChar('-', 80)).append("\n");
            gauges.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append(String.format("  %-50s : %.2f%n",
                    entry.getKey(), entry.getValue())));
            sb.append("\n");
        }

        // Print Summaries
        if (!summaries.isEmpty()) {
            sb.append("SUMMARIES (").append(summaries.size()).append("):\n");
            sb.append(repeatChar('-', 80)).append("\n");
            summaries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append(String.format("  %-50s : %.2f%n",
                    entry.getKey(), entry.getValue())));
            sb.append("\n");
        }

        sb.append(repeatChar('=', 80)).append("\n");
        return sb.toString();
    }
}
