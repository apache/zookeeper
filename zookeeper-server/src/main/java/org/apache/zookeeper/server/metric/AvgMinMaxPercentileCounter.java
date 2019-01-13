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
package org.apache.zookeeper.server.metric;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Reservoir;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.UniformSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;


/**
 * Generic long counter that keep track of min/max/avg/percentiles.
 * The counter is thread-safe
 */
public class AvgMinMaxPercentileCounter extends Metric  {

    private String name;
    private AvgMinMaxCounter counter;
    private final ResettableUniformReservoir reservoir;
    private final Histogram histogram;

    static class ResettableUniformReservoir implements Reservoir {

        private static final int DEFAULT_SIZE = 4096;
        private static final int BITS_PER_LONG = 63;

        private final AtomicLong count = new AtomicLong();
        private volatile AtomicLongArray values = new AtomicLongArray(DEFAULT_SIZE);

        @Override
        public int size() {
            final long c = count.get();
            if (c > values.length()) {
                return values.length();
            }
            return (int) c;
        }

        @Override
        public void update(long value) {
            final long c = count.incrementAndGet();
            if (c <= values.length()) {
                values.set((int) c - 1, value);
            } else {
                final long r = nextLong(c);
                if (r < values.length()) {
                    values.set((int) r, value);
                }
            }
        }

        private static long nextLong(long n) {
            long bits, val;
            do {
                bits = ThreadLocalRandom.current().nextLong() & (~(1L << BITS_PER_LONG));
                val = bits % n;
            } while (bits - val + (n - 1) < 0L);
            return val;
        }

        @Override
        public Snapshot getSnapshot() {
            final int s = size();
            final List<Long> copy = new ArrayList<Long>(s);
            for (int i = 0; i < s; i++) {
                copy.add(values.get(i));
            }
            return new UniformSnapshot(copy);
        }

        public void reset() {
            count.set(0);
            values = new AtomicLongArray(DEFAULT_SIZE);
        }
    }

    public AvgMinMaxPercentileCounter(String name) {

        this.name = name;
        this.counter = new AvgMinMaxCounter(this.name);
        reservoir = new ResettableUniformReservoir();
        histogram = new Histogram(reservoir);
    }

    public void addDataPoint(long value) {
        counter.add(value);
        histogram.update(value);
    }

    public void resetMax() {
        // To match existing behavior in upstream
        counter.resetMax();
    }

    public void reset() {
        counter.reset();
        reservoir.reset();
    }

    public void add(long value) {
        addDataPoint(value);
    }

    public Map<String, Object> values() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.putAll(counter.values());
        m.put("p50_" + name, Math.round(this.histogram.getSnapshot().getMedian()));
        m.put("p95_" + name, Math.round(this.histogram.getSnapshot().get95thPercentile()));
        m.put("p99_" + name, Math.round(this.histogram.getSnapshot().get99thPercentile()));
        m.put("p999_" + name, Math.round(this.histogram.getSnapshot().get999thPercentile()));
        return m;
    }
}
