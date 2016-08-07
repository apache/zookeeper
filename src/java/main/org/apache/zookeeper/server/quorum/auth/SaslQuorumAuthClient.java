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
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.Login;
import org.apache.zookeeper.client.ZooKeeperSaslClient;
import org.apache.zookeeper.server.quorum.QuorumAuthPacket;
import org.apache.zookeeper.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaslQuorumAuthClient implements QuorumAuthClient {
    private static final Logger LOG = LoggerFactory
            .getLogger(SaslQuorumAuthClient.class);

    private final Login clientLogin;
    private final boolean quorumRequireSasl;
    private final String quorumServicePrincipal;

    public SaslQuorumAuthClient(boolean quorumRequireSasl,
            String quorumServicePrincipal, String loginContext)
                    throws SaslException {
        this.quorumRequireSasl = quorumRequireSasl;
        this.quorumServicePrincipal = quorumServicePrincipal;
        try {
            AppConfigurationEntry entries[] = Configuration
                .getConfiguration()
                .getAppConfigurationEntry(loginContext);
            if (entries == null || entries.length == 0) {
                throw new LoginException("SASL-authentication failed because"
                                         + " the specified JAAS configuration "
                                         + "section '" + loginContext
                                         + "' could not be found.");
            }
            this.clientLogin = new Login(loginContext,
                                    new ZooKeeperSaslClient.ClientCallbackHandler(null));
            this.clientLogin.startThreadIfNeeded();
        } catch (LoginException e) {
            throw new SaslException("Failed to initialize authentication mechanism using SASL", e);
        }
    }

    @Override
    public boolean authenticate(Socket sock) throws SaslException {
        SaslClient sc = null;
        try {
            DataOutputStream dout = new DataOutputStream(
                    sock.getOutputStream());
            DataInputStream din = new DataInputStream(sock.getInputStream());
            byte[] responseToken = new byte[0];
            sc = SecurityUtils.createSaslClient(clientLogin.getSubject(),
                    quorumServicePrincipal,
                    QuorumAuth.QUORUM_SERVER_PROTOCOL_NAME,
                    QuorumAuth.QUORUM_SERVER_SASL_DIGEST, LOG);

            if (sc.hasInitialResponse()) {
                responseToken = createSaslToken(new byte[0], sc, clientLogin);
            }
            send(dout, responseToken);
            QuorumAuthPacket authPacket = receive(din);
            QuorumAuth.Status qpStatus = QuorumAuth.Status
                    .getStatus(authPacket.getStatus());
            while (!sc.isComplete()) {
                switch (qpStatus) {
                case SUCCESS:
                    responseToken = createSaslToken(authPacket.getToken(), sc,
                            clientLogin);
                    // we're done; don't expect to send another BIND
                    if (responseToken != null) {
                        throw new SaslException("Protocol error: attempting to send response after completion");
                    }
                    break;
                case IN_PROGRESS:
                    responseToken = createSaslToken(authPacket.getToken(), sc,
                            clientLogin);
                    send(dout, responseToken);
                    authPacket = receive(din);
                    qpStatus = QuorumAuth.Status
                            .getStatus(authPacket.getStatus());
                    break;
                case ERROR:
                    throw new SaslException(
                            "Authentication failed against server addr: "
                                    + sock.getRemoteSocketAddress());
                default:
                    LOG.warn("Unknown status!");
                    throw new SaslException(
                            "Authentication failed against server addr: "
                                    + sock.getRemoteSocketAddress());
                }
            }
            if (sc.isComplete()) {
                LOG.info("Successfully completed the authentication using SASL. server addr: {}, status: {}",
                        sock.getRemoteSocketAddress(), qpStatus);
            }
        } catch (Exception e) {
            // If sasl is not required, when a server initializes a
            // connection it will try to log in, but it will also
            // accept connections that do not start with a sasl
            // handshake.
            if (quorumRequireSasl) {
                throw new SaslException("Failed to authenticate using SASL: ", e);
            } else {
                LOG.warn("Failed to authenticate using SASL: ", e);
                LOG.warn("Maintaining client connection despite SASL authentication failure. server addr: {}",
                        sock.getRemoteSocketAddress() + " quorum.auth.serverRequireSasl={}", quorumRequireSasl);
                return true; // let it through, we don't require auth
            }
        } finally {
            if (sc != null) {
                try {
                    sc.dispose();
                } catch (SaslException e) {
                    LOG.error("SaslClient dispose() failed", e);
                }
            }
        }
        return true;
    }

    private QuorumAuthPacket receive(DataInputStream din) throws IOException {
        QuorumAuthPacket authPacket = new QuorumAuthPacket();
        BinaryInputArchive bia = BinaryInputArchive.getArchive(din);
        authPacket.deserialize(bia, QuorumAuth.QUORUM_AUTH_MESSAGE_TAG);
        return authPacket;
    }

    private void send(DataOutputStream dout, byte[] response)
            throws IOException {
        QuorumAuthPacket authPacket;
        BufferedOutputStream bufferedOutput = new BufferedOutputStream(dout);
        BinaryOutputArchive boa = BinaryOutputArchive
                .getArchive(bufferedOutput);
        if (response != null && response.length < 0) {
            throw new IOException("Response length < 0");
        } else if (response == null) {
            authPacket = QuorumAuth.createPacket(
                    QuorumAuth.Status.IN_PROGRESS, response);
        } else {
            authPacket = QuorumAuth.createPacket(
                    QuorumAuth.Status.IN_PROGRESS, response);
        }

        boa.writeRecord(authPacket, QuorumAuth.QUORUM_AUTH_MESSAGE_TAG);
        bufferedOutput.flush();
    }

    private byte[] createSaslToken(final byte[] saslToken,
            final SaslClient saslClient, final Login login)
                    throws SaslException {
        if (saslToken == null) {
            throw new SaslException(
                    "Error in authenticating with a Zookeeper Quorum member: the quorum member's saslToken is null.");
        }
        if (login.getSubject() != null) {
            synchronized (login) {
                try {
                    final byte[] retval = Subject.doAs(login.getSubject(),
                            new PrivilegedExceptionAction<byte[]>() {
                                public byte[] run() throws SaslException {
                                    LOG.debug("saslClient.evaluateChallenge(len="
                                                    + saslToken.length + ")");
                                    return saslClient.evaluateChallenge(saslToken);
                                }
                            });
                    return retval;
                } catch (PrivilegedActionException e) {
                    String error = "An error: (" + e
                            + ") occurred when evaluating Zookeeper Quorum Member's "
                            + " received SASL token.";
                    // Try to provide hints to use about what went wrong so they
                    // can fix their configuration.
                    // TODO: introspect about e: look for GSS information.
                    final String UNKNOWN_SERVER_ERROR_TEXT = "(Mechanism level: Server not found in Kerberos database (7) - UNKNOWN_SERVER)";
                    if (e.toString().indexOf(UNKNOWN_SERVER_ERROR_TEXT) > -1) {
                        error += " This may be caused by Java's being unable to resolve the Zookeeper Quorum Member's"
                                + " hostname correctly. You may want to try to adding"
                                + " '-Dsun.net.spi.nameservice.provider.1=dns,sun' to your server's JVMFLAGS environment.";
                    }
                    LOG.error(error);
                    throw new SaslException(error);
                }
            }
        } else {
            throw new SaslException(
                    "Cannot make SASL token without subject defined. "
                            + "For diagnosis, please look for WARNs and ERRORs in your log related to the Login class.");
        }
    }
}
