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

import static org.apache.zookeeper.client.FourLetterWordMain.send4LetterWord;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricSetRemoveTest extends ClientBase {

    protected static final Logger LOG = LoggerFactory.getLogger(MetricSetRemoveTest.class);

    @Test
    public void nameSpaceMetricRemoveTest() throws Exception {
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(hostPort, 10000, watcher);
        // Create and immediately delete 100 root nodes
        String testPath = "summarySetTest";
        for (int i = 0; i < 100; i++) {
            String path = "/" + testPath + i;
            zk.create(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.delete(path, -1);
        }

        String host = hostPort.split(":")[0];
        int port = Integer.valueOf(hostPort.split(":")[1]);
        String mntrCmd = send4LetterWord(host, port, "mntr");
        String metricTail = "_write_per_namespace";
        Set<String> mntrLines = Arrays.asList(mntrCmd.split("\n")).stream()
                .filter(line -> line.contains(metricTail))
                .map(line -> line.split("\t")[0])
                .collect(Collectors.toSet());

        // Verify that the metrics for the created and deleted nodes do not appear in the 'mntr' namespace output
        for (int i = 0; i < 100; i++) {
            for (String metricHead : Arrays.asList("zk_cnt_", "zk_sum_", "zk_avg_", "zk_min_", "zk_max_")) {
                String metric = metricHead + testPath + i + metricTail;
                assertFalse(mntrLines.contains(metric));
            }
        }
        zk.close();
    }
}
