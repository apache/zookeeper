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

package org.apache.zookeeper.client;

import org.apache.yetus.audience.InterfaceAudience;

/**
 * Represents the type of connection resolution used by ZooKeeper clients.
 * This is an internal enum used for connection string and provider validation.
 */
@InterfaceAudience.Private
public enum ConnectionType {
    /**
     * Traditional host:port static server list.
     */
    HOST_PORT("Host:Port"),

    /**
     * DNS SRV record-based service discovery.
     */
    DNS_SRV("DNS SRV");

    private final String name;

    ConnectionType(final String name) {
        this.name = name;
    }

    /**
     * Returns the name for this connection type.
     * @return the name
     */
    public String getName() {
        return name;
    }
}
