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

package org.apache.zookeeper.server.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ServerMetrics;
import org.junit.jupiter.api.Test;

public class QuotaMetricsUtilsTest extends ZKTestCase {
    @Test
    public void testQuotaMetrics_singleQuotaSubtree() throws Exception {
        // register the metrics
        final String nameSuffix = UUID.randomUUID().toString();
        final DataTree dt = new DataTree();
        registerQuotaMetrics(nameSuffix, dt);

        // build the data tree
        final String ns = UUID.randomUUID().toString();

        final long countLimit = 10;
        final long bytesLimit = 100;
        final long countHardLimit = 5;
        final long bytesHardLimit = 50;

        final long countUsage = 5;
        final long bytesUsage = 40;

        final StatsTrack limitTrack = buildLimitStatsTrack(countLimit, bytesLimit, countHardLimit, bytesHardLimit);
        final StatsTrack usageTrack = buildUsageStatsTrack(countUsage, bytesUsage);
        buildDataTree("/" + ns, limitTrack, usageTrack, dt);

        // validate the quota metrics
        validateQuotaMetrics(ns, countHardLimit, bytesHardLimit, countUsage, bytesUsage, nameSuffix);
    }


    @Test
    public void testQuotaMetrics_multipleQuotaSubtrees() throws Exception {
        // register the metrics
        final String nameSuffix = UUID.randomUUID().toString();
        final DataTree dt = new DataTree();
        registerQuotaMetrics(nameSuffix, dt);

        // build the data tree
        final String ns = UUID.randomUUID().toString();

        final long countLimit1 = 10;
        final long bytesLimit1 = 100;
        final long countHardLimit1 = 5;
        final long bytesHardLimit1 = 50;

        final long countUsage1 = 5;
        final long bytesUsage1 = 40;

        final StatsTrack limitTrack1 = buildLimitStatsTrack(countLimit1, bytesLimit1, countHardLimit1, bytesHardLimit1);
        final StatsTrack usageTrack1 = buildUsageStatsTrack(countUsage1, bytesUsage1);

        buildDataTree("/" + ns + "/a/b", limitTrack1, usageTrack1, dt);

        // validate the quota metrics
        validateQuotaMetrics(ns, countHardLimit1, bytesHardLimit1, countUsage1, bytesUsage1, nameSuffix);

        // update the data tree with another quota subtree
        final long countLimit2 = 20;
        final long bytesLimit2 = 200;
        final long countHardLimit2 = 10;
        final long bytesHardLimit2 = 100;

        final long countUsage2 = 9;
        final long bytesUsage2 = 80;

        final StatsTrack limitTrack2 = buildLimitStatsTrack(countLimit2, bytesLimit2, countHardLimit2, bytesHardLimit2);
        final StatsTrack usageTrack2 = buildUsageStatsTrack(countUsage2, bytesUsage2);

        buildDataTree("/" + ns + "/a/c/d", limitTrack2, usageTrack2, dt);

        // validate the quota metrics
        validateQuotaMetrics(ns, countHardLimit1 + countHardLimit2, bytesHardLimit1 + bytesHardLimit2,
                countUsage1 + countUsage2, bytesUsage1 + bytesUsage2, nameSuffix);
    }

    @Test
    public void testQuotaMetrics_noUsage() throws Exception {
        // register the metrics
        final String nameSuffix = UUID.randomUUID().toString();
        final DataTree dt = new DataTree();
        registerQuotaMetrics(nameSuffix, dt);

        // build the data tree
        final String ns = UUID.randomUUID().toString();

        final long countLimit = 20;
        final long bytesLimit = 200;
        final long countHardLimit = -1;
        final long bytesHardLimit = -1;

        final long countUsage = 1;  // the node itself is always counted
        final long bytesUsage = 0;

        final StatsTrack limitTrack = buildLimitStatsTrack(countLimit, bytesLimit, countHardLimit, bytesHardLimit);
        final StatsTrack usageTrack = buildUsageStatsTrack(countUsage, bytesUsage);
        buildDataTree("/" + ns, limitTrack, usageTrack, dt);

        // validate the quota
        validateQuotaMetrics(ns, countLimit, bytesLimit, countUsage, bytesUsage, nameSuffix);
    }

    @Test
    public void testQuotaMetrics_nullDataTree() {
        // register the metrics
        final String nameSuffix = UUID.randomUUID().toString();
        registerQuotaMetrics(nameSuffix, null);

        // validate the quota
        validateQuotaMetrics(UUID.randomUUID().toString(), null, null, null, null, nameSuffix);
    }

