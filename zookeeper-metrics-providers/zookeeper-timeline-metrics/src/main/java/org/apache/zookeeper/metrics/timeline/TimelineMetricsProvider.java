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

package org.apache.zookeeper.metrics.timeline;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.apache.zookeeper.metrics.Counter;
import org.apache.zookeeper.metrics.CounterSet;
import org.apache.zookeeper.metrics.Gauge;
import org.apache.zookeeper.metrics.GaugeSet;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.Summary;
import org.apache.zookeeper.metrics.SummarySet;
import org.apache.zookeeper.server.metric.AvgMinMaxCounter;
import org.apache.zookeeper.server.metric.AvgMinMaxCounterSet;
import org.apache.zookeeper.server.metric.AvgMinMaxPercentileCounter;
import org.apache.zookeeper.server.metric.AvgMinMaxPercentileCounterSet;
import org.apache.zookeeper.server.metric.SimpleCounter;
import org.apache.zookeeper.server.metric.SimpleCounterSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MetricsProvider implementation that sends ZooKeeper metrics to Timeline collectors.
 * 
 * <p>This provider periodically samples metrics from its internal {@link MetricsContext}
 * and sends them to an external Timeline metrics sink (such as Ambari Metrics Collector).
 * The sink implementation is loaded dynamically at runtime, allowing ZooKeeper to
 * remain independent of specific metrics collection systems.</p>
 * 
 * <p><b>Configuration:</b></p>
 * <p>This provider is configured via zoo.cfg with the following properties:</p>
 * <pre>
 * # Enable Timeline metrics provider
 * metricsProvider.className=org.apache.zookeeper.metrics.timeline.TimelineMetricsProvider
 * 
 * # Sink class (loaded from external JAR on classpath)
 * metricsProvider.timeline.sink.class=org.apache.hadoop.metrics2.sink.timeline.ZooKeeperTimelineMetricsSink
 * 
 * # Collection settings
 * metricsProvider.timeline.collection.period=60
 * metricsProvider.timeline.hostname=zk1.example.com
 * metricsProvider.timeline.appId=zookeeper
 * 
 * # All other metricsProvider.timeline.* properties are passed to the sink
 * metricsProvider.timeline.collector.hosts=collector1.example.com,collector2.example.com
 * metricsProvider.timeline.collector.protocol=http
 * metricsProvider.timeline.collector.port=6188
 * </pre>
 * 
 * <p><b>Lifecycle:</b></p>
 * <ol>
 *   <li>ZooKeeper instantiates this class via reflection</li>
 *   <li>{@link #configure(Properties)} loads configuration and sink class</li>
 *   <li>{@link #start()} begins periodic metric collection</li>
 *   <li>Metrics are collected every N seconds and sent to sink</li>
 *   <li>{@link #stop()} shuts down collection and closes sink</li>
 * </ol>
 * 
 * @see TimelineMetricsSink
 * @see MetricSnapshot
 */
public class TimelineMetricsProvider implements MetricsProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(TimelineMetricsProvider.class);
    
    // Configuration property keys
    private static final String SINK_CLASS_PROPERTY = "timeline.sink.class";
    private static final String COLLECTION_PERIOD_PROPERTY = "timeline.collection.period";
    private static final String HOSTNAME_PROPERTY = "timeline.hostname";
    private static final String APP_ID_PROPERTY = "timeline.appId";
    
    // Default values
    private static final String DEFAULT_SINK_CLASS = 
        "org.apache.hadoop.metrics2.sink.timeline.ZooKeeperTimelineMetricsSink";
    private static final int DEFAULT_COLLECTION_PERIOD_SECONDS = 60;
    private static final String DEFAULT_APP_ID = "zookeeper";
    
    // Instance fields
    private final TimelineMetricsContext rootContext = new TimelineMetricsContext();
    private ScheduledExecutorService scheduler;
    private TimelineMetricsSink sink;
    private int collectionPeriodSeconds;
    private String hostname;
    private String appId;
    private volatile boolean started = false;
    
    /**
     * Default constructor required by MetricsProvider contract.
     */
    public TimelineMetricsProvider() {
        // Empty constructor - initialization happens in configure()
    }
    
    /**
     * Configure the provider with properties from zoo.cfg.
     * 
     * <p>This method loads the sink class dynamically and configures it with
     * all properties that start with "metricsProvider.timeline.". The sink class must be
     * available on the classpath (typically from an external JAR).</p>
     * 
     * @param configuration Properties from zoo.cfg
     * @throws MetricsProviderLifeCycleException if configuration fails
     */
    @Override
    public void configure(Properties configuration) throws MetricsProviderLifeCycleException {
        try {
            // Load basic configuration
            this.collectionPeriodSeconds = Integer.parseInt(
                configuration.getProperty(COLLECTION_PERIOD_PROPERTY, 
                    String.valueOf(DEFAULT_COLLECTION_PERIOD_SECONDS)));
            
            this.appId = configuration.getProperty(APP_ID_PROPERTY, DEFAULT_APP_ID);
            
            this.hostname = configuration.getProperty(HOSTNAME_PROPERTY);
            if (hostname == null || hostname.trim().isEmpty()) {
                this.hostname = getLocalHostname();
            }
            
            LOG.info("Configuring TimelineMetricsProvider: hostname={}, appId={}, collectionPeriod={} seconds",
                hostname, appId, collectionPeriodSeconds);
            
            // Try to load and configure sink - but don't fail if it's not available
            String sinkClassName = configuration.getProperty(SINK_CLASS_PROPERTY, DEFAULT_SINK_CLASS);
            try {
                this.sink = loadSink(sinkClassName);
                this.sink.configure(configuration);
                LOG.info("Successfully configured TimelineMetricsProvider with sink: {}", sinkClassName);
            } catch (ClassNotFoundException e) {
                LOG.warn("Timeline sink class not found: {}. Timeline metrics will be disabled. "
                    + "To enable Timeline metrics, ensure the sink implementation JAR is available on the classpath. "
                    + "ZooKeeper will continue to operate normally without Timeline metrics.", sinkClassName);
                this.sink = null;
            } catch (Exception e) {
                LOG.warn("Failed to configure Timeline sink: {}. Timeline metrics will be disabled. "
                    + "ZooKeeper will continue to operate normally without Timeline metrics.", e.getMessage(), e);
                this.sink = null;
            }
            
        } catch (Exception e) {
            LOG.error("Failed to configure TimelineMetricsProvider", e);
            throw new MetricsProviderLifeCycleException("Configuration failed", e);
        }
    }
    
    /**
     * Start the provider and begin periodic metric collection.
     * 
     * <p>This method creates a scheduled executor that collects metrics
     * every N seconds (configured via metricsProvider.timeline.collection.period). The
     * collection runs on a daemon thread to avoid blocking ZooKeeper shutdown.</p>
     * 
     * @throws MetricsProviderLifeCycleException if startup fails
     */
    @Override
    public void start() throws MetricsProviderLifeCycleException {
        if (started) {
            LOG.warn("TimelineMetricsProvider already started");
            return;
        }
        
        // If sink is not available, don't start the scheduler
        if (sink == null) {
            LOG.warn("Timeline sink not configured. Metric collection will not start. "
                + "ZooKeeper will continue to operate normally without Timeline metrics.");
            return;
        }
        
        try {
            // Create scheduler with daemon thread
            this.scheduler = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "TimelineMetricsCollector");
                t.setDaemon(true);
                return t;
            });
            
            // Schedule periodic collection
            scheduler.scheduleAtFixedRate(
                this::collectAndSend,
                0,  // Initial delay
                collectionPeriodSeconds,
                TimeUnit.SECONDS
            );
            
            started = true;
            LOG.info("Started TimelineMetricsProvider - collecting metrics every {} seconds", 
                collectionPeriodSeconds);
            
        } catch (Exception e) {
            LOG.error("Failed to start TimelineMetricsProvider", e);
            throw new MetricsProviderLifeCycleException("Startup failed", e);
        }
    }
    
    /**
     * Stop the provider and release all resources.
     * 
     * <p>This method shuts down the scheduler, closes the sink, and releases
     * all resources. It can be called multiple times safely.</p>
     */
    @Override
    public void stop() {
        if (!started) {
            return;
        }
        
        LOG.info("Stopping TimelineMetricsProvider");
        
        // Shutdown scheduler
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Close sink
        if (sink != null) {
            try {
                sink.close();
            } catch (Exception e) {
                LOG.error("Error closing Timeline sink", e);
            }
        }
        
        // Clear all metrics from context
        rootContext.clear();
        
        started = false;
        LOG.info("Stopped TimelineMetricsProvider");
    }
    
    /**
     * Returns the root metrics context.
     * 
     * <p>This provider maintains its own {@link TimelineMetricsContext} that stores
     * all registered metrics. Components can register counters, gauges, summaries, etc.
     * which will be automatically collected and sent to Timeline.</p>
     * 
     * @return the root metrics context
     */
    @Override
    public MetricsContext getRootContext() {
        return rootContext;
    }
    
    /**
     * Dumps all current metric values.
     * 
     * <p>This method is called by legacy monitoring commands. It iterates through
     * all metrics stored in the context and provides their current values.</p>
     * 
     * @param sink the receiver of metric name-value pairs
     */
    @Override
    public void dump(BiConsumer<String, Object> sink) {
        rootContext.dump(sink);
    }
    
    /**
     * Resets all metric values.
     * 
     * <p>This resets all counters and summaries to their initial state.
     * Gauges are not reset as they represent current values.</p>
     */
    @Override
    public void resetAllValues() {
        rootContext.reset();
    }
    
    /**
     * Collects metrics from the context and sends to sink.
     * 
     * <p>This method is called periodically by the scheduler. It creates a snapshot
     * of all current metric values and sends it to the configured sink.</p>
     * 
     * <p>Exceptions are caught and logged to prevent them from stopping
     * the scheduled collection.</p>
     */
    private void collectAndSend() {
        try {
            if (sink == null) {
                LOG.debug("Timeline sink is null, skipping metric collection");
                return;
            }
            
            // Create snapshot
            MetricSnapshot snapshot = new MetricSnapshot(
                System.currentTimeMillis(),
                hostname,
                appId
            );
            
            // Dump all metrics from context to snapshot
            rootContext.dumpToSnapshot(snapshot);
            
            // Send to Timeline
            sink.send(snapshot);
            
            if (LOG.isDebugEnabled()) {
                LOG.debug("Sent {} metrics to Timeline", snapshot.getMetricCount());
                LOG.debug("{}", snapshot.printAllMetrics());
            }
            
        } catch (Exception e) {
            LOG.error("Failed to collect and send metrics", e);
        }
    }
    
    /**
     * Loads the Timeline sink class dynamically via reflection.
     * 
     * <p>The sink class must be available on the classpath (typically from
     * an external JAR). This allows ZooKeeper to remain independent of
     * specific metrics collection systems.</p>
     * 
     * @param className the fully qualified class name of the sink
     * @return an instance of the sink
     * @throws ClassNotFoundException if the class cannot be found
     * @throws Exception if the class cannot be instantiated
     */
    private TimelineMetricsSink loadSink(String className) throws ClassNotFoundException, Exception {
        LOG.info("Loading Timeline sink class: {}", className);
        
        try {
            Class<?> clazz = Class.forName(className);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            
            if (!(instance instanceof TimelineMetricsSink)) {
                throw new IllegalArgumentException(
                    "Class " + className + " does not implement TimelineMetricsSink");
            }
            
            return (TimelineMetricsSink) instance;
            
        } catch (ClassNotFoundException e) {
            // Re-throw ClassNotFoundException so it can be caught separately in configure()
            throw e;
        } catch (Exception e) {
            throw new Exception("Failed to instantiate Timeline sink: " + className, e);
        }
    }
    
    /**
     * Gets the local hostname.
     * 
     * @return the hostname, or "unknown" if it cannot be determined
     */
    private String getLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            LOG.warn("Unable to determine hostname, using 'unknown'", e);
            return "unknown";
        }
    }
    
    /**
     * Internal MetricsContext implementation that stores all metrics.
     * 
     * <p>This context reuses existing metric implementations from zookeeper-server
     * (SimpleCounter, AvgMinMaxCounter, etc.) to ensure consistent behavior with
     * other metrics providers.</p>
     */
    private static class TimelineMetricsContext implements MetricsContext {
        
        private final ConcurrentMap<String, SimpleCounter> counters = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, SimpleCounterSet> counterSets = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Gauge> gauges = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, GaugeSet> gaugeSets = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AvgMinMaxCounter> basicSummaries = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AvgMinMaxPercentileCounter> summaries = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AvgMinMaxCounterSet> basicSummarySets = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AvgMinMaxPercentileCounterSet> summarySets = new ConcurrentHashMap<>();
        
        @Override
        public MetricsContext getContext(String name) {
            // No hierarchy yet - return this
            return this;
        }
        
        @Override
        public Counter getCounter(String name) {
            return counters.computeIfAbsent(name, SimpleCounter::new);
        }
        
        @Override
        public CounterSet getCounterSet(String name) {
            Objects.requireNonNull(name, "Cannot register a CounterSet with null name");
            return counterSets.computeIfAbsent(name, SimpleCounterSet::new);
        }
        
        @Override
        public void registerGauge(String name, Gauge gauge) {
            Objects.requireNonNull(gauge, "Cannot register a null Gauge for " + name);
            gauges.put(name, gauge);
        }
        
        @Override
        public void unregisterGauge(String name) {
            gauges.remove(name);
        }
        
        @Override
        public void registerGaugeSet(String name, GaugeSet gaugeSet) {
            Objects.requireNonNull(name, "Cannot register a GaugeSet with null name");
            Objects.requireNonNull(gaugeSet, "Cannot register a null GaugeSet for " + name);
            gaugeSets.put(name, gaugeSet);
        }
        
        @Override
        public void unregisterGaugeSet(String name) {
            Objects.requireNonNull(name, "Cannot unregister GaugeSet with null name");
            gaugeSets.remove(name);
        }
        
        @Override
        public Summary getSummary(String name, DetailLevel detailLevel) {
            if (detailLevel == DetailLevel.BASIC) {
                return basicSummaries.computeIfAbsent(name, (n) -> {
                    if (summaries.containsKey(n)) {
                        throw new IllegalArgumentException("Already registered a non basic summary as " + n);
                    }
                    return new AvgMinMaxCounter(name);
                });
            } else {
                return summaries.computeIfAbsent(name, (n) -> {
                    if (basicSummaries.containsKey(n)) {
                        throw new IllegalArgumentException("Already registered a basic summary as " + n);
                    }
                    return new AvgMinMaxPercentileCounter(name);
                });
            }
        }
        
        @Override
        public SummarySet getSummarySet(String name, DetailLevel detailLevel) {
            if (detailLevel == DetailLevel.BASIC) {
                return basicSummarySets.computeIfAbsent(name, (n) -> {
                    if (summarySets.containsKey(n)) {
                        throw new IllegalArgumentException("Already registered a non basic summary set as " + n);
                    }
                    return new AvgMinMaxCounterSet(name);
                });
            } else {
                return summarySets.computeIfAbsent(name, (n) -> {
                    if (basicSummarySets.containsKey(n)) {
                        throw new IllegalArgumentException("Already registered a basic summary set as " + n);
                    }
                    return new AvgMinMaxPercentileCounterSet(name);
                });
            }
        }
        
        /**
         * Dumps all metrics to a MetricSnapshot for Timeline export.
         */
        void dumpToSnapshot(MetricSnapshot snapshot) {
            // Dump gauges
            gauges.forEach((name, gauge) -> {
                Number value = gauge.get();
                if (value != null) {
                    snapshot.addGauge(name, value.doubleValue());
                }
            });
            
            // Dump gauge sets
            gaugeSets.forEach((name, gaugeSet) ->
                gaugeSet.values().forEach((key, value) -> {
                    if (key != null) {
                        snapshot.addGauge(key + "_" + name, value != null ? value.doubleValue() : 0);
                    }
                })
            );
            
            // Dump counters
            counters.values().forEach(counter -> {
                counter.values().forEach((name, value) -> {
                    snapshot.addCounter(name, ((Number) value).longValue());
                });
            });
            
            // Dump counter sets
            counterSets.values().forEach(counterSet -> {
                counterSet.values().forEach((name, value) -> {
                    snapshot.addCounter(name, ((Number) value).longValue());
                });
            });
            
            // Dump basic summaries (avg, min, max)
            basicSummaries.values().forEach(summary -> {
                summary.values().forEach((name, value) -> {
                    snapshot.addSummary(name, ((Number) value).doubleValue());
                });
            });
            
            // Dump advanced summaries (avg, min, max, percentiles)
            summaries.values().forEach(summary -> {
                summary.values().forEach((name, value) -> {
                    snapshot.addSummary(name, ((Number) value).doubleValue());
                });
            });
            
            // Dump basic summary sets
            basicSummarySets.values().forEach(summarySet -> {
                summarySet.values().forEach((name, value) -> {
                    snapshot.addSummary(name, ((Number) value).doubleValue());
                });
            });
            
            // Dump advanced summary sets
            summarySets.values().forEach(summarySet -> {
                summarySet.values().forEach((name, value) -> {
                    snapshot.addSummary(name, ((Number) value).doubleValue());
                });
            });
        }
        
        /**
         * Dumps all metrics for legacy monitoring commands.
         */
        void dump(BiConsumer<String, Object> sink) {
            gauges.forEach((name, gauge) -> {
                Number value = gauge.get();
                if (value != null) {
                    sink.accept(name, value);
                }
            });
            
            gaugeSets.forEach((name, gaugeSet) ->
                gaugeSet.values().forEach((key, value) -> {
                    if (key != null) {
                        sink.accept(key + "_" + name, value != null ? value : 0);
                    }
                })
            );
            
            counters.values().forEach(counter -> counter.values().forEach(sink));
            counterSets.values().forEach(counterSet -> counterSet.values().forEach(sink));
            basicSummaries.values().forEach(summary -> summary.values().forEach(sink));
            summaries.values().forEach(summary -> summary.values().forEach(sink));
            basicSummarySets.values().forEach(summarySet -> summarySet.values().forEach(sink));
            summarySets.values().forEach(summarySet -> summarySet.values().forEach(sink));
        }
        
        /**
         * Resets all metrics to their initial state.
         */
        void reset() {
            counters.values().forEach(SimpleCounter::reset);
            counterSets.values().forEach(SimpleCounterSet::reset);
            basicSummaries.values().forEach(AvgMinMaxCounter::reset);
            summaries.values().forEach(AvgMinMaxPercentileCounter::reset);
            basicSummarySets.values().forEach(AvgMinMaxCounterSet::reset);
            summarySets.values().forEach(AvgMinMaxPercentileCounterSet::reset);
            // No need to reset gauges - they're read-only
        }
        
        /**
         * Clears all metrics from the context.
         */
        void clear() {
            gauges.clear();
            gaugeSets.clear();
            counters.clear();
            counterSets.clear();
            basicSummaries.clear();
            summaries.clear();
            basicSummarySets.clear();
            summarySets.clear();
        }
    }
}
