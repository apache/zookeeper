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

package org.apache.zookeeper.server.quorum;

import java.util.concurrent.LinkedBlockingQueue;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;

/**
 * Allows the blocking of the request processor queue on a ZooKeeperServer.
 *
 * This is used to simulate arbitrary length delays or to produce delays
 * in request processing that are maximally inconvenient for a given feature
 * for the purposes of testing it.
 */
public class DelayRequestProcessor implements RequestProcessor {

    private boolean blocking;
    RequestProcessor next;

    private LinkedBlockingQueue<Request> incomingRequests = new LinkedBlockingQueue<>();

    private DelayRequestProcessor(RequestProcessor next) {
        this.blocking = true;
        this.next = next;
    }

    @Override
    public void processRequest(Request request) throws RequestProcessorException {
        if (blocking) {
            incomingRequests.add(request);
        } else {
            next.processRequest(request);
        }
    }

    public void submitRequest(Request request) throws RequestProcessorException {
        next.processRequest(request);
    }

    @Override
    public void shutdown() {
    }

    public void unblockQueue() throws RequestProcessorException {
        if (blocking) {
            for (Request request : incomingRequests) {
                next.processRequest(request);
            }
            blocking = false;
        }
    }

    public static DelayRequestProcessor injectDelayRequestProcessor(FollowerZooKeeperServer zooKeeperServer) {
        RequestProcessor finalRequestProcessor = zooKeeperServer.commitProcessor.nextProcessor;
        DelayRequestProcessor delayRequestProcessor = new DelayRequestProcessor(finalRequestProcessor);
        zooKeeperServer.commitProcessor.nextProcessor = delayRequestProcessor;
        return delayRequestProcessor;
    }

}
