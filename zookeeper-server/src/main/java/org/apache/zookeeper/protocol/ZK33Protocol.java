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
 * ZooKeeper 3.3 and earlier doesn't handle ReadOnly field of {@link ConnectRequest} and {@link ConnectResponse}.
 */
public enum ZK33Protocol implements Protocol {
    INSTANCE;

    @Override
    public ConnectRequest deserializeConnectRequest(InputArchive inputArchive) throws IOException {
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

    @Override
    public ConnectResponse deserializeConnectResponse(InputArchive inputArchive) throws IOException {
        final ConnectResponse response = new ConnectResponse();
        inputArchive.startRecord("connect");
        response.setProtocolVersion(inputArchive.readInt("protocolVersion"));
        response.setTimeOut(inputArchive.readInt("timeOut"));
        response.setSessionId(inputArchive.readLong("sessionId"));
        response.setPasswd(inputArchive.readBuffer("passwd"));
        response.setReadOnly(false); // old version doesn't have readonly concept
        inputArchive.endRecord("connect");
        return response;
    }
}
