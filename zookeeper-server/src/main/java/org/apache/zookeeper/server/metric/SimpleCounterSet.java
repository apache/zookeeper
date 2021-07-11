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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.zookeeper.metrics.CounterSet;

/**
 * Represent a set of counters identified by different keys.
 * The counter is thread-safe
 */
public class SimpleCounterSet extends Metric implements CounterSet {
    private final String name;
    private final ConcurrentHashMap<String, SimpleCounter> counters = new ConcurrentHashMap<>();

    public SimpleCounterSet(final String name) {
        this.name = name;
    }

    @Override
    public void add(final String key, final long delta) {
        final SimpleCounter counter = counters.computeIfAbsent(key, (k) -> new SimpleCounter(k + "_" + name));
        counter.add(delta);
    }

    @Override
    public void reset() {
        counters.values().forEach(SimpleCounter::reset);
    }

    @Override
    public Map<String, Object> values() {
        final Map<String, Object> m = new LinkedHashMap<>();
        counters.values().forEach(counter -> m.putAll(counter.values()));
        return m;
    }
}
