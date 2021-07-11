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
import io.prometheus.client.CollectorRegistry;

/**
 * The Customized Exports is different from the Summary, Counter, Gauge which is usually a number.
 * <p>
 * The Customized Exports can be text information. For example: {@link VersionInfoExports}
 * </p>
 * @since 3.8.0
 */

public class CustomizedExports {
    private final Collector versionInfoExports;

    private CustomizedExports() {
        this.versionInfoExports = new VersionInfoExports();
    }

    private static class SingletonHolder {
        private static final CustomizedExports SINGLETON = new CustomizedExports();
    }

    public static CustomizedExports instance() {
        return SingletonHolder.SINGLETON;
    }

    public void initialize() {
        versionInfoExports.register(CollectorRegistry.defaultRegistry);
    }

    // VisibleForTesting
    public void terminate() {
        CollectorRegistry.defaultRegistry.unregister(versionInfoExports);
    }
}
