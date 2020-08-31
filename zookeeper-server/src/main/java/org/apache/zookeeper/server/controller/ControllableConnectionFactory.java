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

package org.apache.zookeeper.server.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extension of NIOServerCnxnFactory which can inject changes per controller commands.
 * Similar extensions can implement on top of NettyServerCnxnFactory as well.
 */
@SuppressFBWarnings(value = "SWL_SLEEP_WITH_LOCK_HELD", justification = "no dead lock")
public class ControllableConnectionFactory extends NIOServerCnxnFactory {
    private static final Logger LOG = LoggerFactory.getLogger(ControllableConnectionFactory.class);
    private long responseDelayInMs = 0;
    private long remainingRequestsToFail = 0;
    private long remainingResponsesToHold = 0;

    public ControllableConnectionFactory() {
    }

    @Override
    protected NIOServerCnxn createConnection(SocketChannel sock, SelectionKey sk, SelectorThread selectorThread)
            throws IOException {
        return new ControllableConnection(zkServer, sock, sk, this, selectorThread);
    }

    /**
     * Called by the connection to delay processing requests from the client.
    */
    public synchronized void delayRequestIfNeeded() {
        try {
            if (responseDelayInMs > 0) {
                Thread.sleep(responseDelayInMs);
            }
        } catch (InterruptedException ex) {
            LOG.warn("Interrupted while delaying requests", ex);
        }
    }

    /**
     * Check if we should fail the next incoming request.
     * If so, decrement the remaining requests to fail.
    */
    public synchronized boolean shouldFailNextRequest() {
        if (remainingRequestsToFail == 0) {
            return false;
        }

        // Value < 0 indicates fail all requests.
        if (remainingRequestsToFail > 0) {
            remainingRequestsToFail--;
        }

        return true;
    }

    /**
     * Check if we should send a response to the latest processed request (true),
     * or eat the response to mess with the client (false).
     * If so, decrement the remaining requests to eat.
    */
    public synchronized boolean shouldSendResponse() {
        if (remainingResponsesToHold == 0) {
            return true;
        }

        // Value < 0 indicates hold all the responses.
        if (remainingResponsesToHold > 0) {
            remainingResponsesToHold--;
        }
        return false;
    }

    public synchronized void delayResponses(long delayInMs) {
        if (delayInMs < 0) {
            throw new IllegalArgumentException("delay must be non-negative");
        }
        responseDelayInMs = delayInMs;
    }

    public synchronized void resetBadBehavior() {
        responseDelayInMs = 0;
        remainingRequestsToFail = 0;
        remainingResponsesToHold = 0;
    }

    public synchronized void failAllFutureRequests() {
        this.remainingRequestsToFail = -1;
    }

    public synchronized void failFutureRequests(long requestsToFail) {
        this.remainingRequestsToFail = requestsToFail;
    }

    public synchronized void holdAllFutureResponses() {
        this.remainingResponsesToHold = -1;
    }

    public synchronized void holdFutureResponses(long requestsToHold) {
        this.remainingResponsesToHold = requestsToHold;
    }
}
