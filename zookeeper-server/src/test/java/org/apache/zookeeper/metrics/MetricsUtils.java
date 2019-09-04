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

import java.util.HashMap;
import java.util.Map;
import org.apache.zookeeper.server.ServerMetrics;

/**
 * Utility for Metrics in tests.
 */
public abstract class MetricsUtils {

    private MetricsUtils() {
    }

    /**
     * Collect all metrics from a {@link MetricsProvider}. A MetricsProvider
     * provides a {@link MetricsProvider#dump(java.util.function.BiConsumer)
     * }
     * method, that method will in general be more efficient and it does not
     * impose to the MetricsProvider to waste resources.
     *
     * @param metricsProvider
     * @return a Map which collects one entry per each different key returned by
     * {@link MetricsProvider#dump(java.util.function.BiConsumer) }
     */
    public static Map<String, Object> collect(MetricsProvider metricsProvider) {
        Map<String, Object> res = new HashMap<>();
        metricsProvider.dump(res::put);
        return res;
    }

    /**
     * Collect current {@link ServerMetrics} as a Map.
     *
     * @return a flattened view of all metrics reported by the MetricsProvider
     * in use by the current ServerMetrics static instance.
     */
    public static Map<String, Object> currentServerMetrics() {
        return collect(ServerMetrics.getMetrics().getMetricsProvider());
    }

}
