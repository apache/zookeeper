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

import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.zookeeper.Version;

/**
 * Exports ZooKeeper version info.
 * Example usage:
 * <pre>
 * {@code
 *   new VersionInfoExports().register();
 * }
 * </pre>
 * Metrics being exported:
 * <pre>
 *   zk_version_info{version="3.8.0-SNAPSHOT",revision_hash="e897abb8b60321c693050add06ba1f9706c220ce",
 *   built_on="2021-06-27 10:46 UTC",} 1.0
 * </pre>
 *
 * @since 3.8.0
 */

public class VersionInfoExports extends Collector {

    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> mfs = new ArrayList<>();

        GaugeMetricFamily zkVersionInfo = new GaugeMetricFamily(
                "zk_version_info",
                "ZooKeeper version info",
                Arrays.asList("version", "revision_hash", "built_on"));
        zkVersionInfo.addMetric(
                Arrays.asList(
                        Version.getVersion(),
                        Version.getRevisionHash(),
                        Version.getBuildDate()),
                    1L);
        mfs.add(zkVersionInfo);

        return mfs;
    }
}
