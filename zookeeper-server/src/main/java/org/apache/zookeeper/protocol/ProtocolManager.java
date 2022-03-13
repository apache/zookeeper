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

package org.apache.zookeeper.protocol;

import java.io.IOException;
import org.apache.jute.InputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;

/**
 * A facade for switching behaviours between difference {@link Protocol}.
 */
public class ProtocolManager {
    private volatile Protocol protocol = null;

    public boolean isZK33Protol() {
        return protocol != null && protocol instanceof ZK33Protocol;
    }

    public ConnectRequest deserializeConnectRequest(InputArchive inputArchive) throws IOException {
        if (protocol != null) {
            return protocol.deserializeConnectRequest(inputArchive);
        }

        final ConnectRequest request = ZK33Protocol.INSTANCE.deserializeConnectRequest(inputArchive);
        try {
            request.setReadOnly(inputArchive.readBool("readOnly"));
            this.protocol = DefaultProtocol.INSTANCE;
            return request;
        } catch (Exception e) {
            request.setReadOnly(false); // old version doesn't have readonly concept
            this.protocol = ZK33Protocol.INSTANCE;
            return request;
        }
    }

    public ConnectResponse deserializeConnectResponse(InputArchive inputArchive) throws IOException {
        if (protocol != null) {
            return protocol.deserializeConnectResponse(inputArchive);
        }

        final ConnectResponse response = ZK33Protocol.INSTANCE.deserializeConnectResponse(inputArchive);
        try {
            response.setReadOnly(inputArchive.readBool("readOnly"));
            this.protocol = DefaultProtocol.INSTANCE;
            return response;
        } catch (Exception e) {
            response.setReadOnly(false); // old version doesn't have readonly concept
            this.protocol = ZK33Protocol.INSTANCE;
            return response;
        }
    }
}
