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

package org.apache.zookeeper.metrics.prometheus;

import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;

/**
 * The base test for prometheus metrics unit tests.
 */
public abstract class PrometheusMetricsTestBase {

    @AfterEach
    void tearDown() throws Exception {
        PrometheusRegistry.defaultRegistry.clear();
        // JvmMetrics uses a static Set to track which registries it has been
        // registered with. We need to clear this set via reflection to allow
        // re-initialization in subsequent tests.
        Field registeredField = JvmMetrics.class.getDeclaredField("REGISTERED");
        registeredField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<?> registeredSet = (Set<?>) registeredField.get(null);
        registeredSet.clear();
    }
}