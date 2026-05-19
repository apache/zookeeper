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

import java.util.Properties;

/**
 * Interface for Timeline metrics sinks.
 *
 * <p>This interface defines the contract between ZooKeeper's metrics collection
 * system and external Timeline metrics collectors (such as Ambari Metrics Collector).
 * Implementations of this interface are loaded dynamically at runtime, allowing
 * ZooKeeper to remain independent of specific metrics collection systems.</p>
 *
 * <p>The typical lifecycle is:</p>
 * <ol>
 *   <li>Sink is instantiated via reflection (Class.forName)</li>
 *   <li>{@link #configure(Properties)} is called with configuration</li>
 *   <li>{@link #send(MetricSnapshot)} is called periodically with metrics</li>
 *   <li>{@link #close()} is called during shutdown</li>
 * </ol>
 *
 * <p>Example implementation in external JAR:</p>
 * <pre>
 * public class MyTimelineSink implements TimelineMetricsSink {
 *     public void configure(Properties config) throws Exception {
 *         // Initialize HTTP client, load collector addresses, etc.
 *     }
 *
 *     public void send(MetricSnapshot snapshot) throws Exception {
 *         // Transform snapshot to target format and send via HTTP
 *     }
 *
 *     public void close() throws Exception {
 *         // Cleanup resources
 *     }
 * }
 * </pre>
 *
 * @see TimelineMetricsProvider
 * @see MetricSnapshot
 */
public interface TimelineMetricsSink {

    /**
     * Configure the sink with the provided properties.
     *
     * <p>This method is called once during initialization, before any metrics
     * are sent. Implementations should use this method to:</p>
     * <ul>
     *   <li>Load collector addresses and connection settings</li>
     *   <li>Initialize HTTP clients or other communication mechanisms</li>
     *   <li>Set up SSL/TLS if required</li>
     *   <li>Validate configuration parameters</li>
     * </ul>
     *
     * @param config Configuration properties from zoo.cfg. All properties
     *               with the "metricsProvider.timeline." prefix are passed to the sink.
     * @throws Exception if configuration fails. The exception will be logged
     *                   and ZooKeeper startup will continue without Timeline metrics.
     */
    void configure(Properties config) throws Exception;

    /**
     * Send a snapshot of metrics to the Timeline collector.
     *
     * <p>This method is called periodically (typically every 60 seconds) with
     * a snapshot of all current metric values. Implementations should:</p>
     * <ul>
     *   <li>Transform the snapshot to the target format (e.g., JSON)</li>
     *   <li>Send metrics to the collector via HTTP POST or other protocol</li>
     *   <li>Handle transient failures gracefully (retry, cache, etc.)</li>
     *   <li>Return quickly to avoid blocking metric collection</li>
     * </ul>
     *
     * <p>Note: This method may be called from a scheduled executor thread.
     * Implementations should be thread-safe and avoid blocking operations
     * that could delay subsequent metric collections.</p>
     *
     * @param snapshot A snapshot of all metrics at a specific point in time.
     *                 Contains counters, gauges, and summary statistics.
     * @throws Exception if sending fails. Exceptions are logged but do not
     *                   stop metric collection. The next snapshot will be
     *                   attempted on schedule.
     */
    void send(MetricSnapshot snapshot) throws Exception;

    /**
     * Close the sink and release all resources.
     *
     * <p>This method is called during ZooKeeper shutdown. Implementations should:</p>
     * <ul>
     *   <li>Flush any cached metrics</li>
     *   <li>Close HTTP connections</li>
     *   <li>Shutdown thread pools</li>
     *   <li>Release any other resources</li>
     * </ul>
     *
     * <p>This method should complete quickly (within a few seconds) to avoid
     * delaying ZooKeeper shutdown.</p>
     *
     * @throws Exception if cleanup fails. Exceptions are logged but do not
     *                   prevent ZooKeeper shutdown.
     */
    void close() throws Exception;
}
