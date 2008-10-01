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

package org.apache.zookeeper.server.quorum;

import java.io.IOException;

import org.apache.zookeeper.server.ZooKeeperObserverNotifier;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.util.ObserverManager;
import org.apache.zookeeper.server.util.ServerObserver;

/**
 * This observable leader server class notifies the registered observers
 * whenever the server is started or stopped.
 * <p>
 * Application wishing to be notified of a server state change should implement
 * {@link ServerObserver} interface and register an instance of the interface
 * with {@link ObserverManager}.
 */
public class ObservableLeaderZooKeeperServer extends LeaderZooKeeperServer {

    private ZooKeeperObserverNotifier notifier;

    public ObservableLeaderZooKeeperServer(FileTxnSnapLog logFactory,
            QuorumPeer self, DataTreeBuilder treeBuilder) throws IOException {
        super(logFactory, self, treeBuilder);
        notifier=new ZooKeeperObserverNotifier(this);
    }

    public void shutdown() {
        notifier.notifyShutdown();
        super.shutdown();
    }

    public void startup() throws IOException, InterruptedException {
        super.startup();
        notifier.notifyStarted();
    }
}
