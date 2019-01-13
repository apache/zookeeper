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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generic long counter that keep track of min/max/avg. The counter is
 * thread-safe
 */
public class AvgMinMaxCounter extends Metric {
    private String name;
    private AtomicLong total = new AtomicLong();
    private AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private AtomicLong max = new AtomicLong(Long.MIN_VALUE);
    private AtomicLong count = new AtomicLong();

    public AvgMinMaxCounter(String name) {
        this.name = name;
    }
    
    public void addDataPoint(long value) {
        total.addAndGet(value);
        count.incrementAndGet();
        setMin(value);
        setMax(value);
    }

    private void setMax(long value) {
        long current;
        while (value > (current = max.get())
                && !max.compareAndSet(current, value))
            ;
    }

    private void setMin(long value) {
        long current;
        while (value < (current = min.get())
                && !min.compareAndSet(current, value))
            ;
    }

    public double getAvg() {
        // There is possible race-condition but we don't need the stats to be
        // extremely accurate.
        long currentCount = count.get();
        long currentTotal = total.get();
        if (currentCount > 0) {
            double avgLatency = currentTotal / (double)currentCount;
            BigDecimal bg = new BigDecimal(avgLatency);
            return bg.setScale(4, BigDecimal.ROUND_HALF_UP).doubleValue();
        }
        return 0;
    }

    public long getCount() {
        return count.get();
    }

    public long getMax() {
        long current = max.get();
        return  (current == Long.MIN_VALUE) ? 0: current;
    }

    public long getMin() {
        long current = min.get();
        return  (current == Long.MAX_VALUE) ? 0: current;
    }

    public long getTotal() {
        return total.get();
    }

    public void resetMax() {
        max.set(getMin());
    }

    public void reset() {
        count.set(0);
        total.set(0);
        min.set(Long.MAX_VALUE);
        max.set(Long.MIN_VALUE);
    }

    public void add(long value) {
        addDataPoint(value);
    }

    public Map<String, Object> values() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("avg_" + name, this.getAvg());
        m.put("min_" + name, this.getMin());
        m.put("max_" + name, this.getMax());
        m.put("cnt_" + name, this.getCount());
        m.put("sum_" + name, this.getTotal());
        return m;
    }

}
