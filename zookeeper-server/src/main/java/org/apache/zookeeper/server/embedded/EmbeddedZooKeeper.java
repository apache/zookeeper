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

package org.apache.zookeeper.server.embedded;

import org.apache.zookeeper.util.ServiceUtils;

/**
 * Factory methods for starting ZooKeeper servers.
 */
public final class EmbeddedZooKeeper {
    private EmbeddedZooKeeper() {
        // Prevent this class from being instantiated.
    }

    /**
     * Starts a ZooKeeper server in production mode, meaning the JVM will exit when the ZooKeeper
     * server exits.
     * @param config The {@link EmbeddedConfig}
     * @return A handle to the ZooKeeper server
     */
    static ZooKeeperServerHandle startProd(final EmbeddedConfig config) {
        ServiceUtils.setSystemExitProcedure(ServiceUtils.SYSTEM_EXIT);
        return new QuorumPeerMainHandle(config);
    }

    /**
     * Starts a ZooKeeper server in testing mode, meaning the JVM will not exit when the ZooKeeper
     * server exits.
     * @param config The {@link EmbeddedConfig}
     * @return A handle to the ZooKeeper server
     */
    static ZooKeeperServerHandle startTest(final EmbeddedConfig config) {
        ServiceUtils.setSystemExitProcedure(ServiceUtils.LOG_ONLY);
        return new QuorumPeerMainHandle(config);
    }
}
