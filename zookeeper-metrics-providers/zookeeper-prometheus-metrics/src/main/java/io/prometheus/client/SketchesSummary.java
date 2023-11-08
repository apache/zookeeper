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

package io.prometheus.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.StampedLock;
import org.apache.datasketches.quantiles.DoublesSketch;
import org.apache.datasketches.quantiles.DoublesUnion;
import org.apache.datasketches.quantiles.DoublesUnionBuilder;

/**
 * A DataSketches based Summary metric, aim for lock-free and low gc overhead.
 */
public class SketchesSummary extends SimpleCollector<SketchesSummary.Child> implements Counter.Describable {

    final List<Double> quantiles;

    SketchesSummary(SketchesSummary.Builder b) {
        super(b);
        this.quantiles = b.quantiles;
        initializeNoLabelsChild();
    }

    public static class Builder extends SimpleCollector.Builder<Builder, SketchesSummary> {

        private final List<Double> quantiles = new ArrayList<>();

        public Builder quantile(double quantile) {
            if (quantile < 0.0 || quantile > 1.0) {
                throw new IllegalArgumentException("Quantile " + quantile
                        + " invalid: Expected number between 0.0 and 1.0.");
            }
            quantiles.add(quantile);
            return this;
        }

        @Override
        public SketchesSummary create() {
            for (String label : labelNames) {
                if (label.equals("quantile")) {
                    throw new IllegalStateException("Summary cannot have a label named 'quantile'.");
                }
            }
            dontInitializeNoLabelsChild = true;
            return new SketchesSummary(this);
        }
    }

    /**
     *  Return a Builder to allow configuration of a new Summary. Ensures required fields are provided.
     *
     *  @param name The name of the metric
     *  @param help The help string of the metric
     */
    public static Builder build(String name, String help) {
        return new Builder().name(name).help(help);
    }

    /**
     *  Return a Builder to allow configuration of a new Summary.
     */
    public static Builder build() {
        return new Builder();
    }

    @Override
    protected Child newChild() {
        return new Child(quantiles);
    }

    /**
     * The value of a single Summary.
     *
     * <p>* <em>Warning:</em> References to a Child become invalid after using
     * {@link SimpleCollector#remove} or {@link SimpleCollector#clear}.
     */
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
                SortedMap<Double, Double> result = new TreeMap<Double, Double>();
                for (Double q : quantiles) {
                    result.put(q, doublesSketch != null && !doublesSketch.isEmpty() ? doublesSketch.getQuantile(q)
                            : Double.NaN);
                }
                return result;
            }
        }

        // Having these separate leaves us open to races,
        // however Prometheus as whole has other races
        // that mean adding atomicity here wouldn't be useful.
        // This should be reevaluated in the future.
        private final DoubleAdder count = new DoubleAdder();
        private final DoubleAdder sum = new DoubleAdder();
        private final List<Double> quantiles;
        /*
         * Use 2 rotating thread local accessor so that we can safely swap them.
         */
        private volatile ThreadLocalAccessor current;
        private volatile ThreadLocalAccessor replacement;
        private volatile DoublesSketch result;

        private Child(List<Double> quantiles) {
            this.quantiles = quantiles;
            this.current = new ThreadLocalAccessor();
            this.replacement = new ThreadLocalAccessor();
        }

        /**
         * Observe the given amount.
         */
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

        /**
         * Get the value of the Summary.
         *
         * <p>* <em>Warning:</em> The definition of {@link Child.Value} is subject to change.
         */
        public Child.Value get() {
            return new Child.Value(count.sum(), sum.sum(), quantiles, result);
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

    // Convenience methods.
    /**
     * Observe the given amount on the summary with no labels.
     */
    public void observe(double amt) {
        noLabelsChild.observe(amt);
    }

    public void rotate() {
        for (Child child : children.values()) {
            child.rotate();
        }
    }

    /**
     * Get the value of the Summary.
     *
     * <p>* <em>Warning:</em> The definition of {@link Child.Value} is subject to change.
     */
    public Child.Value get() {
        return noLabelsChild.get();
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples.Sample> samples = new ArrayList<MetricFamilySamples.Sample>();
        for (Map.Entry<List<String>, Child> c: children.entrySet()) {
            Child.Value v = c.getValue().get();
            List<String> labelNamesWithQuantile = new ArrayList<String>(labelNames);
            labelNamesWithQuantile.add("quantile");
            for (Map.Entry<Double, Double> q : v.quantiles.entrySet()) {
                List<String> labelValuesWithQuantile = new ArrayList<String>(c.getKey());
                labelValuesWithQuantile.add(doubleToGoString(q.getKey()));
                samples.add(new MetricFamilySamples.Sample(fullname, labelNamesWithQuantile, labelValuesWithQuantile,
                        q.getValue()));
            }
            samples.add(new MetricFamilySamples.Sample(fullname + "_count", labelNames, c.getKey(), v.count));
            samples.add(new MetricFamilySamples.Sample(fullname + "_sum", labelNames, c.getKey(), v.sum));
        }

        return familySamplesList(Type.SUMMARY, samples);
    }

    @Override
    public List<MetricFamilySamples> describe() {
        return Collections.<MetricFamilySamples>singletonList(new SummaryMetricFamily(fullname, help, labelNames));
    }

}
