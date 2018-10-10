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

package org.apache.zookeeper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ZooKeeperTestable implements Testable {
    private static final Logger LOG = LoggerFactory
            .getLogger(ZooKeeperTestable.class);

    private final ZooKeeper zooKeeper;
    private final ClientCnxn clientCnxn;

    ZooKeeperTestable(ZooKeeper zooKeeper, ClientCnxn clientCnxn) {
        this.zooKeeper = zooKeeper;
        this.clientCnxn = clientCnxn;
    }

    @Override
    public void injectSessionExpiration() {
        LOG.info("injectSessionExpiration() called");

        clientCnxn.eventThread.queueEvent(new WatchedEvent(
                Watcher.Event.EventType.None,
                Watcher.Event.KeeperState.Expired, null));
        clientCnxn.eventThread.queueEventOfDeath();
        clientCnxn.sendThread.getClientCnxnSocket().wakeupCnxn();
        clientCnxn.state = ZooKeeper.States.CLOSED;
    }
}
