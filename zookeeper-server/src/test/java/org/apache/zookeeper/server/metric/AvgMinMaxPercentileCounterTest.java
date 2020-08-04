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

package org.apache.zookeeper.server.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AvgMinMaxPercentileCounterTest extends ZKTestCase {

    private AvgMinMaxPercentileCounter testCounter;

    @BeforeEach
    public void initCounter() {
        testCounter = new AvgMinMaxPercentileCounter("test");
    }

    private void addDataPoints() {
        for (int i = 0; i < 1000; i++) {
            testCounter.add(i);
        }
    }

    @Test
    public void testReset() {
        addDataPoints();
        testCounter.reset();

        Map<String, Object> values = testCounter.values();

        assertEquals(9, values.size(), "There should be 9 values in the set");

        assertEquals(0D, values.get("avg_test"), "should avg=0");
        assertEquals(0L, values.get("min_test"), "should have min=0");
        assertEquals(0L, values.get("max_test"), "should have max=0");
        assertEquals(0L, values.get("cnt_test"), "should have cnt=0");
        assertEquals(0L, values.get("sum_test"), "should have sum=0");
        assertEquals(0L, values.get("p50_test"), "should have p50=0");
        assertEquals(0L, values.get("p95_test"), "should have p95=0");
        assertEquals(0L, values.get("p99_test"), "should have p99=0");
        assertEquals(0L, values.get("p999_test"), "should have p999=0");
    }

    @Test
    public void testValues() {
        addDataPoints();
        Map<String, Object> values = testCounter.values();

        assertEquals(9, values.size(), "There should be 9 values in the set");

        assertEquals(999D / 2, values.get("avg_test"), "should avg=499.5");
        assertEquals(0L, values.get("min_test"), "should have min=0");
        assertEquals(999L, values.get("max_test"), "should have max=999");
        assertEquals(1000L, values.get("cnt_test"), "should have cnt=1000");
        assertEquals(999 * 500L, values.get("sum_test"), "should have sum=999*500");
        assertEquals(500L, values.get("p50_test"), "should have p50=500");
        assertEquals(950L, values.get("p95_test"), "should have p95=950");
        assertEquals(990L, values.get("p99_test"), "should have p99=990");
        assertEquals(999L, values.get("p999_test"), "should have p999=999");
    }

}
