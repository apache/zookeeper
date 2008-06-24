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

package org.apache.zookeeper.server.util;

import org.apache.zookeeper.server.ZooKeeperServer;

/**
 * Application must implement this interface and register its instance with
 * the {@link ObserverManager}.
 */
public interface ServerObserver {
    /**
     * The server just started.
     * @param server the new fully initialized instance of the server
     */
    public void onStartup(ZooKeeperServer server);
    /**
     * Tne server is about to shutdown.
     * @param server instance of zookeeper server
     */
    public void onShutdown(ZooKeeperServer server);
}