    @Test
    public void testQuotaMetrics_emptyDataTree() {
        // register the metrics
        final String nameSuffix = UUID.randomUUID().toString();
        registerQuotaMetrics(nameSuffix, new DataTree());

        // validate the quota
        validateQuotaMetrics(UUID.randomUUID().toString(), null, null, null, null, nameSuffix);
    }

    @Test
    public void testShouldCollect_limitPath() {
        final String limitPath = Quotas.quotaPath("/ns1") + QuotaMetricsUtils.LIMIT_END_STRING;

        assertTrue(QuotaMetricsUtils.shouldCollect(limitPath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_COUNT_LIMIT));
        assertTrue(QuotaMetricsUtils.shouldCollect(limitPath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_LIMIT));

        assertFalse(QuotaMetricsUtils.shouldCollect(limitPath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_COUNT_USAGE));
        assertFalse(QuotaMetricsUtils.shouldCollect(limitPath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_USAGE));
    }

    @Test
    public void testShouldCollect_usagePath() {
        final String usagePath = Quotas.quotaPath("/ns1") + QuotaMetricsUtils.STATS_END_STRING;

        assertTrue(QuotaMetricsUtils.shouldCollect(usagePath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_COUNT_USAGE));
        assertTrue(QuotaMetricsUtils.shouldCollect(usagePath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_USAGE));

        assertFalse(QuotaMetricsUtils.shouldCollect(usagePath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_COUNT_LIMIT));
        assertFalse(QuotaMetricsUtils.shouldCollect(usagePath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_LIMIT));
    }

    @Test
    public void testShouldCollect_notLimitOrUsagePath() {
        final String usagePath = Quotas.quotaPath("/ns1") + "/notLimitOrUsage";

        assertFalse(QuotaMetricsUtils.shouldCollect(usagePath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_COUNT_USAGE));
        assertFalse(QuotaMetricsUtils.shouldCollect(usagePath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_USAGE));

        assertFalse(QuotaMetricsUtils.shouldCollect(usagePath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_COUNT_LIMIT));
        assertFalse(QuotaMetricsUtils.shouldCollect(usagePath, QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_LIMIT));
    }

    @Test
    public void testGetQuotaLimit() {
        assertEquals(0L, QuotaMetricsUtils.getQuotaLimit(0L, -1L));
        assertEquals(1L, QuotaMetricsUtils.getQuotaLimit(-1L, 1L));
        assertEquals(0L, QuotaMetricsUtils.getQuotaLimit(-2L, 0L));
    }

    @Test
    public void testCollectQuotaMetrics_noData() {
        final Map<String, Number> metricsMap = new HashMap<>();

        QuotaMetricsUtils.collectQuotaLimitOrUsage(Quotas.quotaPath("/ns1") + QuotaMetricsUtils.LIMIT_END_STRING,
                                        new DataNode(new byte[0], null, null),
                                        metricsMap,
                                        QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_LIMIT);

        assertEquals(1, metricsMap.size());
        final Map.Entry<String, Number> entry = metricsMap.entrySet().iterator().next();
        assertEquals("ns1", entry.getKey());
        assertEquals(-1L,  entry.getValue().longValue());
    }

    @Test
    public void testCollectQuotaMetrics_nullData() {
        final Map<String, Number> metricsMap = new HashMap<>();

        QuotaMetricsUtils.collectQuotaLimitOrUsage(Quotas.quotaPath("/ns1") + QuotaMetricsUtils.LIMIT_END_STRING,
                new DataNode(null, null, null),
                metricsMap,
                QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_LIMIT);

        assertEquals(0, metricsMap.size());
    }

    @Test
    public void testCollectQuotaMetrics_noNamespace() {
        final Map<String, Number> metricsMap = new HashMap<>();

        QuotaMetricsUtils.collectQuotaLimitOrUsage("/zookeeper/quota",
                new DataNode(null, null, null),
                metricsMap,
                QuotaMetricsUtils.QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_USAGE);

        assertEquals(0, metricsMap.size());
    }

