/**
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default listener implementation, which will do a graceful shutdown on
 * notification.This is used for standalone server.
 */
public class ZooKeeperServerListenerImpl implements ZooKeeperServerListener {
    private static final Logger LOG = LoggerFactory
            .getLogger(ZooKeeperServerListenerImpl.class);
    final protected ZooKeeperServer zooKeeperServer;

    public ZooKeeperServerListenerImpl(ZooKeeperServer zooKeeperServer) {
        this.zooKeeperServer = zooKeeperServer;
    }

    @Override
    public void notifyStopping(String threadName, int exitCode) {
        LOG.info("Thread {} exits, error code {}", threadName, exitCode);
        shutdown();
    }

    /**
     * Standalone server shutdown
     */
    protected void shutdown() {
        /**
         * connection factory will take care of shutting down rest of the
         * services
         */
        if (null != zooKeeperServer.getServerCnxnFactory()) {
            zooKeeperServer.getServerCnxnFactory().shutdown();
        }
        if (null != zooKeeperServer.getSecureServerCnxnFactory()) {
            zooKeeperServer.getSecureServerCnxnFactory().shutdown();
        }
    }
}
