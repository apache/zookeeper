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

import static java.util.Arrays.binarySearch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.prometheus.client.SketchesSummary;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests about SketchesSummary, make sure the quantile error is within the expected range.
 */
public class SketchesSummaryTest {

    @Test
    void testQuantileError() {
        SketchesSummary summary = SketchesSummary.build("test", "test help")
                .quantile(0.5)
                .quantile(0.9)
                .quantile(0.99)
                .create();
        Random random = new Random();
        int[] samples = new int[1_000];
        double sum = 0;
        for (int i = 0; i < samples.length; i++) {
            int sample = random.nextInt(1_000_000) + 1;
            summary.observe(sample);
            sum += sample;
            samples[i] = sample;
        }
        Arrays.sort(samples);
        summary.rotate();
        SketchesSummary.Child.Value value = summary.get();
        assertEquals(samples.length, value.count);
        assertEquals(sum, value.sum);
        // The default k of DoublesSketches is 128, rank error of about 1.7%.
        // See more: org.apache.datasketches.quantiles.DoublesSketch
        assertQuantileError(samples, value.quantiles, 0.017);
    }

    @Test
    public void testEmptySketch() {
        SketchesSummary summary = SketchesSummary.build("testEmptySketch", "test help")
                .quantile(0.5)
                .quantile(0.9)
                .quantile(0.99)
                .create();
        summary.observe(10);
        summary.rotate();
        summary.observe(10);
        summary.rotate();
        summary.rotate();
        for (Double quantile : summary.get().quantiles.values()) {
            assertTrue(Double.isNaN(quantile));
        }
    }

    private static void assertQuantileError(int[] samples, SortedMap<Double, Double> quantiles, double delta) {
        for (Map.Entry<Double, Double> entry : quantiles.entrySet()) {
            double quantile = entry.getKey();
            int expected = (int) (samples.length * quantile - 1);
            int actual = binarySearch(samples, entry.getValue().intValue());
            if (Math.abs(expected - actual) > samples.length * delta) {
                Assertions.fail(String.format("expected error delta: %s, actual: %s abs(%s-%s)",
                        delta, Math.abs(expected - actual), expected, actual));
            }
        }
    }
}
