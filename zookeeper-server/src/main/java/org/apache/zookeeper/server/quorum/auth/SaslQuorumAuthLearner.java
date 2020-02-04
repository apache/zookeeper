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
import org.apache.zookeeper.SaslClientCallbackHandler;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.quorum.QuorumAuthPacket;
import org.apache.zookeeper.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaslQuorumAuthLearner implements QuorumAuthLearner {

    private static final Logger LOG = LoggerFactory.getLogger(SaslQuorumAuthLearner.class);

    private final Login learnerLogin;
    private final boolean quorumRequireSasl;
    private final String quorumServicePrincipal;

    public SaslQuorumAuthLearner(
        boolean quorumRequireSasl,
        String quorumServicePrincipal,
        String loginContext) throws SaslException {
        this.quorumRequireSasl = quorumRequireSasl;
        this.quorumServicePrincipal = quorumServicePrincipal;
        try {
            AppConfigurationEntry[] entries = Configuration.getConfiguration().getAppConfigurationEntry(loginContext);
            if (entries == null || entries.length == 0) {
                throw new LoginException(String.format(
                    "SASL-authentication failed because the specified JAAS configuration section '%s' could not be found.",
                    loginContext));
            }
            this.learnerLogin = new Login(
                loginContext,
                new SaslClientCallbackHandler(null, "QuorumLearner"),
                new ZKConfig());
            this.learnerLogin.startThreadIfNeeded();
        } catch (LoginException e) {
            throw new SaslException("Failed to initialize authentication mechanism using SASL", e);
        }
    }

    @Override
    public void authenticate(Socket sock, String hostName) throws IOException {
        if (!quorumRequireSasl) { // let it through, we don't require auth
            LOG.info(
                "Skipping SASL authentication as {}={}",
                QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED,
                quorumRequireSasl);
            return;
        }
        SaslClient sc = null;
        String principalConfig = SecurityUtils.getServerPrincipal(quorumServicePrincipal, hostName);
        try {
            DataOutputStream dout = new DataOutputStream(sock.getOutputStream());
            DataInputStream din = new DataInputStream(sock.getInputStream());
            byte[] responseToken = new byte[0];
            sc = SecurityUtils.createSaslClient(
                learnerLogin.getSubject(),
                principalConfig,
                QuorumAuth.QUORUM_SERVER_PROTOCOL_NAME,
                QuorumAuth.QUORUM_SERVER_SASL_DIGEST,
                LOG,
                "QuorumLearner");

            if (sc.hasInitialResponse()) {
                responseToken = createSaslToken(new byte[0], sc, learnerLogin);
            }
            send(dout, responseToken);
            QuorumAuthPacket authPacket = receive(din);
            QuorumAuth.Status qpStatus = QuorumAuth.Status.getStatus(authPacket.getStatus());
            while (!sc.isComplete()) {
                switch (qpStatus) {
                case SUCCESS:
                    responseToken = createSaslToken(authPacket.getToken(), sc, learnerLogin);
                    // we're done; don't expect to send another BIND
                    if (responseToken != null) {
                        throw new SaslException("Protocol error: attempting to send response after completion");
                    }
                    break;
                case IN_PROGRESS:
                    responseToken = createSaslToken(authPacket.getToken(), sc, learnerLogin);
                    send(dout, responseToken);
                    authPacket = receive(din);
                    qpStatus = QuorumAuth.Status.getStatus(authPacket.getStatus());
                    break;
                case ERROR:
                    throw new SaslException("Authentication failed against server addr: " + sock.getRemoteSocketAddress());
                default:
                    LOG.warn("Unknown status:{}!", qpStatus);
                    throw new SaslException("Authentication failed against server addr: " + sock.getRemoteSocketAddress());
                }
            }

            // Validate status code at the end of authentication exchange.
            checkAuthStatus(sock, qpStatus);
        } finally {
            if (sc != null) {
                try {
                    sc.dispose();
                } catch (SaslException e) {
                    LOG.error("SaslClient dispose() failed", e);
                }
            }
        }
    }

    private void checkAuthStatus(Socket sock, QuorumAuth.Status qpStatus) throws SaslException {
        if (qpStatus == QuorumAuth.Status.SUCCESS) {
            LOG.info(
                "Successfully completed the authentication using SASL. server addr: {}, status: {}",
                sock.getRemoteSocketAddress(),
                qpStatus);
        } else {
            throw new SaslException("Authentication failed against server addr: " + sock.getRemoteSocketAddress()
                                    + ", qpStatus: " + qpStatus);
        }
    }

    private QuorumAuthPacket receive(DataInputStream din) throws IOException {
        QuorumAuthPacket authPacket = new QuorumAuthPacket();
        BinaryInputArchive bia = BinaryInputArchive.getArchive(din);
        authPacket.deserialize(bia, QuorumAuth.QUORUM_AUTH_MESSAGE_TAG);
        return authPacket;
    }

    private void send(DataOutputStream dout, byte[] response) throws IOException {
        QuorumAuthPacket authPacket;
        BufferedOutputStream bufferedOutput = new BufferedOutputStream(dout);
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(bufferedOutput);
        authPacket = QuorumAuth.createPacket(QuorumAuth.Status.IN_PROGRESS, response);
        boa.writeRecord(authPacket, QuorumAuth.QUORUM_AUTH_MESSAGE_TAG);
        bufferedOutput.flush();
    }

    // TODO: need to consolidate the #createSaslToken() implementation between ZooKeeperSaslClient#createSaslToken().
    private byte[] createSaslToken(
        final byte[] saslToken,
        final SaslClient saslClient,
        final Login login) throws SaslException {
        if (saslToken == null) {
            throw new SaslException("Error in authenticating with a Zookeeper Quorum member: the quorum member's saslToken is null.");
        }
        if (login.getSubject() != null) {
            synchronized (login) {
                try {
                    final byte[] retval = Subject.doAs(login.getSubject(), new PrivilegedExceptionAction<byte[]>() {
                        public byte[] run() throws SaslException {
                            LOG.debug("saslClient.evaluateChallenge(len={})", saslToken.length);
                            return saslClient.evaluateChallenge(saslToken);
                        }
                    });
                    return retval;
                } catch (PrivilegedActionException e) {
                    String error = "An error: (" + e + ") occurred when evaluating Zookeeper Quorum Member's received SASL token.";
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
                    throw new SaslException(error, e);
                }
            }
        } else {
            throw new SaslException("Cannot make SASL token without subject defined. "
                                    + "For diagnosis, please look for WARNs and ERRORs in your log related to the Login class.");
        }
    }

}
