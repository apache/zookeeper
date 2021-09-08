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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;

public final class QuotaMetricsUtils {
    public static final String QUOTA_COUNT_LIMIT_PER_NAMESPACE = "quota_count_limit_per_namespace";
    public static final String QUOTA_BYTES_LIMIT_PER_NAMESPACE = "quota_bytes_limit_per_namespace";
    public static final String QUOTA_COUNT_USAGE_PER_NAMESPACE = "quota_count_usage_per_namespace";
    public static final String QUOTA_BYTES_USAGE_PER_NAMESPACE = "quota_bytes_usage_per_namespace";
    public static final String QUOTA_EXCEEDED_ERROR_PER_NAMESPACE = "quota_exceeded_error_per_namespace";

    enum QUOTA_LIMIT_USAGE_METRIC_TYPE {QUOTA_COUNT_LIMIT, QUOTA_BYTES_LIMIT, QUOTA_COUNT_USAGE, QUOTA_BYTES_USAGE}
    static final String LIMIT_END_STRING = "/" + Quotas.limitNode;
    static final String STATS_END_STRING = "/" + Quotas.statNode;

    private QuotaMetricsUtils() {
    }

    /**
     * Traverse the quota subtree and return per namespace quota count limit
     *
     * @param dataTree dataTree that contains the quota limit and usage data
     * @return a map with top namespace as the key and quota count limit as the value
     *
     */
    public static Map<String, Number> getQuotaCountLimit(final DataTree dataTree) {
        return getQuotaLimitOrUsage(dataTree, QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_COUNT_LIMIT);
    }

    /**
     * Traverse the quota subtree and return per namespace quota bytes limit
     *`
     * @param dataTree dataTree that contains the quota limit and usage data
     * @return a map with top namespace as the key and quota bytes limit as the value
     *
     */
    public static Map<String, Number> getQuotaBytesLimit(final DataTree dataTree) {
        return getQuotaLimitOrUsage(dataTree, QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_LIMIT);
    }

    /**
     * Traverse the quota subtree and return per namespace quota count usage
     *
     * @param dataTree dataTree that contains the quota limit and usage data
     * @return a map with top namespace as the key and quota count usage as the value
     *
     */
    public static Map<String, Number> getQuotaCountUsage(final DataTree dataTree) {
        return getQuotaLimitOrUsage(dataTree, QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_COUNT_USAGE);
    }

    /**
     * Traverse the quota subtree and return per namespace quota bytes usage
     *
     * @param dataTree dataTree that contains the quota limit and usage data
     * @return  a map with top namespace as the key and quota bytes usage as the value
     *
     */
    public static Map<String, Number> getQuotaBytesUsage(final DataTree dataTree) {
        return getQuotaLimitOrUsage(dataTree, QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_USAGE);
    }

    // traverse the quota subtree and read the quota limit or usage data
    private static Map<String, Number> getQuotaLimitOrUsage(final DataTree dataTree,
                                                            final QUOTA_LIMIT_USAGE_METRIC_TYPE type) {
        final Map<String, Number> metricsMap = new ConcurrentHashMap<>();
        if (dataTree != null) {
            getQuotaLimitOrUsage(Quotas.quotaZookeeper, metricsMap, type, dataTree);
        }
        return metricsMap;
    }

    private static void getQuotaLimitOrUsage(final String path,
                                     final Map<String, Number> metricsMap,
                                     final QUOTA_LIMIT_USAGE_METRIC_TYPE type,
                                     final DataTree dataTree) {
        final DataNode node = dataTree.getNode(path);
        if (node == null) {
            return;
        }
        final String[] children;
        synchronized (node) {
            children = node.getChildren().toArray(new String[0]);
        }
        if (children.length == 0) {
            if (shouldCollect(path, type)) {
                collectQuotaLimitOrUsage(path, node, metricsMap, type);
            }
            return;
        }
        for (final String child : children) {
            getQuotaLimitOrUsage(path + "/" + child, metricsMap, type, dataTree);
        }
    }

    static boolean shouldCollect(final String path, final QUOTA_LIMIT_USAGE_METRIC_TYPE type) {
        return path.endsWith(LIMIT_END_STRING)
                && (QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_COUNT_LIMIT == type || QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_LIMIT == type)
                || path.endsWith(STATS_END_STRING)
                && (QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_COUNT_USAGE == type || QUOTA_LIMIT_USAGE_METRIC_TYPE.QUOTA_BYTES_USAGE == type);
    }

    static void collectQuotaLimitOrUsage(final String path,
                                         final DataNode node,
                                         final Map<String, Number> metricsMap,
                                         final QUOTA_LIMIT_USAGE_METRIC_TYPE type) {
        final String namespace = PathUtils.getTopNamespace(Quotas.trimQuotaPath(path));
        if (namespace == null) {
            return;
        }
        final byte[] data = node.getData();
        if (data == null) {
            return;
        }
        final StatsTrack statsTrack = new StatsTrack(data);
        switch (type) {
            case QUOTA_COUNT_LIMIT:
                aggregateQuotaLimitOrUsage(namespace, metricsMap, getQuotaLimit(statsTrack.getCountHardLimit(), statsTrack.getCount()));
                break;
            case QUOTA_BYTES_LIMIT:
                aggregateQuotaLimitOrUsage(namespace, metricsMap, getQuotaLimit(statsTrack.getByteHardLimit(), statsTrack.getBytes()));
                break;
            case QUOTA_COUNT_USAGE:
                aggregateQuotaLimitOrUsage(namespace, metricsMap, statsTrack.getCount());
                break;
            case QUOTA_BYTES_USAGE:
                aggregateQuotaLimitOrUsage(namespace, metricsMap, statsTrack.getBytes());
                break;
            default:
        }
    }

    // hard limit takes precedence if specified
    static long getQuotaLimit(final long hardLimit, final long limit) {
        return hardLimit > -1 ? hardLimit : limit;
    }

    private static void aggregateQuotaLimitOrUsage(final String namespace,
                                           final Map<String, Number> metricsMap,
                                           final long limitOrUsage) {
        metricsMap.put(namespace, metricsMap.getOrDefault(namespace, 0).longValue() + limitOrUsage);
    }
}
