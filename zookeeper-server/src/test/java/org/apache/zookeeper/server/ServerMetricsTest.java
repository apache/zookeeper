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

package org.apache.zookeeper.server;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.metric.AvgMinMaxCounter;
import org.apache.zookeeper.server.metric.SimpleCounter;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class ServerMetricsTest extends ZKTestCase {
    private static final int RANDOM_TRIALS = 100;
    private static final int RANDOM_SIZE = 100;

    private long[] generateRandomValues(int size) {
        // Clamp range to prevent overflow in metric aggregation
        final long[] values = new long[size];
        if (size == 0) {
            return values;
        }
        final long rangeMin = Long.MIN_VALUE / size;
        final long rangeMax = Long.MAX_VALUE / size;
        for (int i = 0; i < size; ++i) {
            values[i] = ThreadLocalRandom.current().nextLong(rangeMin, rangeMax);
        }
        return values;
    }

    @Test
    public void testAvgMinMaxCounter() {
        final AvgMinMaxCounter metric = new AvgMinMaxCounter("test");
        testAvgMinMaxCounter(metric, 0);
        testAvgMinMaxCounter(metric, 1);
        for (int i = 0; i < RANDOM_TRIALS; ++i) {
            testAvgMinMaxCounter(metric, RANDOM_SIZE);
        }
    }

    private void testAvgMinMaxCounter(AvgMinMaxCounter metric, int size) {
        final long[] values = generateRandomValues(size);
        for (long value : values) {
            metric.add(value);
        }
        long expectedMin = Arrays.stream(values).min().orElse(0);
        long expectedMax = Arrays.stream(values).max().orElse(0);
        long expectedSum = Arrays.stream(values).sum();
        long expectedCnt = values.length;
        double expectedAvg = expectedSum / Math.max(1, expectedCnt);

        Assert.assertEquals(expectedAvg, metric.getAvg(), (double)200);
        Assert.assertEquals(expectedMin, metric.getMin());
        Assert.assertEquals(expectedMax, metric.getMax());
        Assert.assertEquals(expectedCnt, metric.getCount());
        Assert.assertEquals(expectedSum, metric.getTotal());

        final Map<String, Object> results = metric.values();
        Assert.assertEquals(expectedMax, (long)results.get("max_test"));
        Assert.assertEquals(expectedMin, (long)results.get("min_test"));
        Assert.assertEquals(expectedCnt, (long)results.get("cnt_test"));
        Assert.assertEquals(expectedAvg, (double)results.get("avg_test"), (double)200);

        metric.reset();
    }

    @Test
    public void testSimpleCounter() {
        SimpleCounter metric = new SimpleCounter("test");
        testSimpleCounter(metric, 0);
        testSimpleCounter(metric, 1);
        for (int i = 0; i < RANDOM_TRIALS; ++i) {
            testSimpleCounter(metric, RANDOM_SIZE);
        }
    }

    private void testSimpleCounter(SimpleCounter metric, int size) {
        final long[] values = generateRandomValues(size);
        for (long value : values) {
            metric.add(value);
        }

        long expectedCount = Arrays.stream(values).sum();
        Assert.assertEquals(expectedCount, metric.getCount());

        final Map<String, Object> results = metric.values();
        Assert.assertEquals(expectedCount, (long)results.get("test"));

        metric.reset();
    }
}
