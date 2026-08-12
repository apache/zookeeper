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

import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricMetadata;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.Quantile;
import io.prometheus.metrics.model.snapshots.Quantiles;
import io.prometheus.metrics.model.snapshots.SummarySnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.locks.StampedLock;
import org.apache.datasketches.quantiles.DoublesSketch;
import org.apache.datasketches.quantiles.DoublesUnion;
import org.apache.datasketches.quantiles.DoublesUnionBuilder;

/**
 * A DataSketches based Summary metric, aim for lock-free and low gc overhead.
 *
 * <p>Per-thread {@link DoublesSketch} instances accumulate observations without contention. A
 * periodic {@link #rotate()} aggregates them into a single result sketch that is exposed via
 * {@link #collect()}. Quantile values returned to Prometheus are based on the most recently
 * rotated result; before the first rotation they are {@link Double#NaN}.
 */
public class SketchesSummary implements Collector {

    private final String name;
    private final String help;
    private final List<String> labelNames;
    private final List<Double> quantiles;
    private final ConcurrentMap<List<String>, Child> children = new ConcurrentHashMap<>();
    private final Child noLabelsChild;

    private SketchesSummary(Builder b) {
        this.name = b.name;
        this.help = b.help == null ? b.name : b.help;
        this.labelNames = Collections.unmodifiableList(new ArrayList<>(b.labelNames));
        this.quantiles = Collections.unmodifiableList(new ArrayList<>(b.quantiles));
        if (this.labelNames.isEmpty()) {
            this.noLabelsChild = new Child(this.quantiles);
            this.children.put(Collections.<String>emptyList(), this.noLabelsChild);
        } else {
            this.noLabelsChild = null;
        }
    }

    /** Observe a value on the no-labels child. */
    public void observe(double amt) {
        if (noLabelsChild == null) {
            throw new IllegalStateException("Summary has labels; use labels(...).observe()");
        }
        noLabelsChild.observe(amt);
    }

    /** Get or create the child for the given label values. */
    public Child labels(String... values) {
        if (values.length != labelNames.size()) {
            throw new IllegalArgumentException(
                "Incorrect number of label values: expected " + labelNames.size() + " got " + values.length);
        }
        List<String> key = Collections.unmodifiableList(Arrays.asList(values.clone()));
        Child existing = children.get(key);
        if (existing != null) {
            return existing;
        }
        return children.computeIfAbsent(key, k -> new Child(quantiles));
    }

    /**
     * Aggregate per-thread sketches across all children into their result sketches. Must be called
     * periodically (the {@link PrometheusMetricsProvider} schedules this) so that quantile values
     * become visible to scrapers.
     */
    public void rotate() {
        for (Child child : children.values()) {
            child.rotate();
        }
    }

    /** Register this collector and return it (fluent style). */
    public SketchesSummary register(PrometheusRegistry registry) {
        registry.register(this);
        return this;
    }

    @Override
    public MetricSnapshot collect() {
        List<SummarySnapshot.SummaryDataPointSnapshot> dataPoints = new ArrayList<>(children.size());
        String[] labelNamesArray = labelNames.toArray(new String[0]);
        for (Map.Entry<List<String>, Child> entry : children.entrySet()) {
            Child child = entry.getValue();
            Child.Value value = child.get();
            List<Quantile> quantileList = new ArrayList<>(value.quantiles.size());
            for (Map.Entry<Double, Double> q : value.quantiles.entrySet()) {
                quantileList.add(new Quantile(q.getKey(), q.getValue()));
            }
            Labels labels = labelNames.isEmpty()
                    ? Labels.EMPTY
                    : Labels.of(labelNamesArray, entry.getKey().toArray(new String[0]));
            dataPoints.add(SummarySnapshot.SummaryDataPointSnapshot.builder()
                    .count((long) value.count)
                    .sum(value.sum)
                    .quantiles(Quantiles.of(quantileList))
                    .labels(labels)
                    .build());
        }
        return new SummarySnapshot(new MetricMetadata(name, help), dataPoints);
    }

    @Override
    public String getPrometheusName() {
        return name;
    }

    public static Builder build(String name, String help) {
        return new Builder().name(name).help(help);
    }

