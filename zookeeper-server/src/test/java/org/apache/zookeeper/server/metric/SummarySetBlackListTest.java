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

import static org.junit.Assert.assertTrue;
import java.util.HashSet;
import java.util.Set;
import org.apache.zookeeper.metrics.impl.BaseMetricsProvider;
import org.apache.zookeeper.metrics.impl.DefaultMetricsProvider;
import org.apache.zookeeper.server.ServerMetrics;
import org.junit.Test;

public class SummarySetBlackListTest {

    @Test
    public void testMetrics() {
        System.setProperty(BaseMetricsProvider.summarySetBlackList, "write_per_namespace,read_per_namespace");
        ServerMetrics.metricsProviderInitialized(new DefaultMetricsProvider());
        ServerMetrics.getMetrics().WRITE_PER_NAMESPACE.add("testNamespace", 1);
        ServerMetrics.getMetrics().READ_PER_NAMESPACE.add("testNamespace", 1);

        Set<String> metricNames = new HashSet<>();
        ServerMetrics.getMetrics().getMetricsProvider().dump((k, v) -> metricNames.add(k));
        assertTrue(!metricNames.contains("cnt_testNamespace_write_per_namespace"));
        assertTrue(!metricNames.contains("cnt_testNamespace_read_per_namespace"));
        System.clearProperty(BaseMetricsProvider.summarySetBlackList);
        ServerMetrics.metricsProviderInitialized(new DefaultMetricsProvider());
    }

}
