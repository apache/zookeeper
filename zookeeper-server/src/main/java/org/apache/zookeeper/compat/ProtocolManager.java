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

package org.apache.zookeeper.compat;

import java.io.IOException;
import org.apache.jute.InputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;

/**
 * A manager for switching behaviours between difference wire protocol.
 * <p>
 * Basically, wire protocol should be backward and forward compatible between minor versions.
 * However, there are several cases that it's different due to Jute's limitations.
 */
public final class ProtocolManager {
    private volatile Boolean isReadonlyAvailable = null;

    public boolean isReadonlyAvailable() {
        return isReadonlyAvailable != null && isReadonlyAvailable;
    }

    /**
     * Deserializing {@link ConnectRequest} should be specially handled for request from client
     * version before and including ZooKeeper 3.3 which doesn't understand readOnly field.
     */
    public ConnectRequest deserializeConnectRequest(InputArchive inputArchive) throws IOException {
        if (isReadonlyAvailable != null) {
            if (isReadonlyAvailable) {
                return deserializeConnectRequestWithReadonly(inputArchive);
            } else {
                return deserializeConnectRequestWithoutReadonly(inputArchive);
            }
        }

        final ConnectRequest request = deserializeConnectRequestWithoutReadonly(inputArchive);
        try {
            request.setReadOnly(inputArchive.readBool("readOnly"));
            this.isReadonlyAvailable = true;
        } catch (Exception e) {
            request.setReadOnly(false); // old version doesn't have readonly concept
            this.isReadonlyAvailable = false;
        }
        return request;
    }

    private ConnectRequest deserializeConnectRequestWithReadonly(InputArchive inputArchive) throws IOException {
        final ConnectRequest request = new ConnectRequest();
        request.deserialize(inputArchive, "connect");
        return request;
    }

    private ConnectRequest deserializeConnectRequestWithoutReadonly(InputArchive inputArchive) throws IOException {
        final ConnectRequest request = new ConnectRequest();
        inputArchive.startRecord("connect");
        request.setProtocolVersion(inputArchive.readInt("protocolVersion"));
        request.setLastZxidSeen(inputArchive.readLong("lastZxidSeen"));
        request.setTimeOut(inputArchive.readInt("timeOut"));
        request.setSessionId(inputArchive.readLong("sessionId"));
        request.setPasswd(inputArchive.readBuffer("passwd"));
        inputArchive.endRecord("connect");
        return request;
    }

    /**
     * Deserializing {@link ConnectResponse} should be specially handled for response from server
     * version before and including ZooKeeper 3.3 which doesn't understand readOnly field.
     */
    public ConnectResponse deserializeConnectResponse(InputArchive inputArchive) throws IOException {
        if (isReadonlyAvailable != null) {
            if (isReadonlyAvailable) {
                return deserializeConnectResponseWithReadonly(inputArchive);
            } else {
                return deserializeConnectResponseWithoutReadonly(inputArchive);
            }
        }

        final ConnectResponse response = deserializeConnectResponseWithoutReadonly(inputArchive);
        try {
            response.setReadOnly(inputArchive.readBool("readOnly"));
            this.isReadonlyAvailable = true;
        } catch (Exception e) {
            response.setReadOnly(false); // old version doesn't have readonly concept
            this.isReadonlyAvailable = false;
        }
        return response;
    }

    private ConnectResponse deserializeConnectResponseWithReadonly(InputArchive inputArchive) throws IOException {
        final ConnectResponse response = new ConnectResponse();
        response.deserialize(inputArchive, "connect");
        return response;
    }

    private ConnectResponse deserializeConnectResponseWithoutReadonly(InputArchive inputArchive) throws IOException {
        final ConnectResponse response = new ConnectResponse();
        inputArchive.startRecord("connect");
        response.setProtocolVersion(inputArchive.readInt("protocolVersion"));
        response.setTimeOut(inputArchive.readInt("timeOut"));
        response.setSessionId(inputArchive.readLong("sessionId"));
        response.setPasswd(inputArchive.readBuffer("passwd"));
        inputArchive.endRecord("connect");
        return response;
    }
}
