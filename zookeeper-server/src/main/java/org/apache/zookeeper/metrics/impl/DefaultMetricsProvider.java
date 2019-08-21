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

package org.apache.zookeeper.metrics.impl;

import java.util.Objects;
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

/**
 * Default implementation of {@link MetricsProvider}.<br>
 * It does not implement a real hierarchy of contexts, but metrics are flattened
 * in a single namespace.<br>
 * It is mostly useful to make the legacy 4 letter words interface work as
 * expected.
 */
public class DefaultMetricsProvider implements MetricsProvider {

    private final DefaultMetricsContext rootMetricsContext = new DefaultMetricsContext();

    @Override
    public void configure(Properties configuration) throws MetricsProviderLifeCycleException {
    }

    @Override
    public void start() throws MetricsProviderLifeCycleException {
    }

    @Override
    public MetricsContext getRootContext() {
        return rootMetricsContext;
    }

    @Override
    public void stop() {
        // release all references to external objects
        rootMetricsContext.gauges.clear();
    }

    @Override
    public void dump(BiConsumer<String, Object> sink) {
        rootMetricsContext.dump(sink);
    }

    @Override
    public void resetAllValues() {
        rootMetricsContext.reset();
    }

    private static final class DefaultMetricsContext implements MetricsContext {

        private final ConcurrentMap<String, Gauge> gauges = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, SimpleCounter> counters = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AvgMinMaxCounter> basicSummaries = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AvgMinMaxPercentileCounter> summaries = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AvgMinMaxCounterSet> basicSummarySets = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AvgMinMaxPercentileCounterSet> summarySets = new ConcurrentHashMap<>();

        @Override
        public MetricsContext getContext(String name) {
            // no hierarchy yet
            return this;
        }

        @Override
        public Counter getCounter(String name) {
            return counters.computeIfAbsent(name, (n) -> {
                return new SimpleCounter(n);
            });
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
            gauges.forEach((name, metric) -> {
                Number value = metric.get();
                if (value != null) {
                    sink.accept(name, value);
                }
            });
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
            // no need to reset gauges
        }

    }

}
