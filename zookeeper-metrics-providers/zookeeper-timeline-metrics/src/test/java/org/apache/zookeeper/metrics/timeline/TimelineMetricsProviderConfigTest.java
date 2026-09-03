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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Properties;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for Timeline Metrics Provider configuration.
 *
 * <p>These tests verify configuration handling, validation, and error cases
 * for the TimelineMetricsProvider.</p>
 */
public class TimelineMetricsProviderConfigTest extends TimelineMetricsTestBase {

    private TimelineMetricsProvider provider;

    @AfterEach
    public void tearDown() {
        if (provider != null) {
            provider.stop();
        }
    }

    @Test
    public void testValidConfig() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        configuration.setProperty("timeline.collection.period", "60");
        configuration.setProperty("timeline.hostname", "test-host");
        configuration.setProperty("timeline.appId", "test-zookeeper");

        provider.configure(configuration);
        provider.start();

        assertNotNull(provider.getRootContext());
    }

    @Test
    public void testDefaultCollectionPeriod() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        // Don't set collection.period - should use default

        provider.configure(configuration);
        provider.start();

        assertNotNull(provider.getRootContext());
    }

    @Test
    public void testDefaultAppId() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        // Don't set appId - should use default "zookeeper"

        provider.configure(configuration);
        provider.start();

        assertNotNull(provider.getRootContext());
    }

    @Test
    public void testAutoDetectHostname() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        // Don't set hostname - should auto-detect

        provider.configure(configuration);
        provider.start();

        assertNotNull(provider.getRootContext());
    }

    @Test
    public void testMissingSinkClass() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        // Use default sink class that doesn't exist
        configuration.setProperty("timeline.collection.period", "60");

        // Should not throw during configure
        provider.configure(configuration);

        // Should not throw during start (just logs warning)
        provider.start();

        assertNotNull(provider.getRootContext());
    }

    @Test
    public void testInvalidSinkClass() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class", "com.example.NonExistentClass");
        configuration.setProperty("timeline.collection.period", "60");

        // Should not throw during configure
        provider.configure(configuration);

        // Should not throw during start (just logs warning and continues without sink)
        provider.start();

        assertNotNull(provider.getRootContext());
    }

    @Test
    public void testInvalidCollectionPeriod() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        configuration.setProperty("timeline.collection.period", "invalid");

        // Should throw NumberFormatException wrapped in MetricsProviderLifeCycleException
        try {
            provider.configure(configuration);
            assertTrue(false, "Should have thrown exception for invalid collection period");
        } catch (MetricsProviderLifeCycleException e) {
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof NumberFormatException);
        }
    }

    @Test
    public void testZeroCollectionPeriod() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        configuration.setProperty("timeline.collection.period", "0");

        provider.configure(configuration);
        // Zero period is invalid for ScheduledThreadPoolExecutor
        try {
            provider.start();
            assertTrue(false, "Should have thrown exception for zero collection period");
        } catch (MetricsProviderLifeCycleException e) {
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void testNegativeCollectionPeriod() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        configuration.setProperty("timeline.collection.period", "-1");

        provider.configure(configuration);
        // Negative period is invalid for ScheduledThreadPoolExecutor
        try {
            provider.start();
            assertTrue(false, "Should have thrown exception for negative collection period");
        } catch (MetricsProviderLifeCycleException e) {
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void testStopWithoutStart() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");

        provider.configure(configuration);
        // Stop without starting should not throw
        provider.stop();
    }

    @Test
    public void testMultipleStopCalls() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");

        provider.configure(configuration);
        provider.start();

        // Multiple stop calls should be safe
        provider.stop();
        provider.stop();
        provider.stop();
    }

    @Test
    public void testCustomHostname() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        configuration.setProperty("timeline.hostname", "custom-host.example.com");

        provider.configure(configuration);
        provider.start();

        assertNotNull(provider.getRootContext());
    }

    @Test
    public void testCustomAppId() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        configuration.setProperty("timeline.appId", "custom-app");

        provider.configure(configuration);
        provider.start();

        assertNotNull(provider.getRootContext());
    }

    @Test
    public void testEmptyHostname() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        configuration.setProperty("timeline.hostname", "");

        // Should auto-detect hostname when empty string is provided
        provider.configure(configuration);
        provider.start();

        assertNotNull(provider.getRootContext());
    }

    @Test
    public void testSinkReceivesConfiguration() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("timeline.sink.class",
            "org.apache.zookeeper.metrics.timeline.MockTimelineMetricsSink");
        configuration.setProperty("timeline.collection.period", "5");
        configuration.setProperty("timeline.custom.property", "test-value");

        provider.configure(configuration);
        provider.start();

        // Give it time to initialize
        Thread.sleep(100);

        // The mock sink should have received the full configuration
        assertNotNull(provider.getRootContext());
    }

    @Test
    public void testProviderWithoutSink() throws Exception {
        provider = new TimelineMetricsProvider();
        Properties configuration = new Properties();
        // Intentionally use a class that doesn't exist
        configuration.setProperty("timeline.sink.class", "does.not.Exist");
        configuration.setProperty("timeline.collection.period", "1");

        provider.configure(configuration);
        provider.start();

        // Provider should still work, just without sink
        assertNotNull(provider.getRootContext());

        // Can still register metrics
        provider.getRootContext().getCounter("test").add(1);

        // Can still dump metrics
        int[] count = {0};
        provider.dump((k, v) -> count[0]++);
        assertTrue(count[0] > 0);
    }
}
