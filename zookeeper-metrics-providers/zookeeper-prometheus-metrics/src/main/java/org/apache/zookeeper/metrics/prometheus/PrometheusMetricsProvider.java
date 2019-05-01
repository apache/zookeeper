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
package org.apache.zookeeper.metrics.prometheus;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.MetricsServlet;
import java.util.Enumeration;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import org.apache.zookeeper.metrics.Counter;
import org.apache.zookeeper.metrics.Gauge;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.Summary;
import org.apache.zookeeper.metrics.SummarySet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Metrics Provider implementation based on https://prometheus.io.
 *
 * @since 3.6.0
 */
public class PrometheusMetricsProvider implements MetricsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(PrometheusMetricsProvider.class);
    private final CollectorRegistry collectorRegistry = new CollectorRegistry(true);
    private int port = 7000;
    private Server server;
    private final Context rootContext = new Context();

    @Override
    public void configure(Properties configuration) throws MetricsProviderLifeCycleException {
        LOG.info("Initializing metrics, configuration: {}", configuration);
        this.port = Integer.parseInt(configuration.getProperty("port", "7000"));
    }

    @Override
    public void start() throws MetricsProviderLifeCycleException {
        try {
            LOG.info("Starting /metrics HTTP endpoint at port " + port);
            server = new Server(port);
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server.setHandler(context);
            context.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");
            server.start();
        } catch (Exception err) {
            LOG.error("Cannot start /metrics server", err);
            server = null;
            throw new MetricsProviderLifeCycleException(err);
        }
    }

    @Override
    public MetricsContext getRootContext() {
        return rootContext;
    }

    @Override
    public void stop() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception err) {
                LOG.error("Cannot safely stop Jetty server", err);
            } finally {
                server = null;
            }
        }
    }

    @Override
    public void dump(BiConsumer<String, Object> sink) {
        Enumeration<Collector.MetricFamilySamples> samplesFamilies = collectorRegistry.metricFamilySamples();
        while (samplesFamilies.hasMoreElements()) {
            Collector.MetricFamilySamples samples = samplesFamilies.nextElement();
            samples.samples.forEach(sample -> {
                StringBuilder keyBuilder = new StringBuilder();
                keyBuilder.append(sample.name);
                if (sample.labelNames.size() > 0) {
                    keyBuilder.append('{');
                    for (int i = 0; i < sample.labelNames.size(); ++i) {
                        keyBuilder.append(sample.labelNames.get(i));
                        keyBuilder.append("=\"");
                        keyBuilder.append(sample.labelValues.get(i));
                        keyBuilder.append("\",");
                    }
                    keyBuilder.append('}');
                }
                sink.accept(keyBuilder.toString(), sample.value);
            });
        }
    }

    @Override
    public void resetAllValues() {
        // not supported on Prometheus
    }

    private class Context implements MetricsContext {

        private final ConcurrentMap<String, Gauge> gauges = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusCounter> counters = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusSummary> basicSummaries = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusSummary> summaries = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusLabelledSummary> basicSummarySets = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusLabelledSummary> summarySets = new ConcurrentHashMap<>();

        @Override
        public MetricsContext getContext(String name) {
            // no hierarchy yet
            return this;
        }

        @Override
        public Counter getCounter(String name) {
            return counters.computeIfAbsent(name, (n) -> {
                return new PrometheusCounter(n);
            });
        }

        @Override
        public void registerGauge(String name, Gauge gauge) {
            if (gauge == null) {
                // null means 'unregister'
                gauges.remove(name);
            } else {
                gauges.put(name, gauge);
            }
        }

        @Override
        public Summary getSummary(String name, DetailLevel detailLevel) {
            if (detailLevel == DetailLevel.BASIC) {
                return basicSummaries.computeIfAbsent(name, (n) -> {
                    if (summaries.containsKey(n)) {
                        throw new IllegalArgumentException("Already registered a non basic summary as " + n);
                    }
                    return new PrometheusSummary(name, detailLevel);
                });
            } else {
                return summaries.computeIfAbsent(name, (n) -> {
                    if (basicSummaries.containsKey(n)) {
                        throw new IllegalArgumentException("Already registered a basic summary as " + n);
                    }
                    return new PrometheusSummary(name, detailLevel);
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
                    return new PrometheusLabelledSummary(name, detailLevel);
                });
            } else {
                return summarySets.computeIfAbsent(name, (n) -> {
                    if (basicSummarySets.containsKey(n)) {
                        throw new IllegalArgumentException("Already registered a basic summary set as " + n);
                    }
                    return new PrometheusLabelledSummary(name, detailLevel);
                });
            }
        }

    }

    private class PrometheusCounter implements Counter {

        private final io.prometheus.client.Counter inner;
        private final String name;

        public PrometheusCounter(String name) {
            this.name = name;
            this.inner = io.prometheus.client.Counter
                    .build(name, name)
                    .register(collectorRegistry)
                    .register();
        }

        @Override
        public void add(long delta) {
            try {
                inner.inc(delta);
            } catch (IllegalArgumentException err) {
                LOG.error("invalid delta for counter " + name, err);
            }
        }

        @Override
        public long get() {
            return (long) inner.get();
        }

    }

    private class PrometheusSummary implements Summary {

        private final io.prometheus.client.Summary inner;
        private final String name;

        public PrometheusSummary(String name, MetricsContext.DetailLevel level) {
            this.name = name;
            if (level == MetricsContext.DetailLevel.ADVANCED) {
                this.inner = io.prometheus.client.Summary
                        .build(name, name)
                        .quantile(0.5, 0.05) // Add 50th percentile (= median) with 5% tolerated error
                        .quantile(0.9, 0.01) // Add 90th percentile with 1% tolerated error
                        .quantile(0.99, 0.001) // Add 99th percentile with 0.1% tolerated error
                        .register(collectorRegistry)
                        .register();
            } else {
                this.inner = io.prometheus.client.Summary
                        .build(name, name)
                        .quantile(0.5, 0.05) // Add 50th percentile (= median) with 5% tolerated error
                        .register(collectorRegistry)
                        .register();
            }
        }

        @Override
        public void add(long delta) {
            try {
                inner.observe(delta);
            } catch (IllegalArgumentException err) {
                LOG.error("invalid delta for counter " + name, err);
            }
        }

    }
    private static final String LABEL = "key";

    private static final String[] LABELS = {LABEL};

    private class PrometheusLabelledSummary implements SummarySet {

        private final io.prometheus.client.Summary inner;
        private final String name;

        public PrometheusLabelledSummary(String name, MetricsContext.DetailLevel level) {
            this.name = name;
            if (level == MetricsContext.DetailLevel.ADVANCED) {
                this.inner = io.prometheus.client.Summary
                        .build(name, name)
                        .labelNames(LABELS)
                        .quantile(0.5, 0.05) // Add 50th percentile (= median) with 5% tolerated error
                        .quantile(0.9, 0.01) // Add 90th percentile with 1% tolerated error
                        .quantile(0.99, 0.001) // Add 99th percentile with 0.1% tolerated error
                        .register(collectorRegistry)
                        .register();
            } else {
                this.inner = io.prometheus.client.Summary
                        .build(name, name)
                        .labelNames(LABELS)
                        .quantile(0.5, 0.05) // Add 50th percentile (= median) with 5% tolerated error
                        .register(collectorRegistry)
                        .register();
            }
        }

        @Override
        public void add(String key, long value) {
            inner.labels(key).observe(value);
        }

    }
}