    private void registerQuotaMetrics(final String nameSuffix, final DataTree dt) {
        final MetricsProvider metricProvider = ServerMetrics.getMetrics().getMetricsProvider();
        final MetricsContext rootContext = metricProvider.getRootContext();

        // added random UUID as NAME_SUFFIX to avoid GaugeSet being overwritten when registering with same name
        rootContext.registerGaugeSet(
                QuotaMetricsUtils.QUOTA_COUNT_LIMIT_PER_NAMESPACE + nameSuffix, () -> QuotaMetricsUtils.getQuotaCountLimit(dt));
        rootContext.registerGaugeSet(
                QuotaMetricsUtils.QUOTA_BYTES_LIMIT_PER_NAMESPACE + nameSuffix, () -> QuotaMetricsUtils.getQuotaBytesLimit(dt));
        rootContext.registerGaugeSet(
                QuotaMetricsUtils.QUOTA_COUNT_USAGE_PER_NAMESPACE + nameSuffix, () -> QuotaMetricsUtils.getQuotaCountUsage(dt));
        rootContext.registerGaugeSet(
                QuotaMetricsUtils.QUOTA_BYTES_USAGE_PER_NAMESPACE + nameSuffix, () -> QuotaMetricsUtils.getQuotaBytesUsage(dt));
    }

    private StatsTrack buildLimitStatsTrack(final long countLimit,
                                            final long bytesLimit,
                                            final long countHardLimit,
                                            final long bytesHardLimit) {
        final StatsTrack limitTrack = new StatsTrack();
        limitTrack.setCount(countLimit);
        limitTrack.setBytes(bytesLimit);
        limitTrack.setCountHardLimit(countHardLimit);
        limitTrack.setByteHardLimit(bytesHardLimit);
        return limitTrack;
    }

    private StatsTrack buildUsageStatsTrack(final long countUsage,
                                            final long bytesUsage) {
        final StatsTrack usageTrack = new StatsTrack();
        usageTrack.setCount(countUsage);
        usageTrack.setBytes(bytesUsage);

        return usageTrack;
    }

    private void buildDataTree(final String path,
                               final StatsTrack limitTrack,
                               final StatsTrack usageTrack,
                               final DataTree dataTree) throws Exception {

        // create the ancestor and child data nodes
        buildAncestors(path, dataTree);
        int childCount = (int) usageTrack.getCount() - 1; // the node count always includes the top namespace itself
        if (childCount > 0) {
            int dataBytes = (int) usageTrack.getBytes() / childCount;
            for (int i = 0; i < childCount; i++) {
                dataTree.createNode(path + "/n_" + i, new byte[dataBytes], null, -1, 1, 1, 1);
            }
        }

        // create the quota tree
        buildAncestors(Quotas.quotaPath(path), dataTree);

        final String limitPath = Quotas.limitPath(path);
        dataTree.createNode(limitPath, limitTrack.getStatsBytes(), null, -1, 1, 1, 1);
        assertEquals(limitTrack, new StatsTrack(dataTree.getNode(limitPath).getData()));

        final String usagePath = Quotas.statPath(path);
        dataTree.createNode(usagePath, usageTrack.getStatsBytes(), null, -1, 1, 1, 1);
        assertEquals(usageTrack, new StatsTrack(dataTree.getNode(usagePath).getData()));
    }

    private void buildAncestors(final String path, final DataTree dataTree) throws Exception {
        final String[] parts = path.split("/");
        String nodePath = "";

        for (int i = 1; i < parts.length; i++) {
            nodePath = nodePath + "/" + parts[i];
            try {
                dataTree.createNode(nodePath, null, null, -1, 1, 1, 1);
            } catch (final KeeperException.NodeExistsException e) {
                // ignored
            }
        }
    }

    private void validateQuotaMetrics(final String namespace,
                                      final Long countLimit,
                                      final Long bytesLimit,
                                      final Long countUsage,
                                      final Long bytesUsage,
                                      final String nameSuffix) {
        final Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(countLimit, values.get(namespace + "_" + QuotaMetricsUtils.QUOTA_COUNT_LIMIT_PER_NAMESPACE + nameSuffix));
        assertEquals(bytesLimit, values.get(namespace + "_" + QuotaMetricsUtils.QUOTA_BYTES_LIMIT_PER_NAMESPACE + nameSuffix));
        assertEquals(countUsage, values.get(namespace + "_" + QuotaMetricsUtils.QUOTA_COUNT_USAGE_PER_NAMESPACE + nameSuffix));
        assertEquals(bytesUsage, values.get(namespace + "_" + QuotaMetricsUtils.QUOTA_BYTES_USAGE_PER_NAMESPACE + nameSuffix));
    }
}
