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

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.prometheus.client.CollectorRegistry;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.zookeeper.metrics.Counter;
import org.apache.zookeeper.metrics.Gauge;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.metrics.Summary;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests about Prometheus Metrics Provider. Please note that we are not testing
 * Prometheus but only our integration.
 */
public class PrometheusMetricsProviderTest {

    private PrometheusMetricsProvider provider;

    @BeforeEach
    public void setup() throws Exception {
        CollectorRegistry.defaultRegistry.clear();
        provider = new PrometheusMetricsProvider();
        Properties configuration = new Properties();
        configuration.setProperty("httpPort", "0"); // ephemeral port
        configuration.setProperty("exportJvmInfo", "false");
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
    public void testCounters() throws Exception {
        Counter counter = provider.getRootContext().getCounter("cc");
        counter.add(10);
        int[] count = {0};
        provider.dump((k, v) -> {
            assertEquals("cc", k);
            assertEquals(10, ((Number) v).intValue());
            count[0]++;
        }
        );
        assertEquals(1, count[0]);
        count[0] = 0;

        // this is not allowed but it must not throw errors
        counter.add(-1);

        provider.dump((k, v) -> {
            assertEquals("cc", k);
            assertEquals(10, ((Number) v).intValue());
            count[0]++;
        }
        );
        assertEquals(1, count[0]);

        // we always must get the same object
        assertSame(counter, provider.getRootContext().getCounter("cc"));

        String res = callServlet();
        assertThat(res, CoreMatchers.containsString("# TYPE cc counter"));
        assertThat(res, CoreMatchers.containsString("cc 10.0"));
    }

    @Test
    public void testGauge() throws Exception {
        int[] values = {78, -89};
        int[] callCounts = {0, 0};
        Gauge gauge0 = () -> {
            callCounts[0]++;
            return values[0];
        };
        Gauge gauge1 = () -> {
            callCounts[1]++;
            return values[1];
        };
        provider.getRootContext().registerGauge("gg", gauge0);

        int[] count = {0};
        provider.dump((k, v) -> {
            assertEquals("gg", k);
            assertEquals(values[0], ((Number) v).intValue());
            count[0]++;
        }
        );
        assertEquals(1, callCounts[0]);
        assertEquals(0, callCounts[1]);
        assertEquals(1, count[0]);
        count[0] = 0;
        String res2 = callServlet();
        assertThat(res2, CoreMatchers.containsString("# TYPE gg gauge"));
        assertThat(res2, CoreMatchers.containsString("gg 78.0"));

        provider.getRootContext().unregisterGauge("gg");
        provider.dump((k, v) -> {
            count[0]++;
        }
        );
        assertEquals(2, callCounts[0]);
        assertEquals(0, callCounts[1]);
        assertEquals(0, count[0]);
        String res3 = callServlet();
        assertTrue(res3.isEmpty());

        provider.getRootContext().registerGauge("gg", gauge1);

        provider.dump((k, v) -> {
            assertEquals("gg", k);
            assertEquals(values[1], ((Number) v).intValue());
            count[0]++;
        }
        );
        assertEquals(2, callCounts[0]);
        assertEquals(1, callCounts[1]);
        assertEquals(1, count[0]);
        count[0] = 0;

        String res4 = callServlet();
        assertThat(res4, CoreMatchers.containsString("# TYPE gg gauge"));
        assertThat(res4, CoreMatchers.containsString("gg -89.0"));
        assertEquals(2, callCounts[0]);
        // the servlet must sample the value again (from gauge1)
        assertEquals(2, callCounts[1]);

        // override gauge, without unregister
        provider.getRootContext().registerGauge("gg", gauge0);

        provider.dump((k, v) -> {
            count[0]++;
        }
        );
        assertEquals(1, count[0]);
        assertEquals(3, callCounts[0]);
        assertEquals(2, callCounts[1]);
    }

    @Test
    public void testBasicSummary() throws Exception {
        Summary summary = provider.getRootContext()
                .getSummary("cc", MetricsContext.DetailLevel.BASIC);
        summary.add(10);
        summary.add(10);
        int[] count = {0};
        provider.dump((k, v) -> {
            count[0]++;
            int value = ((Number) v).intValue();

            switch (k) {
                case "cc{quantile=\"0.5\"}":
                    assertEquals(10, value);
                    break;
                case "cc_count":
                    assertEquals(2, value);
                    break;
                case "cc_sum":
                    assertEquals(20, value);
                    break;
                default:
                    fail("unespected key " + k);
                    break;
            }
        }
        );
        assertEquals(3, count[0]);
        count[0] = 0;

        // we always must get the same object
        assertSame(summary, provider.getRootContext()
                .getSummary("cc", MetricsContext.DetailLevel.BASIC));

        try {
            provider.getRootContext()
                    .getSummary("cc", MetricsContext.DetailLevel.ADVANCED);
            fail("Can't get the same summary with a different DetailLevel");
        } catch (IllegalArgumentException err) {
            assertThat(err.getMessage(), containsString("Already registered"));
        }

        String res = callServlet();
        assertThat(res, containsString("# TYPE cc summary"));
        assertThat(res, CoreMatchers.containsString("cc_sum 20.0"));
        assertThat(res, CoreMatchers.containsString("cc_count 2.0"));
        assertThat(res, CoreMatchers.containsString("cc{quantile=\"0.5\",} 10.0"));
    }

    @Test
    public void testAdvancedSummary() throws Exception {
        Summary summary = provider.getRootContext()
                .getSummary("cc", MetricsContext.DetailLevel.ADVANCED);
        summary.add(10);
        summary.add(10);
        int[] count = {0};
        provider.dump((k, v) -> {
            count[0]++;
            int value = ((Number) v).intValue();

            switch (k) {
                case "cc{quantile=\"0.5\"}":
                    assertEquals(10, value);
                    break;
                case "cc{quantile=\"0.9\"}":
                    assertEquals(10, value);
                    break;
                case "cc{quantile=\"0.99\"}":
                    assertEquals(10, value);
                    break;
                case "cc_count":
                    assertEquals(2, value);
                    break;
                case "cc_sum":
                    assertEquals(20, value);
                    break;
                default:
                    fail("unespected key " + k);
                    break;
            }
        }
        );
        assertEquals(5, count[0]);
        count[0] = 0;

        // we always must get the same object
        assertSame(summary, provider.getRootContext()
                .getSummary("cc", MetricsContext.DetailLevel.ADVANCED));

        try {
            provider.getRootContext()
                    .getSummary("cc", MetricsContext.DetailLevel.BASIC);
            fail("Can't get the same summary with a different DetailLevel");
        } catch (IllegalArgumentException err) {
            assertThat(err.getMessage(), containsString("Already registered"));
        }

        String res = callServlet();
        assertThat(res, containsString("# TYPE cc summary"));
        assertThat(res, CoreMatchers.containsString("cc_sum 20.0"));
        assertThat(res, CoreMatchers.containsString("cc_count 2.0"));
        assertThat(res, CoreMatchers.containsString("cc{quantile=\"0.5\",} 10.0"));
        assertThat(res, CoreMatchers.containsString("cc{quantile=\"0.9\",} 10.0"));
        assertThat(res, CoreMatchers.containsString("cc{quantile=\"0.99\",} 10.0"));
    }

    private String callServlet() throws ServletException, IOException {
        // we are not performing an HTTP request
        // but we are calling directly the servlet
        StringWriter writer = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(writer));
        HttpServletRequest req = mock(HttpServletRequest.class);
        provider.getServlet().doGet(req, response);
        String res = writer.toString();
        return res;
    }

}
