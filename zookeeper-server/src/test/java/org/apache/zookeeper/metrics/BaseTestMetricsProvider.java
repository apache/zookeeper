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

package org.apache.zookeeper.metrics;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.apache.zookeeper.metrics.impl.NullMetricsProvider;

/**
 * Simple MetricsProvider for tests.
 */
public abstract class BaseTestMetricsProvider implements MetricsProvider {

    @Override
    public void configure(Properties prprts) throws MetricsProviderLifeCycleException {
    }

    @Override
    public void start() throws MetricsProviderLifeCycleException {
    }

    @Override
    public MetricsContext getRootContext() {
        return NullMetricsProvider.NullMetricsContext.INSTANCE;
    }

    @Override
    public void stop() {
    }

    @Override
    public void dump(BiConsumer<String, Object> sink) {
    }

    @Override
    public void resetAllValues() {
    }

    public static final class MetricsProviderCapturingLifecycle extends BaseTestMetricsProvider {

        public static final AtomicBoolean configureCalled = new AtomicBoolean();
        public static final AtomicBoolean startCalled = new AtomicBoolean();
        public static final AtomicBoolean stopCalled = new AtomicBoolean();
        public static final AtomicBoolean getRootContextCalled = new AtomicBoolean();

        public static void reset() {
            configureCalled.set(false);
            startCalled.set(false);
            stopCalled.set(false);
            getRootContextCalled.set(false);
        }

        @Override
        public void configure(Properties prprts) throws MetricsProviderLifeCycleException {
            if (!configureCalled.compareAndSet(false, true)) {
                // called twice
                throw new IllegalStateException();
            }
        }

        @Override
        public void start() throws MetricsProviderLifeCycleException {
            if (!startCalled.compareAndSet(false, true)) {
                // called twice
                throw new IllegalStateException();
            }
        }

        @Override
        public MetricsContext getRootContext() {
            getRootContextCalled.set(true);

            return NullMetricsProvider.NullMetricsContext.INSTANCE;
        }

        @Override
        public void stop() {
            if (!stopCalled.compareAndSet(false, true)) {
                // called twice
                throw new IllegalStateException();
            }
        }

    }

    public static final class MetricsProviderWithErrorInStart extends BaseTestMetricsProvider {

        @Override
        public void start() throws MetricsProviderLifeCycleException {
            throw new MetricsProviderLifeCycleException();
        }

    }

    public static final class MetricsProviderWithErrorInConfigure extends BaseTestMetricsProvider {

        @Override
        public void configure(Properties prprts) throws MetricsProviderLifeCycleException {
            throw new MetricsProviderLifeCycleException();
        }

    }

    public static final class MetricsProviderWithConfiguration extends BaseTestMetricsProvider {

        public static final AtomicInteger httpPort = new AtomicInteger();

        @Override
        public void configure(Properties prprts) throws MetricsProviderLifeCycleException {
            httpPort.set(Integer.parseInt(prprts.getProperty("httpPort")));
        }

    }

    public static final class MetricsProviderWithErrorInStop extends BaseTestMetricsProvider {

        public static final AtomicBoolean stopCalled = new AtomicBoolean();

        @Override
        public void stop() {
            stopCalled.set(true);
            throw new RuntimeException();
        }

    }

}
