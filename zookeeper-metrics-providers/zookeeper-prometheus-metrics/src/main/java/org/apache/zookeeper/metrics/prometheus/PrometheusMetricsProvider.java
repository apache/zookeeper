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

package org.apache.zookeeper.metrics.prometheus;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.MetricsServlet;
import io.prometheus.client.hotspot.DefaultExports;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
    private static final String LABEL = "key";
    private static final String[] LABELS = {LABEL};
    /**
     * We are using the 'defaultRegistry'.
     * <p>
     * When you are running ZooKeeper (server or client) together with other
     * libraries every metrics will be expected as a single view.
     * </p>
     */
    private final CollectorRegistry collectorRegistry = CollectorRegistry.defaultRegistry;
    private int port = 7000;
    private boolean exportJvmInfo = true;
    private Server server;
    private final MetricsServletImpl servlet = new MetricsServletImpl();
    private final Context rootContext = new Context();

    @Override
    public void configure(Properties configuration) throws MetricsProviderLifeCycleException {
        LOG.info("Initializing metrics, configuration: {}", configuration);
        this.port = Integer.parseInt(configuration.getProperty("httpPort", "7000"));
        this.exportJvmInfo = Boolean.parseBoolean(configuration.getProperty("exportJvmInfo", "true"));
    }

    @Override
    public void start() throws MetricsProviderLifeCycleException {
        try {
            LOG.info("Starting /metrics HTTP endpoint at port {} exportJvmInfo: {}", port, exportJvmInfo);
            if (exportJvmInfo) {
                DefaultExports.initialize();
            }
            server = new Server(port);
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server.setHandler(context);
            context.addServlet(new ServletHolder(servlet), "/metrics");
            server.start();
        } catch (Exception err) {
            LOG.error("Cannot start /metrics server", err);
            if (server != null) {
                try {
                    server.stop();
                } catch (Exception suppressed) {
                    err.addSuppressed(suppressed);
                } finally {
                    server = null;
                }
            }
            throw new MetricsProviderLifeCycleException(err);
        }
    }

    // for tests
    MetricsServletImpl getServlet() {
        return servlet;
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

    /**
     * Dump all values to the 4lw interface and to the Admin server.
     * <p>
     * This method is not expected to be used to serve metrics to Prometheus. We
     * are using the MetricsServlet provided by Prometheus for that, leaving the
     * real representation to the Prometheus Java client.
     * </p>
     *
     * @param sink the receiver of data (4lw interface, Admin server or tests)
     */
    @Override
    public void dump(BiConsumer<String, Object> sink) {
        sampleGauges();
        Enumeration<Collector.MetricFamilySamples> samplesFamilies = collectorRegistry.metricFamilySamples();
        while (samplesFamilies.hasMoreElements()) {
            Collector.MetricFamilySamples samples = samplesFamilies.nextElement();
            samples.samples.forEach(sample -> {
                String key = buildKeyForDump(sample);
                sink.accept(key, sample.value);
            });
        }
    }

    private static String buildKeyForDump(Collector.MetricFamilySamples.Sample sample) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(sample.name);
        if (sample.labelNames.size() > 0) {
            keyBuilder.append('{');
            for (int i = 0; i < sample.labelNames.size(); ++i) {
                if (i > 0) {
                    keyBuilder.append(',');
                }
                keyBuilder.append(sample.labelNames.get(i));
                keyBuilder.append("=\"");
                keyBuilder.append(sample.labelValues.get(i));
                keyBuilder.append('"');
            }
            keyBuilder.append('}');
        }
        return keyBuilder.toString();
    }

    /**
     * Update Gauges. In ZooKeeper Metrics API Gauges are callbacks served by
     * internal components and the value is not held by Prometheus structures.
     */
    private void sampleGauges() {
        rootContext.gauges.values()
                .forEach(PrometheusGaugeWrapper::sample);
    }

    @Override
    public void resetAllValues() {
        // not supported on Prometheus
    }

    private class Context implements MetricsContext {

        private final ConcurrentMap<String, PrometheusGaugeWrapper> gauges = new ConcurrentHashMap<>();
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
            return counters.computeIfAbsent(name, PrometheusCounter::new);
        }

        /**
         * Gauges may go up and down, in ZooKeeper they are a way to export
         * internal values with a callback.
         *
         * @param name  the name of the gauge
         * @param gauge the callback
         */
        @Override
        public void registerGauge(String name, Gauge gauge) {
            Objects.requireNonNull(name);
            gauges.compute(name, (id, prev) ->
                    new PrometheusGaugeWrapper(id, gauge, prev != null ? prev.inner : null));
        }

        @Override
        public void unregisterGauge(String name) {
            PrometheusGaugeWrapper existing = gauges.remove(name);
            if (existing != null) {
                existing.unregister();
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
                    .register(collectorRegistry);
        }

        @Override
        public void add(long delta) {
            try {
                inner.inc(delta);
            } catch (IllegalArgumentException err) {
                LOG.error("invalid delta {} for metric {}", delta, name, err);
            }
        }

        @Override
        public long get() {
            // this method is used only for tests
            // Prometheus returns a "double"
            // it is safe to fine to a long
            // we are never setting non-integer values
            return (long) inner.get();
        }

    }

    private class PrometheusGaugeWrapper {

        private final io.prometheus.client.Gauge inner;
        private final Gauge gauge;
        private final String name;

        public PrometheusGaugeWrapper(String name, Gauge gauge, io.prometheus.client.Gauge prev) {
            this.name = name;
            this.gauge = gauge;
            this.inner = prev != null ? prev
                    : io.prometheus.client.Gauge
                    .build(name, name)
                    .register(collectorRegistry);
        }

        /**
         * Call the callack and update Prometheus Gauge. This method is called
         * when the server is polling for a value.
         */
        private void sample() {
            Number value = gauge.get();
            this.inner.set(value != null ? value.doubleValue() : 0);
        }

        private void unregister() {
            collectorRegistry.unregister(inner);
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
                        .register(collectorRegistry);
            } else {
                this.inner = io.prometheus.client.Summary
                        .build(name, name)
                        .quantile(0.5, 0.05) // Add 50th percentile (= median) with 5% tolerated error
                        .register(collectorRegistry);
            }
        }

        @Override
        public void add(long delta) {
            try {
                inner.observe(delta);
            } catch (IllegalArgumentException err) {
                LOG.error("invalid delta {} for metric {}", delta, name, err);
            }
        }

    }

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
                        .register(collectorRegistry);
            } else {
                this.inner = io.prometheus.client.Summary
                        .build(name, name)
                        .labelNames(LABELS)
                        .quantile(0.5, 0.05) // Add 50th percentile (= median) with 5% tolerated error
                        .register(collectorRegistry);
            }
        }

        @Override
        public void add(String key, long value) {
            try {
                inner.labels(key).observe(value);
            } catch (IllegalArgumentException err) {
                LOG.error("invalid value {} for metric {} with key {}", value, name, key, err);
            }
        }

    }

    class MetricsServletImpl extends MetricsServlet {

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            // little trick: update the Gauges before serving data
            // from Prometheus CollectorRegistry
            sampleGauges();
            // serve data using Prometheus built in client.
            super.doGet(req, resp);
        }
    }
}
