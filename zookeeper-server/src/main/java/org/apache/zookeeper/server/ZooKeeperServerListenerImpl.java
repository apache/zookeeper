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

import org.apache.zookeeper.server.ZooKeeperServer.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default listener implementation, which will be used to notify internal
 * errors. For example, if some critical thread has stopped due to fatal errors,
 * then it will get notifications and will change the state of ZooKeeper server
 * to ERROR representing an error status.
 * 默认侦听器实现，用于通知内部错误。
 * 例如，如果某个关键线程由于致命错误而停止，那么它将获得通知并将ZooKeeper服务器的状态更改为错误状态ERROR。
 */
class ZooKeeperServerListenerImpl implements ZooKeeperServerListener {
    private static final Logger LOG = LoggerFactory .getLogger(ZooKeeperServerListenerImpl.class);

    private final ZooKeeperServer zkServer;

    ZooKeeperServerListenerImpl(ZooKeeperServer zkServer) {
        this.zkServer = zkServer;
    }
    // 发生错误时候调用
    @Override
    public void notifyStopping(String threadName, int exitCode) {
        LOG.info("Thread {} exits, error code {}", threadName, exitCode);
        zkServer.setState(State.ERROR);
    }
}
