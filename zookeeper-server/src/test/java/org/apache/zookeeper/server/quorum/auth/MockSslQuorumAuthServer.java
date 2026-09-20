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

import static org.junit.Assert.assertEquals;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.cert.X509Certificate;
import org.apache.zookeeper.server.quorum.UnifiedServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test stub implementation of {@link QuorumAuthServer} for SSL quorum authentication.
 * Used to verify provider wiring in {@code QuorumPeer}.
 */
public class MockSslQuorumAuthServer implements QuorumAuthServer {

    private static final Logger LOG = LoggerFactory.getLogger(MockSslQuorumAuthServer.class);
    private static String subjectX509Principal;
    private final boolean initialized;

    /**
     * Constructs a new MockSslQuorumAuthServer and reads the expected X.509 principal
     * from the system property.
     */
    public MockSslQuorumAuthServer() {
        this.initialized = true;
        subjectX509Principal = System.getProperty("zookeeper.ssl.quorum.auth.subjectX509Principal");
    }

    /**
     * @return {@code true} if this stub was constructed without error
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Authenticates the peer using its X.509 certificate. If no principal is configured,
     * authentication is skipped. If the socket is not SSL, an IOException is thrown.
     *
     * @param socket the client socket
     * @param input  the data input stream
     * @throws IOException if an I/O error occurs or the socket is not an SSL socket
     */
    @Override
    public void authenticate(Socket socket, DataInputStream input) throws IOException {
        if (subjectX509Principal == null || subjectX509Principal.isEmpty()) {
            LOG.info("No subject X.509 principal configured; skipping authentication.");
            return;
        }

        if (!(socket instanceof UnifiedServerSocket.UnifiedSocket)) {
            LOG.info("Cannot authenticate: socket is not an SSL socket.");
            throw new IOException("Socket is not an SSL socket");
        }

        X509Certificate[] chain =
                (X509Certificate[]) ((UnifiedServerSocket.UnifiedSocket) socket)
                        .getSslSocket()
                        .getSession()
                        .getPeerCertificates();

        String peerPrincipal = chain[0].getSubjectX500Principal().getName();
        LOG.info("Authenticating peer with subject principal: {}", peerPrincipal);
        assertEquals(subjectX509Principal, peerPrincipal);
    }
}
