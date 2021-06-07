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

import org.apache.zookeeper.server.quorum.QuorumPeerMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emulates {@link QuorumPeerMain#main(String[])} with the provided {@link EmbeddedConfig}.
 */
final class QuorumPeerMainHandle implements ZooKeeperServerHandle {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumPeerMainHandle.class);

    private final Thread thread;
    private final QuorumPeerMain peer;

    QuorumPeerMainHandle(final EmbeddedConfig config) {
        this.peer = new QuorumPeerMain();
        this.thread = new Thread(() -> QuorumPeerMain.handledInit(this.peer, config.mainArgs()));
        this.thread.start();
    }

    @Override
    public void join() throws InterruptedException {
        this.thread.join();
    }

    @Override
    public void close() {
        LOG.info("Stopping ZK Server");
        this.peer.close();
    }
}
