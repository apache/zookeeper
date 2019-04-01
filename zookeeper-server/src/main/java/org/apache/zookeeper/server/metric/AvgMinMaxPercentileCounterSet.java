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

import java.lang.Integer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generic set of long counters that keep track of min/max/avg
 * for different keys.
 * The counter is thread-safe
 */
public class AvgMinMaxPercentileCounterSet extends Metric {
    private String name;

    private ConcurrentHashMap<String, AvgMinMaxPercentileCounter> counters = new ConcurrentHashMap<>();

    public AvgMinMaxPercentileCounterSet(String name) {
        this.name = name;
    }

    private AvgMinMaxPercentileCounter getCounterForKey(String key) {
        AvgMinMaxPercentileCounter counter = counters.get(key);
        if (counter == null) {
            counters.putIfAbsent(key, new AvgMinMaxPercentileCounter(key + "_" + name));
            counter = counters.get(key);
        }

        return counter;
    }

    public void addDataPoint(String key, long value) {
        getCounterForKey(key).addDataPoint(value);
    }

    public void resetMax() {
        for (Map.Entry<String, AvgMinMaxPercentileCounter> entry : counters.entrySet()) {
            entry.getValue().resetMax();
        }
    }

    public void reset() {
        for (Map.Entry<String, AvgMinMaxPercentileCounter> entry : counters.entrySet()) {
            entry.getValue().reset();
        }
    }

    @Override
    public void add(String key, long value) {
        addDataPoint(key, value);
    }

    @Override
    public Map<String, Object> values() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, AvgMinMaxPercentileCounter> entry : counters.entrySet()) {
            m.putAll(entry.getValue().values());
        }
        return m;
    }
}
