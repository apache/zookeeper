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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

public class ServerMetricsOpCountTest extends ClientBase {

    // Metric name constants
    private static final String OP_COUNT_TOTAL_METRIC_NAME = "op_count_total";
    private static final String OP_COUNT_CREATE_METRIC_NAME = "op_count_create";
    private static final String OP_COUNT_EXISTS_METRIC_NAME = "op_count_exists";
    private static final String OP_COUNT_GET_DATA_METRIC_NAME = "op_count_get_data";
    private static final String OP_COUNT_SET_DATA_METRIC_NAME = "op_count_set_data";
    private static final String OP_COUNT_GET_ACL_METRIC_NAME = "op_count_get_acl";
    private static final String OP_COUNT_SET_ACL_METRIC_NAME = "op_count_set_acl";
    private static final String OP_COUNT_GET_CHILDREN_METRIC_NAME = "op_count_get_children";
    private static final String OP_COUNT_GET_ALL_CHILDREN_NUMBER_METRIC_NAME = "op_count_get_all_children_number";
    private static final String OP_COUNT_ADD_WATCH_METRIC_NAME = "op_count_add_watch";
    private static final String OP_COUNT_DELETE_METRIC_NAME = "op_count_delete";
    private static final String OP_COUNT_MULTI_METRIC_NAME = "op_count_multi";
    private static final String OP_COUNT_MULTI_READ_METRIC_NAME = "op_count_multi_read";
    private static final String OP_COUNT_GET_EPHEMERALS_METRIC_NAME = "op_count_get_ephemerals";
    private static final String OP_COUNT_WHO_AM_I_METRIC_NAME = "op_count_who_am_i";
    private static final String OP_COUNT_CREATE_SESSION_METRIC_NAME = "op_count_create_session";
    private static final String OP_COUNT_CLOSE_SESSION_METRIC_NAME = "op_count_close_session";

