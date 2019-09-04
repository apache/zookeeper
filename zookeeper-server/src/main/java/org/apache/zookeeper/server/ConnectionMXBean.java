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

package org.apache.zookeeper.server;

/**
 * This MBean represents a client connection.
 */
public interface ConnectionMXBean {

    /**
     * @return source (client) IP address
     */
    String getSourceIP();
    /**
     * @return client's session id
     */
    String getSessionId();
    /**
     * @return time the connection was started
     */
    String getStartedTime();
    /**
     * @return number of ephemeral nodes owned by this connection
     */
    String[] getEphemeralNodes();
    /**
     * @return packets received from this client
     */
    long getPacketsReceived();
    /**
     * @return number of packets sent to this client
     */
    long getPacketsSent();
    /**
     * @return number of requets being processed
     */
    long getOutstandingRequests();
    /**
     * @return session timeout in ms
     */
    int getSessionTimeout();

    /**
     * Terminate this client session. The client will reconnect with a different
     * session id.
     */
    void terminateSession();
    /**
     * Terminate thei client connection. The client will immediately attempt to
     * reconnect with the same session id.
     */
    void terminateConnection();

    /** Min latency in ms
     * @since 3.3.0 */
    long getMinLatency();
    /** Average latency in ms
     * @since 3.3.0 */
    long getAvgLatency();
    /** Max latency in ms
     * @since 3.3.0 */
    long getMaxLatency();
    /** Last operation performed by this connection
     * @since 3.3.0 */
    String getLastOperation();
    /** Last cxid of this connection
     * @since 3.3.0 */
    String getLastCxid();
    /** Last zxid of this connection
     * @since 3.3.0 */
    String getLastZxid();
    /** Last time server sent a response to client on this connection
     * @since 3.3.0 */
    String getLastResponseTime();
    /** Latency of last response to client on this connection in ms
     * @since 3.3.0 */
    long getLastLatency();

    /** Reset counters
     * @since 3.3.0 */
    void resetCounters();

}
