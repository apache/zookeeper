/**
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server.quorum;

import java.io.File;
import java.io.IOException;

import com.yahoo.zookeeper.server.ZooKeeperObserverNotifier;
import com.yahoo.zookeeper.server.util.ObserverManager;
import com.yahoo.zookeeper.server.util.ServerObserver;

/**
 * This observable follower server class notifies the registered observers
 * whenever the server instance is started or stopped.
 * <p>
 * Application wishing to be notified of a server state change should implement
 * {@link ServerObserver} interface and register an instance of the interface
 * with {@link ObserverManager}.
 */
public class ObservableFollowerZooKeeperServer extends FollowerZooKeeperServer {

    private ZooKeeperObserverNotifier notifier;

    public ObservableFollowerZooKeeperServer(File dataDir, File dataLogDir,
            QuorumPeer self, DataTreeBuilder treeBuilder) throws IOException {
        super(dataDir, dataLogDir, self, treeBuilder);
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
