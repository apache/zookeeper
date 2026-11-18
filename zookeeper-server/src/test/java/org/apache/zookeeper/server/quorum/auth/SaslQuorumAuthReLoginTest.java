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

package org.apache.zookeeper.server.quorum.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import javax.security.auth.Subject;
import javax.security.auth.login.Configuration;
import javax.security.sasl.SaslException;

import org.apache.zookeeper.Login;
import org.apache.zookeeper.common.X509Util;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests that SaslQuorumAuthLearner recovers from SASL authentication
 * failures by re-logging in to refresh stale credentials.
 *
 * <p>This addresses the scenario where the Login TGT refresh thread
 * has silently exited (due to clock skew, KDC unavailability, etc.)
 * and the cached credentials in the Subject have become stale.
 * Without the re-login logic, the learner would fail to authenticate
 * indefinitely until the process is restarted.
 */
public class SaslQuorumAuthReLoginTest extends QuorumAuthTestBase {

    private static final String JAAS_ENTRIES =
        "QuorumServer {\n"
        + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
        + "       user_test=\"mypassword\";\n"
        + "};\n"
        + "QuorumLearner {\n"
        + "       org.apache.zookeeper.server.auth.DigestLoginModule required\n"
        + "       username=\"test\"\n"
        + "       password=\"mypassword\";\n"
        + "};\n";

    private SaslQuorumAuthServer authServer;
    private SaslQuorumAuthLearner authLearner;

    @BeforeAll
    public static void setUpClass() {
        // DIGEST-MD5 is not FIPS-compliant
        System.setProperty(X509Util.FIPS_MODE_PROPERTY, "false");
        setupJaasConfig(JAAS_ENTRIES);
    }

    @AfterAll
    public static void tearDownClass() {
        System.clearProperty(X509Util.FIPS_MODE_PROPERTY);
        cleanupJaasConfig();
    }

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.getConfiguration().refresh();
        authServer = new SaslQuorumAuthServer(
            true, "QuorumServer", new HashSet<>());
        authLearner = new SaslQuorumAuthLearner(
            true, "zkquorum/localhost", "QuorumLearner");
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Test that after credential corruption and authentication failure,
     * the re-login mechanism restores valid credentials so that the
     * next authentication attempt succeeds.
     *
     * <p>Without the fix (forceReLogin on auth failure), the second
     * authentication attempt would also fail because the corrupted
     * credentials remain in the Subject.
     */
    @Test
    @Timeout(value = 30)
    public void testReLoginOnSaslAuthFailure() throws Exception {
        // Baseline: normal authentication should succeed
        runAuthentication();

        // Simulate stale/corrupted credentials by replacing the
        // password in the learner's Subject
        Login learnerLogin = authLearner.getLogin();
        Subject subject = learnerLogin.getSubject();
        subject.getPrivateCredentials().clear();
        subject.getPrivateCredentials().add("wrongpassword");

        // Authentication should fail with corrupted credentials.
        // With the fix, forceReLogin() is called inside authenticate(),
        // which restores the correct credentials from JAAS config.
        assertThrows(IOException.class, this::runAuthentication);

        // The next authentication attempt should succeed because
        // forceReLogin() restored the correct credentials.
        // Without the fix, this would fail because the corrupted
        // credentials are still in the Subject.
        assertDoesNotThrow(this::runAuthentication);
    }

    /**
     * Run a single SASL authentication exchange between the learner
     * and server over connected sockets.
     */
    private void runAuthentication() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            AtomicReference<Exception> serverError = new AtomicReference<>();

            Thread serverThread = new Thread(() -> {
                try (Socket serverSock = ss.accept()) {
                    DataInputStream din = new DataInputStream(
                        new BufferedInputStream(serverSock.getInputStream()));
                    authServer.authenticate(serverSock, din);
                } catch (Exception e) {
                    serverError.set(e);
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            try (Socket clientSock = new Socket("localhost", port)) {
                authLearner.authenticate(clientSock, "localhost");
            }

            serverThread.join(5000);
        }
    }

}