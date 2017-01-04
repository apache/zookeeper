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

package org.apache.zookeeper.server.quorum.auth;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Set;

import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.Login;
import org.apache.zookeeper.server.quorum.QuorumAuthPacket;
import org.apache.zookeeper.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaslQuorumAuthServer implements QuorumAuthServer {

    private static final Logger LOG = LoggerFactory
            .getLogger(SaslQuorumAuthServer.class);

    private final static int MAX_RETRIES = 5;
    private final Login serverLogin;
    private final boolean quorumRequireSasl;

    public SaslQuorumAuthServer(boolean quorumRequireSasl, String loginContext, Set<String> authzHosts)
            throws SaslException {
        this.quorumRequireSasl = quorumRequireSasl;
        try {
            AppConfigurationEntry entries[] = Configuration.getConfiguration()
                    .getAppConfigurationEntry(loginContext);
            if (entries == null || entries.length == 0) {
                throw new LoginException("SASL-authentication failed"
                        + " because the specified JAAS configuration "
                        + "section '" + loginContext + "' could not be found.");
            }
            SaslQuorumServerCallbackHandler saslServerCallbackHandler = new SaslQuorumServerCallbackHandler(
                    Configuration.getConfiguration(), loginContext, authzHosts);
            serverLogin = new Login(loginContext, saslServerCallbackHandler);
            serverLogin.startThreadIfNeeded();
        } catch (Throwable e) {
            throw new SaslException(
                    "Failed to initialize authentication mechanism using SASL",
                    e);
        }
    }

    @Override
    public void authenticate(Socket sock, DataInputStream din)
            throws SaslException {
        DataOutputStream dout = null;
        SaslServer ss = null;
        try {
            if (!QuorumAuth.nextPacketIsAuth(din)) {
                if (quorumRequireSasl) {
                    throw new SaslException(
                            "Learner " + sock.getRemoteSocketAddress()
                                    + " not trying to authenticate"
                                    + " and authentication is required");
                } else {
                    // let it through, we don't require auth
                    return;
                }
            }

            byte[] token = receive(din);
            int tries = 0;
            dout = new DataOutputStream(sock.getOutputStream());
            byte[] challenge = null;
            ss = SecurityUtils.createSaslServer(serverLogin.getSubject(),
                    QuorumAuth.QUORUM_SERVER_PROTOCOL_NAME,
                    QuorumAuth.QUORUM_SERVER_SASL_DIGEST, serverLogin.callbackHandler,
                    LOG);
            while (!ss.isComplete()) {
                challenge = ss.evaluateResponse(token);
                if (!ss.isComplete()) {
                    // limited number of retries.
                    if (++tries > MAX_RETRIES) {
                        send(dout, challenge, QuorumAuth.Status.ERROR);
                        LOG.warn("Failed to authenticate using SASL, server addr: {}, retries={} exceeded.",
                                sock.getRemoteSocketAddress(), tries);
                        break;
                    }
                    send(dout, challenge, QuorumAuth.Status.IN_PROGRESS);
                    token = receive(din);
                }
            }
            // Authentication exchange has completed
            if (ss.isComplete()) {
                send(dout, challenge, QuorumAuth.Status.SUCCESS);
                LOG.info("Successfully completed the authentication using SASL. learner addr: {}",
                        sock.getRemoteSocketAddress());
            }
        } catch (Exception e) {
            try {
                if (dout != null) {
                    // send error message to the learner
                    send(dout, new byte[0], QuorumAuth.Status.ERROR);
                }
            } catch (IOException ioe) {
                LOG.warn("Exception while sending failed status", ioe);
            }
            // If sasl is not required, when a server initializes a
            // connection it will try to log in, but it will also
            // accept connections that do not start with a sasl
            // handshake.
            if (quorumRequireSasl) {
                LOG.error("Failed to authenticate using SASL", e);
                throw new SaslException(
                        "Failed to authenticate using SASL: " + e.getMessage());
            } else {
                LOG.warn("Failed to authenticate using SASL", e);
                LOG.warn("Maintaining learner connection despite SASL authentication failure."
                                + " server addr: {}, {}: {}",
                        new Object[] { sock.getRemoteSocketAddress(),
                                QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED,
                                quorumRequireSasl });
                return; // let it through, we don't require auth
            }
        } finally {
            if (ss != null) {
                try {
                    ss.dispose();
                } catch (SaslException e) {
                    LOG.error("SaslServer dispose() failed", e);
                }
            }
        }
        return;
    }

    private byte[] receive(DataInputStream din) throws IOException {
        QuorumAuthPacket authPacket = new QuorumAuthPacket();
        BinaryInputArchive bia = BinaryInputArchive.getArchive(din);
        authPacket.deserialize(bia, QuorumAuth.QUORUM_AUTH_MESSAGE_TAG);
        return authPacket.getToken();
    }

    private void send(DataOutputStream dout, byte[] challenge,
            QuorumAuth.Status s) throws IOException {
        BufferedOutputStream bufferedOutput = new BufferedOutputStream(dout);
        BinaryOutputArchive boa = BinaryOutputArchive
                .getArchive(bufferedOutput);
        QuorumAuthPacket authPacket;
        if (challenge != null && challenge.length < 0) {
            throw new IOException("Response length < 0");
        } else if (challenge == null && s != QuorumAuth.Status.SUCCESS) {
            authPacket = QuorumAuth.createPacket(
                    QuorumAuth.Status.IN_PROGRESS, challenge);
        } else {
            authPacket = QuorumAuth.createPacket(s, challenge);
        }

        boa.writeRecord(authPacket, QuorumAuth.QUORUM_AUTH_MESSAGE_TAG);
        bufferedOutput.flush();
    }
}
