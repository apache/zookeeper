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

import org.apache.zookeeper.ZKTestCase;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

public class AvgMinMaxCounterSetTest extends ZKTestCase {
    private AvgMinMaxCounterSet testCounterSet;

    @Before
    public void initCounter() {
        testCounterSet = new AvgMinMaxCounterSet("test");
    }

    private void addDataPoints() {
        testCounterSet.add("key1", 0);
        testCounterSet.add("key1", 1);
        testCounterSet.add("key2", 2);
        testCounterSet.add("key2", 3);
        testCounterSet.add("key2", 4);
        testCounterSet.add("key2", 5);
    }

    @Test
    public void testReset() {
        addDataPoints();
        testCounterSet.reset();

        Map<String, Object> values = testCounterSet.values();

        Assert.assertEquals("There should be 10 values in the set", 10, values.size());

        Assert.assertEquals("avg_key1_test should =0", 0D, values.get("avg_key1_test"));
        Assert.assertEquals("min_key1_test should =0", 0L, values.get("min_key1_test"));
        Assert.assertEquals("max_key1_test should =0", 0L, values.get("max_key1_test"));
        Assert.assertEquals("cnt_key1_test should =0", 0L, values.get("cnt_key1_test"));
        Assert.assertEquals("sum_key1_test should =0", 0L, values.get("sum_key1_test"));

        Assert.assertEquals("avg_key2_test should =0", 0D, values.get("avg_key2_test"));
        Assert.assertEquals("min_key2_test should =0", 0L, values.get("min_key2_test"));
        Assert.assertEquals("max_key2_test should =0", 0L, values.get("max_key2_test"));
        Assert.assertEquals("cnt_key2_test should =0", 0L, values.get("cnt_key2_test"));
        Assert.assertEquals("sum_key2_test should =0", 0L, values.get("sum_key2_test"));

    }

    @Test
    public void testValues() {
        addDataPoints();
        Map<String, Object> values = testCounterSet.values();

        Assert.assertEquals("There should be 10 values in the set", 10, values.size());
        Assert.assertEquals("avg_key1_test should =0.5", 0.5D, values.get("avg_key1_test"));
        Assert.assertEquals("min_key1_test should =0", 0L, values.get("min_key1_test"));
        Assert.assertEquals("max_key1_test should =1", 1L, values.get("max_key1_test"));
        Assert.assertEquals("cnt_key1_test should =2", 2L, values.get("cnt_key1_test"));
        Assert.assertEquals("sum_key1_test should =1", 1L, values.get("sum_key1_test"));

        Assert.assertEquals("avg_key2_test should =3.5", 3.5, values.get("avg_key2_test"));
        Assert.assertEquals("min_key2_test should =2", 2L, values.get("min_key2_test"));
        Assert.assertEquals("max_key2_test should =5", 5L, values.get("max_key2_test"));
        Assert.assertEquals("cnt_key2_test should =4", 4L, values.get("cnt_key2_test"));
        Assert.assertEquals("sum_key2_test should =14", 14L, values.get("sum_key2_test"));
    }
}
