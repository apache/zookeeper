/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum.auth;

import java.io.IOException;
import java.net.Socket;

/**
 * Test stub implementation of {@link QuorumAuthLearner} for SSL quorum authentication.
 * Used to verify provider wiring in {@code QuorumPeer}.
 */
public class MockSSLQuorumAuthLearner implements QuorumAuthLearner {

    private final boolean initialized;

    /**
     * Constructs a new MockSSLQuorumAuthLearner.
     */
    public MockSSLQuorumAuthLearner() {
        this.initialized = true;
    }

    /**
     * @return {@code true} if this stub was constructed without error
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Authenticates the learner side using SSL. Stub implementation does nothing.
     *
     * @param socket   the socket connected to the server
     * @param hostname the server hostname for authentication
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void authenticate(Socket socket, String hostname) throws IOException {
        // No-op for testing
    }
}
