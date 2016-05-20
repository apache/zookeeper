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

import javax.security.sasl.SaslException;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.server.quorum.QuorumAuthPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NullQuorumAuthServer implements QuorumAuthServer {
    private static final Logger LOG = LoggerFactory
            .getLogger(NullQuorumAuthServer.class);
    @Override
    public boolean authenticate(Socket sock, DataInputStream din)
            throws SaslException, IOException {
        boolean nextPacketIsAuth = false;
        try {
            nextPacketIsAuth = QuorumAuth.nextPacketIsAuth(din);
        } catch (IOException e) {
            LOG.warn("Exception while checking auth packet", e);
            return false;
        }
        if (nextPacketIsAuth) {
            // This situation can occur when the client server is upgraded to
            // 'quorum.auth.clientEnableSasl=true' and start sending the auth
            // packets to a auth-disabled server. Here just reading out the
            // auth packet and send an ERROR packet to the peer client server.
            QuorumAuthPacket authPacket = new QuorumAuthPacket();
            BinaryInputArchive bia = BinaryInputArchive.getArchive(din);
            authPacket.deserialize(bia, QuorumAuth.QUORUM_AUTH_MESSAGE_TAG);

            DataOutputStream dout = new DataOutputStream(sock.getOutputStream());
            BufferedOutputStream bufferedOutput = new BufferedOutputStream(dout);
            BinaryOutputArchive boa = BinaryOutputArchive
                    .getArchive(bufferedOutput);
            boa.writeRecord(QuorumAuth.createPacket(
                    QuorumAuth.Status.ERROR, null), QuorumAuth.QUORUM_AUTH_MESSAGE_TAG);
            bufferedOutput.flush();
        }
        return true;
    }
}
