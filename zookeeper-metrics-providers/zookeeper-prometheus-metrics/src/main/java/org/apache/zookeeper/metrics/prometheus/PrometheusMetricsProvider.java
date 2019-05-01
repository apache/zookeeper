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
import io.prometheus.client.exporter.common.TextFormat;
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
import org.apache.zookeeper.server.metric.AvgMinMaxCounter;
import org.apache.zookeeper.server.metric.AvgMinMaxCounterSet;
import org.apache.zookeeper.server.metric.AvgMinMaxPercentileCounter;
import org.apache.zookeeper.server.metric.AvgMinMaxPercentileCounterSet;
import org.apache.zookeeper.server.metric.SimpleCounter;
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

        private final ConcurrentMap<String, io.prometheus.client.Counter> counters = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, io.prometheus.client.Summary> basicSummaries = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, io.prometheus.client.Summary> summaries = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, io.prometheus.client.Summary> basicSummarySets = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, io.prometheus.client.Summary> summarySets = new ConcurrentHashMap<>();

        @Override
        public MetricsContext getContext(String name) {
            // no hierarchy
            return this;
        }

        @Override
        public Counter getCounter(String name) {
            return counters.computeIfAbsent(name, (n) -> {
                return new SimpleCounter(n);
            });
        }

        @Override
        public boolean registerGauge(String name, Gauge gauge) {
            // Not supported
            return false;
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

        void dump(BiConsumer<String, Object> sink) {
            counters.values().forEach(metric -> {
                metric.values().forEach(sink);
            });
            basicSummaries.values().forEach(metric -> {
                metric.values().forEach(sink);
            });
            summaries.values().forEach(metric -> {
                metric.values().forEach(sink);
            });
            basicSummarySets.values().forEach(metric -> {
                metric.values().forEach(sink);
            });
            summarySets.values().forEach(metric -> {
                metric.values().forEach(sink);
            });
        }

        void reset() {
            counters.values().forEach(metric -> {
                metric.reset();
            });
            basicSummaries.values().forEach(metric -> {
                metric.reset();
            });
            summaries.values().forEach(metric -> {
                metric.reset();
            });
            basicSummarySets.values().forEach(metric -> {
                metric.reset();
            });
            summarySets.values().forEach(metric -> {
                metric.reset();
            });
        }
    }

}