    public static Builder build() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String help;
        private final List<String> labelNames = new ArrayList<>();
        private final List<Double> quantiles = new ArrayList<>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder help(String help) {
            this.help = help;
            return this;
        }

        public Builder labelNames(String... labelNames) {
            for (String label : labelNames) {
                if ("quantile".equals(label)) {
                    throw new IllegalArgumentException("Summary cannot have a label named 'quantile'.");
                }
                this.labelNames.add(label);
            }
            return this;
        }

        public Builder quantile(double quantile) {
            if (quantile < 0.0 || quantile > 1.0) {
                throw new IllegalArgumentException(
                    "Quantile " + quantile + " invalid: Expected number between 0.0 and 1.0.");
            }
            quantiles.add(quantile);
            return this;
        }

        public SketchesSummary create() {
            if (name == null) {
                throw new IllegalStateException("SketchesSummary name must be set");
            }
            return new SketchesSummary(this);
        }

        public SketchesSummary register(PrometheusRegistry registry) {
            return create().register(registry);
        }
    }

    /** A single child (per label-values combination). */
    public static class Child {

        public static class Value {
            public final double count;
            public final double sum;
            public final SortedMap<Double, Double> quantiles;

            private Value(double count, double sum, List<Double> quantiles, DoublesSketch doublesSketch) {
                this.count = count;
                this.sum = sum;
                this.quantiles = Collections.unmodifiableSortedMap(snapshot(quantiles, doublesSketch));
            }

            private SortedMap<Double, Double> snapshot(List<Double> quantiles, DoublesSketch doublesSketch) {
                SortedMap<Double, Double> result = new TreeMap<>();
                for (Double q : quantiles) {
                    result.put(q, doublesSketch != null && !doublesSketch.isEmpty()
                            ? doublesSketch.getQuantile(q) : Double.NaN);
                }
                return result;
            }
        }

        // Having these separate leaves us open to races, however Prometheus as a whole has other
        // races that mean adding atomicity here wouldn't be useful. This should be reevaluated in
        // the future.
        private final DoubleAdder count = new DoubleAdder();
        private final DoubleAdder sum = new DoubleAdder();
        private final List<Double> quantiles;
        /*
         * Use 2 rotating thread local accessors so that we can safely swap them.
         */
        private volatile ThreadLocalAccessor current;
        private volatile ThreadLocalAccessor replacement;
        private volatile DoublesSketch result;

        private Child(List<Double> quantiles) {
            this.quantiles = quantiles;
            this.current = new ThreadLocalAccessor();
            this.replacement = new ThreadLocalAccessor();
        }

        public void observe(double amt) {
            count.add(1);
            sum.add(amt);
            LocalSketch localSketch = current.localData.get();
            long stamp = localSketch.lock.readLock();
            try {
                localSketch.sketch.update(amt);
            } finally {
                localSketch.lock.unlockRead(stamp);
            }
        }

        public void rotate() {
            ThreadLocalAccessor swap = current;
            current = replacement;
            replacement = swap;
            List<Thread> deadThreads = new ArrayList<>();
            final DoublesUnion aggregated = new DoublesUnionBuilder().build();
            swap.map.forEach((thread, localSketch) -> {
                long stamp = localSketch.lock.writeLock();
                try {
                    aggregated.union(localSketch.sketch);
                    localSketch.sketch.reset();
                    if (!thread.isAlive()) {
                        deadThreads.add(thread);
                    }
                } finally {
                    localSketch.lock.unlockWrite(stamp);
                }
            });
            for (Thread deadThread : deadThreads) {
                swap.map.remove(deadThread);
                current.map.remove(deadThread);
            }
            result = aggregated.getResultAndReset();
        }

        public Value get() {
            return new Value(count.sum(), sum.sum(), quantiles, result);
        }
    }

    private static class ThreadLocalAccessor {
        private final Map<Thread, LocalSketch> map = new ConcurrentHashMap<>();
        private final ThreadLocal<LocalSketch> localData = ThreadLocal.withInitial(() -> {
            LocalSketch sketch = new LocalSketch();
            map.put(Thread.currentThread(), sketch);
            return sketch;
        });
    }

    private static class LocalSketch {
        private final DoublesSketch sketch = DoublesSketch.builder().build();
        private final StampedLock lock = new StampedLock();
    }
}
