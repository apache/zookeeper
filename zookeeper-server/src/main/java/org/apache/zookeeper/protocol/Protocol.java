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
 * Basically, wire protocol should be backward and forward compatible between minor versions.
 * However, there are several case that it's different due to Jute's limitations.
 */
public interface Protocol {

    /**
     * Deserializing {@link ConnectRequest} should be specially handled for request from client
     * version before and including ZooKeeper 3.3 which doesn't understand readOnly field.
     */
    ConnectRequest deserializeConnectRequest(InputArchive inputArchive) throws IOException;

    /**
     * Deserializing {@link ConnectResponse} should be specially handled for response from server
     * version before and including ZooKeeper 3.3 which doesn't understand readOnly field.
     */
    ConnectResponse deserializeConnectResponse(InputArchive inputArchive) throws IOException;
}
