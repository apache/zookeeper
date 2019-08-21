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

package org.apache.zookeeper.metrics.impl;

import java.util.Properties;
import java.util.function.BiConsumer;
import org.apache.zookeeper.metrics.Counter;
import org.apache.zookeeper.metrics.Gauge;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.Summary;
import org.apache.zookeeper.metrics.SummarySet;

/**
 * This is a dummy MetricsProvider which does nothing.
 */
public class NullMetricsProvider implements MetricsProvider {

    /**
     * Instance of NullMetricsProvider useful for tests.
     */
    public static final MetricsProvider INSTANCE = new NullMetricsProvider();

    @Override
    public void configure(Properties configuration) throws MetricsProviderLifeCycleException {
    }

    @Override
    public void start() throws MetricsProviderLifeCycleException {
    }

    @Override
    public MetricsContext getRootContext() {
        return NullMetricsContext.INSTANCE;
    }

    @Override
    public void dump(BiConsumer<String, Object> sink) {
    }

    @Override
    public void resetAllValues() {
    }

    @Override
    public void stop() {
    }

    public static final class NullMetricsContext implements MetricsContext {

        public static final NullMetricsContext INSTANCE = new NullMetricsContext();

        @Override
        public MetricsContext getContext(String name) {
            return INSTANCE;
        }

        @Override
        public Counter getCounter(String name) {
            return NullCounter.INSTANCE;
        }

        @Override
        public void registerGauge(String name, Gauge gauge) {
        }

        @Override
        public void unregisterGauge(String name) {
        }

        @Override
        public Summary getSummary(String name, DetailLevel detailLevel) {
            return NullSummary.INSTANCE;
        }

        @Override
        public SummarySet getSummarySet(String name, DetailLevel detailLevel) {
            return NullSummarySet.INSTANCE;
        }

    }

    private static final class NullCounter implements Counter {

        private static final NullCounter INSTANCE = new NullCounter();

        @Override
        public void add(long delta) {
        }

        @Override
        public long get() {
            return 0;
        }

    }

    private static final class NullSummary implements Summary {

        private static final NullSummary INSTANCE = new NullSummary();

        @Override
        public void add(long value) {
        }

    }

    private static final class NullSummarySet implements SummarySet {

        private static final NullSummarySet INSTANCE = new NullSummarySet();

        @Override
        public void add(String key, long value) {
        }

    }

}
