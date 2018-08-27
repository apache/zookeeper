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
package org.apache.zookeeper.metrics.impl;

import java.util.Properties;
import org.apache.zookeeper.metrics.Counter;
import org.apache.zookeeper.metrics.Gauge;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.Summary;

/**
 * This is a dummy MetricsProvider which does nothing.
 */
public class NullMetricsProvider implements MetricsProvider {

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
        public boolean registerGauge(String name, Gauge gauge) {
            return true;
        }

        @Override
        public Summary getSummary(String name) {
            return NullSummary.INSTANCE;
        }

    }

    private static final class NullCounter implements Counter {

        private static final NullCounter INSTANCE = new NullCounter();

        @Override
        public void inc(long delta) {
        }

        @Override
        public long get() {
            return 0;
        }

    }

    private static final class NullSummary implements Summary {

        private static final NullSummary INSTANCE = new NullSummary();

        @Override
        public void registerValue(long value) {
        }

    }
}
