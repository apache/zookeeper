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

package org.apache.zookeeper.metrics.impl;

import java.util.ArrayList;
import java.util.List;
import org.apache.zookeeper.metrics.MetricsProvider;

/**
 * Base implementation of {@link MetricsProvider}.<br>
 * Provide a default implementation that currently includes the summarySet blacklist feature.
 */
public class BaseMetricsProvider {
    public static final String summarySetBlackList = "zookeeper.summarySetBlackList";

    public static final List<String> getSummarySetBlackList() {
        String summarySetBlackListConfig = System.getProperty(summarySetBlackList);
        List<String> blackList = new ArrayList<>();
        if (summarySetBlackListConfig != null) {
            String[] list = summarySetBlackListConfig.split(",");
            for (String name : list) {
                if (!name.trim().isEmpty()) {
                    blackList.add(name.trim());
                }
            }
        }
        return blackList;
    }

}
