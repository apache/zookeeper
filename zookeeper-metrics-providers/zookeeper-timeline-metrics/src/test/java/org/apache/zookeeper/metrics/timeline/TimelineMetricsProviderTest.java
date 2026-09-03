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

package org.apache.zookeeper.metrics.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.metrics.Counter;
import org.apache.zookeeper.metrics.CounterSet;
import org.apache.zookeeper.metrics.Gauge;
import org.apache.zookeeper.metrics.GaugeSet;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.metrics.Summary;
import org.apache.zookeeper.metrics.SummarySet;
import org.apache.zookeeper.server.util.QuotaMetricsUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for Timeline Metrics Provider.
 *
 * <p>These tests verify the functionality of the TimelineMetricsProvider including
 * metric registration, collection, and export to Timeline sinks.</p>
 */
public class TimelineMetricsProviderTest extends TimelineMetricsTestBase {

    private TimelineMetricsProvider provider;
    private MockTimelineMetricsSink mockSink;

    @BeforeEach
    public void setup() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        configuration.setProperty("timeline.collection.period", "1"); // 1 second for fast tests
        configuration.setProperty("timeline.hostname", "test-host");
        configuration.setProperty("timeline.appId", "test-zookeeper");
        provider.configure(configuration);
        provider.start();

        // Give the provider time to start and get the sink reference
        Thread.sleep(100);
    }

    @AfterEach
    public void tearDown() {
        if (provider != null) {
            provider.stop();
        }
    }

    @Test
    public void testCounters() throws Exception {
        Counter counter = provider.getRootContext().getCounter("test_counter");
        counter.add(10);

        int[] count = { 0 };
        provider.dump((k, v) -> {
            if (k.equals("test_counter")) {
                assertEquals(10, ((Number) v).intValue());
                count[0]++;
            }
        });
        assertEquals(1, count[0]);

        // Adding more should work
        counter.add(5);
        count[0] = 0;
        provider.dump((k, v) -> {
            if (k.equals("test_counter")) {
                assertEquals(15, ((Number) v).intValue());
                count[0]++;
            }
        });
        assertEquals(1, count[0]);

        // We always must get the same object
        assertSame(counter, provider.getRootContext().getCounter("test_counter"));
    }

    @Test
    public void testCounterSet() throws Exception {
        final String name = QuotaMetricsUtils.QUOTA_EXCEEDED_ERROR_PER_NAMESPACE;
        final CounterSet counterSet = provider.getRootContext().getCounterSet(name);
        final String[] keys = { "ns1", "ns2" };

        // Update counters
        counterSet.inc("ns1");
        counterSet.add("ns1", 2);
        counterSet.inc("ns2");

        // Validate with dump - CounterSet uses format "key_name" based on SimpleCounterSet
        final Map<String, Number> expectedMetrics = new HashMap<>();
        expectedMetrics.put("ns1_" + name, 3L);
        expectedMetrics.put("ns2_" + name, 1L);

        validateWithDump(expectedMetrics);

        // Validate registering with same name returns same object
        assertSame(counterSet, provider.getRootContext().getCounterSet(name));
    }

    @Test
    public void testCounterSet_nullName() {
        assertThrows(NullPointerException.class,
            () -> provider.getRootContext().getCounterSet(null));
    }

    @Test
    public void testGauge() throws Exception {
        int[] values = { 78, -89 };
        int[] callCounts = { 0, 0 };

        Gauge gauge0 = () -> {
            callCounts[0]++;
            return values[0];
        };

        Gauge gauge1 = () -> {
            callCounts[1]++;
            return values[1];
        };

        provider.getRootContext().registerGauge("test_gauge", gauge0);

        int[] count = { 0 };
        provider.dump((k, v) -> {
            if (k.equals("test_gauge")) {
                assertEquals(values[0], ((Number) v).intValue());
                count[0]++;
            }
        });
        assertEquals(1, callCounts[0]);
        assertEquals(0, callCounts[1]);
        assertEquals(1, count[0]);

        // Unregister and verify
        provider.getRootContext().unregisterGauge("test_gauge");
        count[0] = 0;
        provider.dump((k, v) -> {
            if (k.equals("test_gauge")) {
                count[0]++;
            }
        });
        assertEquals(0, count[0]);

        // Register new gauge
        provider.getRootContext().registerGauge("test_gauge", gauge1);
        count[0] = 0;
        provider.dump((k, v) -> {
            if (k.equals("test_gauge")) {
                assertEquals(values[1], ((Number) v).intValue());
                count[0]++;
            }
        });
        assertEquals(1, callCounts[1]);
        assertEquals(1, count[0]);
    }

    @Test
    public void testGauge_nullGauge() {
        assertThrows(NullPointerException.class,
            () -> provider.getRootContext().registerGauge("test", null));
    }

    @Test
    public void testBasicSummary() throws Exception {
        Summary summary = provider.getRootContext().getSummary("test_summary",
            MetricsContext.DetailLevel.BASIC);
        summary.add(10);
        summary.add(20);
        summary.add(30);

        int[] count = { 0 };
        provider.dump((k, v) -> {
            count[0]++;
            double value = ((Number) v).doubleValue();

            // AvgMinMaxCounter formats keys as "prefix_name", e.g., "avg_test_summary"
            switch (k) {
                case "avg_test_summary":
                    assertEquals(20.0, value, 0.01);
                    break;
                case "min_test_summary":
                    assertEquals(10.0, value, 0.01);
                    break;
                case "max_test_summary":
                    assertEquals(30.0, value, 0.01);
                    break;
                case "cnt_test_summary":
                    assertEquals(3.0, value, 0.01);
                    break;
                case "sum_test_summary":
                    assertEquals(60.0, value, 0.01);
                    break;
            }
        });
        assertTrue(count[0] >= 5); // Should have at least avg, min, max, cnt, sum

        // We always must get the same object
        assertSame(summary, provider.getRootContext().getSummary("test_summary",
            MetricsContext.DetailLevel.BASIC));

        // Cannot get same name with different detail level
        assertThrows(IllegalArgumentException.class,
            () -> provider.getRootContext().getSummary("test_summary",
                MetricsContext.DetailLevel.ADVANCED));
    }

    @Test
    public void testAdvancedSummary() throws Exception {
        Summary summary = provider.getRootContext().getSummary("test_advanced_summary",
            MetricsContext.DetailLevel.ADVANCED);

        // Add values
        for (int i = 1; i <= 100; i++) {
            summary.add(i);
        }

        int[] count = { 0 };
        provider.dump((k, v) -> {
            if (k.contains("test_advanced_summary")) {
                count[0]++;
            }
        });

        // Should have avg, min, max, cnt, sum, and percentiles (p50, p95, p99, p999)
        // AvgMinMaxPercentileCounter provides 9 metrics total
        assertTrue(count[0] >= 5);

        // We always must get the same object
        assertSame(summary, provider.getRootContext().getSummary("test_advanced_summary",
            MetricsContext.DetailLevel.ADVANCED));

        // Cannot get same name with different detail level
        assertThrows(IllegalArgumentException.class,
            () -> provider.getRootContext().getSummary("test_advanced_summary",
                MetricsContext.DetailLevel.BASIC));
    }

    @Test
    public void testSummarySet() throws Exception {
        final String name = "test_summary_set";
        final String[] keys = { "key1", "key2" };

        final SummarySet summarySet = provider.getRootContext().getSummarySet(name,
            MetricsContext.DetailLevel.BASIC);

        // Update summaries
        summarySet.add("key1", 10);
        summarySet.add("key1", 20);
        summarySet.add("key2", 5);

        int[] count = { 0 };
        provider.dump((k, v) -> {
            if (k.contains("key1") || k.contains("key2")) {
                count[0]++;
            }
        });

        assertTrue(count[0] > 0);

        // Validate registering with same name returns same object
        assertSame(summarySet, provider.getRootContext().getSummarySet(name,
            MetricsContext.DetailLevel.BASIC));

        // Cannot get same name with different detail level
        assertThrows(IllegalArgumentException.class,
            () -> provider.getRootContext().getSummarySet(name,
                MetricsContext.DetailLevel.ADVANCED));
    }

    @Test
    public void testGaugeSet() throws Exception {
        final String name = QuotaMetricsUtils.QUOTA_BYTES_LIMIT_PER_NAMESPACE;
        final Map<String, Number> metricsMap = new HashMap<>();
        metricsMap.put("ns1", 100.0);
        metricsMap.put("ns2", 200.0);

        final AtomicInteger callCount = new AtomicInteger(0);
        final GaugeSet gaugeSet = () -> {
            callCount.incrementAndGet();
            return metricsMap;
        };

        provider.getRootContext().registerGaugeSet(name, gaugeSet);

        // Validate with dump
        int[] count = { 0 };
        provider.dump((k, v) -> {
            if (k.contains("ns1") || k.contains("ns2")) {
                count[0]++;
            }
        });

        assertTrue(count[0] >= 2);
        assertTrue(callCount.get() >= 1);

        // Unregister
        callCount.set(0);
        provider.getRootContext().unregisterGaugeSet(name);

        count[0] = 0;
        provider.dump((k, v) -> {
            if (k.contains(name)) {
                count[0]++;
            }
        });
        assertEquals(0, count[0]);
        assertEquals(0, callCount.get());
    }

    @Test
    public void testGaugeSet_nullName() {
        assertThrows(NullPointerException.class,
            () -> provider.getRootContext().registerGaugeSet(null, () -> null));
    }

    @Test
    public void testGaugeSet_nullGaugeSet() {
        assertThrows(NullPointerException.class,
            () -> provider.getRootContext().registerGaugeSet("test", null));
    }

    @Test
    public void testGaugeSet_unregisterNull() {
        assertThrows(NullPointerException.class,
            () -> provider.getRootContext().unregisterGaugeSet(null));
    }

    @Test
    public void testMetricCollection() throws Exception {
        // Register some metrics
        Counter counter = provider.getRootContext().getCounter("collection_test");
        counter.add(42);

        Gauge gauge = () -> 123;
        provider.getRootContext().registerGauge("collection_gauge", gauge);

        // Wait for at least one collection cycle (1 second + some buffer)
        Thread.sleep(1500);

        // Verify metrics were collected
        Map<String, Object> metrics = new HashMap<>();
        provider.dump(metrics::put);

        assertTrue(metrics.containsKey("collection_test"));
        assertEquals(42L, ((Number) metrics.get("collection_test")).longValue());
        assertTrue(metrics.containsKey("collection_gauge"));
        assertEquals(123, ((Number) metrics.get("collection_gauge")).intValue());
    }

    @Test
    public void testResetAllValues() throws Exception {
        Counter counter = provider.getRootContext().getCounter("reset_test");
        counter.add(100);

        Summary summary = provider.getRootContext().getSummary("reset_summary",
            MetricsContext.DetailLevel.BASIC);
        summary.add(50);

        // Verify initial values
        Map<String, Object> metrics = new HashMap<>();
        provider.dump(metrics::put);
        assertEquals(100L, ((Number) metrics.get("reset_test")).longValue());

        // Reset
        provider.resetAllValues();

        // Verify reset
        metrics.clear();
        provider.dump(metrics::put);
        assertEquals(0L, ((Number) metrics.get("reset_test")).longValue());
    }

    @Test
    public void testGetContext() {
        MetricsContext context1 = provider.getRootContext();
        MetricsContext context2 = provider.getRootContext().getContext("subcontext");

        // Timeline provider uses flat namespace, so sub-contexts return same context
        assertSame(context1, context2);
    }

    private void validateWithDump(final Map<String, Number> expectedMetrics) {
        final Map<String, Object> returnedMetrics = new HashMap<>();
        provider.dump(returnedMetrics::put);

        expectedMetrics.forEach((key, value) -> {
            assertTrue(returnedMetrics.containsKey(key),
                "Expected metric " + key + " not found");
            assertEquals(value.longValue(),
                ((Number) returnedMetrics.get(key)).longValue(),
                "Value mismatch for metric " + key);
        });
    }
}
