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

import static org.junit.Assert.assertEquals;
import java.util.Map;
import org.apache.zookeeper.ZKTestCase;
import org.junit.Before;
import org.junit.Test;

public class AvgMinMaxPercentileCounterTest extends ZKTestCase {

    private AvgMinMaxPercentileCounter testCounter;

    @Before
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

        assertEquals("There should be 9 values in the set", 9, values.size());

        assertEquals("should avg=0", 0D, values.get("avg_test"));
        assertEquals("should have min=0", 0L, values.get("min_test"));
        assertEquals("should have max=0", 0L, values.get("max_test"));
        assertEquals("should have cnt=0", 0L, values.get("cnt_test"));
        assertEquals("should have sum=0", 0L, values.get("sum_test"));
        assertEquals("should have p50=0", 0L, values.get("p50_test"));
        assertEquals("should have p95=0", 0L, values.get("p95_test"));
        assertEquals("should have p99=0", 0L, values.get("p99_test"));
        assertEquals("should have p999=0", 0L, values.get("p999_test"));
    }

    @Test
    public void testValues() {
        addDataPoints();
        Map<String, Object> values = testCounter.values();

        assertEquals("There should be 9 values in the set", 9, values.size());

        assertEquals("should avg=499.5", 999D / 2, values.get("avg_test"));
        assertEquals("should have min=0", 0L, values.get("min_test"));
        assertEquals("should have max=999", 999L, values.get("max_test"));
        assertEquals("should have cnt=1000", 1000L, values.get("cnt_test"));
        assertEquals("should have sum=999*500", 999 * 500L, values.get("sum_test"));
        assertEquals("should have p50=500", 500L, values.get("p50_test"));
        assertEquals("should have p95=950", 950L, values.get("p95_test"));
        assertEquals("should have p99=990", 990L, values.get("p99_test"));
        assertEquals("should have p999=999", 999L, values.get("p999_test"));
    }

}
