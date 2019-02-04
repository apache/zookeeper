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

import java.lang.Override;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class SimpleCounter extends Metric {
    private final String name;
    private final AtomicLong counter = new AtomicLong();

    public SimpleCounter(String name) {
        this.name = name;
    }

    @Override
    public void add(long value) {
        counter.addAndGet(value);
    }

    @Override
    public void reset() {
        counter.set(0);
    }

    public long getCount() {
        return counter.get();
    }

    @Override
    public Map<String, Object> values() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(name, this.getCount());
        return m;
    }
}
