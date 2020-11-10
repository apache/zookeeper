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

package org.apache.zookeeper.server;

import java.util.concurrent.CountDownLatch;
import org.apache.zookeeper.server.ZooKeeperServer.State;

/**
 * ZooKeeper server shutdown handler which will be used to handle ERROR or
 * SHUTDOWN server state transitions, which in turn releases the associated
 * shutdown latch.
 */
public final class ZooKeeperServerShutdownHandler {

        private final CountDownLatch shutdownLatch;

    ZooKeeperServerShutdownHandler(CountDownLatch shutdownLatch) {
        this.shutdownLatch = shutdownLatch;
    }

    /**
     * This will be invoked when the server transition to a new server state.
     *
     * @param state new server state
     */
    public void handle(State state) {
        if (state == State.ERROR || state == State.SHUTDOWN) {
            shutdownLatch.countDown();
        }
    }

}
