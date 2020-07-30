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

public class AvgMinMaxPercentileCounterSetTest extends ZKTestCase {

    private AvgMinMaxPercentileCounterSet testCounterSet;

    @BeforeEach
    public void initCounter() {
        testCounterSet = new AvgMinMaxPercentileCounterSet("test");
    }

    private void addDataPoints() {
        for (int i = 0; i < 1000; i++) {
            testCounterSet.add("key1", i);
        }

        for (int i = 1000; i < 2000; i++) {
            testCounterSet.add("key2", i);
        }
    }

    @Test
    public void testReset() {
        addDataPoints();
        testCounterSet.reset();

        Map<String, Object> values = testCounterSet.values();

        assertEquals(0D, values.get("avg_key1_test"), "avg_key1_test should =0");
        assertEquals(0L, values.get("min_key1_test"), "min_key1_test should =0");
        assertEquals(0L, values.get("max_key1_test"), "max_key1_test should =0");
        assertEquals(0L, values.get("cnt_key1_test"), "cnt_key1_test should =0");
        assertEquals(0L, values.get("sum_key1_test"), "sum_key1_test should =0");
        assertEquals(0L, values.get("p50_key1_test"), "p50_key1_test should have p50=0");
        assertEquals(0L, values.get("p95_key1_test"), "p95_key1_test should have p95=0");
        assertEquals(0L, values.get("p99_key1_test"), "p99_key1_test should have p99=0");
        assertEquals(0L, values.get("p999_key1_test"), "p999_key1_test should have p999=0");

        assertEquals(0D, values.get("avg_key2_test"), "avg_key2_test should =0");
        assertEquals(0L, values.get("min_key2_test"), "min_key2_test should =0");
        assertEquals(0L, values.get("max_key2_test"), "max_key2_test should =0");
        assertEquals(0L, values.get("cnt_key2_test"), "cnt_key2_test should =0");
        assertEquals(0L, values.get("sum_key2_test"), "sum_key2_test should =0");
        assertEquals(0L, values.get("p50_key2_test"), "p50_key2_test should have p50=0");
        assertEquals(0L, values.get("p95_key2_test"), "p95_key2_test should have p95=0");
        assertEquals(0L, values.get("p99_key2_test"), "p99_key2_test should have p99=0");
        assertEquals(0L, values.get("p999_key2_test"), "p999_key2_test should have p999=0");
    }

    @Test
    public void testValues() {
        addDataPoints();
        Map<String, Object> values = testCounterSet.values();

        assertEquals(18, values.size(), "There should be 18 values in the set");

        assertEquals(999D / 2, values.get("avg_key1_test"), "avg_key1_test should =499.5");
        assertEquals(0L, values.get("min_key1_test"), "min_key1_test should =0");
        assertEquals(999L, values.get("max_key1_test"), "max_key1_test should =999");
        assertEquals(1000L, values.get("cnt_key1_test"), "cnt_key1_test should =1000");
        assertEquals(999 * 500L, values.get("sum_key1_test"), "sum_key1_test should =999*500");
        assertEquals(500L, values.get("p50_key1_test"), "p50_key1_test should have p50=500");
        assertEquals(950L, values.get("p95_key1_test"), "p95_key1_test should have p95=950");
        assertEquals(990L, values.get("p99_key1_test"), "p99_key1_test should have p99=990");
        assertEquals(999L, values.get("p999_key1_test"), "p999_key1_test should have p999=999");

        assertEquals(1000 + 999D / 2, values.get("avg_key2_test"), "avg_key2_test should =3.5");
        assertEquals(1000L, values.get("min_key2_test"), "min_key2_test should =2");
        assertEquals(1999L, values.get("max_key2_test"), "max_key2_test should =5");
        assertEquals(1000L, values.get("cnt_key2_test"), "cnt_key2_test should =4");
        assertEquals(2999 * 500L, values.get("sum_key2_test"), "sum_key2_test should =14");
        assertEquals(1500L, values.get("p50_key2_test"), "p50_key2_test should have p50=1500");
        assertEquals(1950L, values.get("p95_key2_test"), "p95_key2_test should have p95=1950");
        assertEquals(1990L, values.get("p99_key2_test"), "p99_key2_test should have p99=1990");
        assertEquals(1999L, values.get("p999_key2_test"), "p999_key2_test should have p999=1999");
    }

}
