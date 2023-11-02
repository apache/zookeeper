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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.SketchesSummary;
import java.util.Properties;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.metrics.Summary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * Tests about PrometheusSummary, make sure sketches rotation task works as expected.
 */
public class PrometheusMetricsSummaryRotationTest {

    private PrometheusMetricsProvider provider;

    private final int summaryRotateSeconds = 2;

    @BeforeEach
    public void setup() throws Exception {
        CollectorRegistry.defaultRegistry.clear();
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("httpHost", "127.0.0.1"); // local host for test
        configuration.setProperty("httpPort", "0"); // ephemeral port
        configuration.setProperty("exportJvmInfo", "false");
        configuration.setProperty("prometheusMetricsSummaryRotateSeconds", String.valueOf(summaryRotateSeconds));
        provider.configure(configuration);
        provider.start();
    }

    @AfterEach
    public void tearDown() {
        if (provider != null) {
            provider.stop();
        }
        CollectorRegistry.defaultRegistry.clear();
    }

    @Test
    void testSummaryRotation() throws InterruptedException {
        Summary summary = provider.getRootContext().getSummary("rotation_" + System.currentTimeMillis(),
                MetricsContext.DetailLevel.ADVANCED);
        for (int i = 0; i < 10; i++) {
            summary.add(10);
        }
        SketchesSummary innerSummary = ((PrometheusMetricsProvider.PrometheusSummary) summary).inner;
        boolean assertionFailed = true;
        int timeout = summaryRotateSeconds + 1;
        for (int i = 0; i < timeout && assertionFailed; i++) {
            SortedMap<Double, Double> quantiles = innerSummary.get().quantiles;
            try {
                assertEquals(10, quantiles.get(0.5));
                assertEquals(10, quantiles.get(0.9));
                assertEquals(10, quantiles.get(0.99));
                assertionFailed = false;
            } catch (AssertionFailedError e) {
                TimeUnit.SECONDS.sleep(1);
            }
        }
        if (assertionFailed) {
            fail("Quantiles not updated after " + timeout + "seconds, it is likely that the summary is not rotated.");
        }
    }
}
