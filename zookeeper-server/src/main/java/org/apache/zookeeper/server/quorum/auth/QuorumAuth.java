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

import java.io.DataInputStream;
import java.io.IOException;
import org.apache.jute.BinaryInputArchive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.zookeeper.server.quorum.QuorumAuthPacket;

public class QuorumAuth {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumAuth.class);

    public static final String QUORUM_SASL_AUTH_ENABLED = "quorum.auth.enableSasl";
    public static final String QUORUM_SERVER_SASL_AUTH_REQUIRED = "quorum.auth.serverRequireSasl";
    public static final String QUORUM_LEARNER_SASL_AUTH_REQUIRED = "quorum.auth.learnerRequireSasl";

    public static final String QUORUM_KERBEROS_SERVICE_PRINCIPAL = "quorum.auth.kerberos.servicePrincipal";
    public static final String QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE = "zkquorum/localhost";

    public static final String QUORUM_LEARNER_SASL_LOGIN_CONTEXT = "quorum.auth.learner.saslLoginContext";
    public static final String QUORUM_LEARNER_SASL_LOGIN_CONTEXT_DFAULT_VALUE = "QuorumLearner";

    public static final String QUORUM_SERVER_SASL_LOGIN_CONTEXT = "quorum.auth.server.saslLoginContext";
    public static final String QUORUM_SERVER_SASL_LOGIN_CONTEXT_DFAULT_VALUE = "QuorumServer";

    static final String QUORUM_SERVER_PROTOCOL_NAME = "zookeeper-quorum";
    static final String QUORUM_SERVER_SASL_DIGEST = "zk-quorum-sasl-md5";
    static final String QUORUM_AUTH_MESSAGE_TAG = "qpconnect";

    // this is negative, so that if a learner that does auth, connects to a
    // server, it'll think the received packet is an authentication packet
    public static final long QUORUM_AUTH_MAGIC_NUMBER = -0xa0dbcafecafe1234L;

    public enum Status {
         IN_PROGRESS(0), SUCCESS(1), ERROR(-1);
        private int status;

        Status(int status) {
            this.status = status;
        }

        static Status getStatus(int status) {
            switch (status) {
            case 0:
                return IN_PROGRESS;
            case 1:
                return SUCCESS;
            case -1:
                return ERROR;
            default:
                LOG.error("Unknown status:{}!", status);
                assert false : "Unknown status!";
                return ERROR;
            }
        }

        int status() {
            return status;
        }
    }

    public static QuorumAuthPacket createPacket(Status status, byte[] response) {
        return new QuorumAuthPacket(QUORUM_AUTH_MAGIC_NUMBER,
                                    status.status(), response);
    }

    public static boolean nextPacketIsAuth(DataInputStream din)
            throws IOException {
        din.mark(32);
        BinaryInputArchive bia = new BinaryInputArchive(din);
        boolean firstIsAuth = (bia.readLong("NO_TAG")
                               == QuorumAuth.QUORUM_AUTH_MAGIC_NUMBER);
        din.reset();
        return firstIsAuth;
    }
}