    @Test
    public void testBasicOpCounts() throws Exception {
        final Map<String, Object> initialMetrics = MetricsUtils.currentServerMetrics();
        final long initialTotalCount = (Long) initialMetrics.getOrDefault(OP_COUNT_TOTAL_METRIC_NAME, 0L);

        final Map<String, Long> initialCounts = new HashMap<>();
        initialCounts.put(OP_COUNT_CREATE_METRIC_NAME, (Long) initialMetrics.getOrDefault(OP_COUNT_CREATE_METRIC_NAME, 0L));
        initialCounts.put(OP_COUNT_EXISTS_METRIC_NAME, (Long) initialMetrics.getOrDefault(OP_COUNT_EXISTS_METRIC_NAME, 0L));
        initialCounts.put(OP_COUNT_GET_DATA_METRIC_NAME, (Long) initialMetrics.getOrDefault(OP_COUNT_GET_DATA_METRIC_NAME, 0L));
        initialCounts.put(OP_COUNT_SET_DATA_METRIC_NAME, (Long) initialMetrics.getOrDefault(OP_COUNT_SET_DATA_METRIC_NAME, 0L));
        initialCounts.put(OP_COUNT_GET_ACL_METRIC_NAME, (Long) initialMetrics.getOrDefault(OP_COUNT_GET_ACL_METRIC_NAME, 0L));
        initialCounts.put(OP_COUNT_SET_ACL_METRIC_NAME, (Long) initialMetrics.getOrDefault(OP_COUNT_SET_ACL_METRIC_NAME, 0L));
        initialCounts.put(OP_COUNT_GET_CHILDREN_METRIC_NAME, (Long) initialMetrics.getOrDefault(OP_COUNT_GET_CHILDREN_METRIC_NAME, 0L));
        initialCounts.put(OP_COUNT_GET_ALL_CHILDREN_NUMBER_METRIC_NAME, (Long) initialMetrics.getOrDefault(OP_COUNT_GET_ALL_CHILDREN_NUMBER_METRIC_NAME, 0L));
        initialCounts.put(OP_COUNT_ADD_WATCH_METRIC_NAME, (Long) initialMetrics.getOrDefault(OP_COUNT_ADD_WATCH_METRIC_NAME, 0L));
        initialCounts.put(OP_COUNT_DELETE_METRIC_NAME, (Long) initialMetrics.getOrDefault(OP_COUNT_DELETE_METRIC_NAME, 0L));

        try (final ZooKeeper zk = createClient()) {
            final String path = generateUniquePath("testBasicOps");

            // Create node
            zk.create(path, "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            // Perform various operations
            zk.exists(path, false);
            zk.getData(path, false, null);
            zk.setData(path, "updated".getBytes(), -1);
            zk.getACL(path, null);

            zk.setACL(path, Ids.READ_ACL_UNSAFE, -1);
            zk.getChildren(path, false);

            zk.getAllChildrenNumber(path);

            zk.addWatch(path, event -> {}, org.apache.zookeeper.AddWatchMode.PERSISTENT);

            // Delete node
            zk.delete(path, -1);

            // Verify all metrics increased
            final Map<String, Object> finalMetrics = MetricsUtils.currentServerMetrics();
            final long finalTotalCount = (Long) finalMetrics.getOrDefault(OP_COUNT_TOTAL_METRIC_NAME, 0L);

            // Total count should have increased by at least 10 operations
            assertTrue(finalTotalCount >= initialTotalCount + 10,
                    "Total count should increase by at least 10 operations, initial: " + initialTotalCount
                            + ", final: " + finalTotalCount);

            // Verify each specific metric increased
            for (final Map.Entry<String, Long> entry : initialCounts.entrySet()) {
                final String metricName = entry.getKey();
                final long initialCount = entry.getValue();
                final long finalCount = (Long) finalMetrics.getOrDefault(metricName, 0L);

                assertTrue(finalCount > initialCount,
                        metricName + " should increase, initial: " + initialCount
                                + ", final: " + finalCount);
            }
        }
    }

    @Test
    public void testMultiOpCount() throws Exception {
        testSingleOpCount(OP_COUNT_MULTI_METRIC_NAME, (zk, basePath) -> {
            final String path1 = basePath + "_1";
            final String path2 = basePath + "_2";
            final List<org.apache.zookeeper.Op> ops = Arrays.asList(
                    org.apache.zookeeper.Op.create(path1, "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    org.apache.zookeeper.Op.create(path2, "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
            );
            zk.multi(ops);
            zk.delete(path1, -1);
            zk.delete(path2, -1);
        });
    }

    @Test
    public void testMultiReadOpCount() throws Exception {
        testSingleOpCount(OP_COUNT_MULTI_READ_METRIC_NAME, (zk, basePath) -> {
            final String path1 = basePath + "_1";
            final String path2 = basePath + "_2";

            // Create nodes first
            zk.create(path1, "test1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create(path2, "test2".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            // Perform multi-read operation
            final List<org.apache.zookeeper.Op> readOps = Arrays.asList(
                    org.apache.zookeeper.Op.getData(path1),
                    org.apache.zookeeper.Op.getData(path2)
            );
            zk.multi(readOps);

            zk.delete(path1, -1);
            zk.delete(path2, -1);
        });
    }

    @Test
    public void testGetEphemeralsOpCount() throws Exception {
        testSingleOpCount(OP_COUNT_GET_EPHEMERALS_METRIC_NAME, (zk, path) -> {
            zk.create(path, "test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            zk.getEphemerals("/");
        });
    }

    @Test
    public void testWhoAmIOpCount() throws Exception {
        testSingleOpCount(OP_COUNT_WHO_AM_I_METRIC_NAME, (zk, path) -> zk.whoAmI());
    }

    @Test
    public void testSessionOperationCounts() throws Exception {
        final Map<String, Object> initialMetrics = MetricsUtils.currentServerMetrics();
        final long initialCreateCount = (Long) initialMetrics.getOrDefault(OP_COUNT_CREATE_SESSION_METRIC_NAME, 0L);
        final long initialCloseCount = (Long) initialMetrics.getOrDefault(OP_COUNT_CLOSE_SESSION_METRIC_NAME, 0L);

        // Create and close a new client to trigger session operations
        final ZooKeeper zk = createClient();
        zk.close();

        final Map<String, Object> finalMetrics = MetricsUtils.currentServerMetrics();
        final long finalCreateCount = (Long) finalMetrics.getOrDefault(OP_COUNT_CREATE_SESSION_METRIC_NAME, 0L);
        final long finalCloseCount = (Long) finalMetrics.getOrDefault(OP_COUNT_CLOSE_SESSION_METRIC_NAME, 0L);

        assertTrue(finalCreateCount > initialCreateCount, "Create session count should not decrease");
        assertTrue(finalCloseCount > initialCloseCount, "Close session count should not decrease");
    }

    private void testSingleOpCount(final String metricName, final OperationExecutor executor) throws Exception {
        final Map<String, Object> initialMetrics = MetricsUtils.currentServerMetrics();
        final long initialOpCount = (Long) initialMetrics.getOrDefault(metricName, 0L);
        final long initialTotalCount = (Long) initialMetrics.getOrDefault(OP_COUNT_TOTAL_METRIC_NAME, 0L);

        try (final ZooKeeper zk = createClient()) {
            final String path = generateUniquePath("test" + metricName.replace("_op_count", ""));

            // Execute the operation
            executor.execute(zk, path);

            // Verify metrics increased
            final Map<String, Object> finalMetrics = MetricsUtils.currentServerMetrics();
            final long finalOpCount = (Long) finalMetrics.getOrDefault(metricName, 0L);
            final long finalTotalCount = (Long) finalMetrics.getOrDefault(OP_COUNT_TOTAL_METRIC_NAME, 0L);

            assertTrue(finalTotalCount > initialTotalCount,
                    "Total count should increase after " + metricName + " operations"
                            + " initial: " + initialTotalCount + ", final: " + finalTotalCount);

            assertTrue(finalOpCount > initialOpCount,
                    metricName + " should increase, initial: " + initialOpCount
                            + ", final: " + finalOpCount);
        }
    }

    @FunctionalInterface
    private interface OperationExecutor {
        void execute(ZooKeeper zk, String path) throws Exception;
    }

    private String generateUniquePath(final String baseName) {
        return "/" + baseName + "_" + System.nanoTime();
    }
}

