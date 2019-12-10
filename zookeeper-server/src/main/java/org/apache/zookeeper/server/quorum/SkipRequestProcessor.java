/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ServerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkipRequestProcessor implements RequestProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(SkipRequestProcessor.class);
    private final RequestProcessor nextProcessor;
    private final RequestProcessor commitProcessor;

    public SkipRequestProcessor(RequestProcessor nextProcessor, RequestProcessor commitProcessor) {
        this.nextProcessor = nextProcessor;
        this.commitProcessor = commitProcessor;
    }

    @Override
    public void processRequest(Request request) throws RequestProcessorException {
        if (request.isSkipped()) {
            /**
             * In case when the request is coming from a client that is connected to the Leader,
             * we need to submit the request to the Leader's CommitProcessor so we can SKIP it in order
             * and return the status back to the client.
             *
             * We don't need to do this when the request comes from a Follower because the request will
             * already be submitted to their own CommitProcessor and waiting to hear COMMIT or SKIP from
             * the Leader so it could reply the request status back to the client.
             */
            if (request.isFromLeader()) {
                commitProcessor.processRequest(request);
            }

            SkipRequestHandler handler = request.getSkipRequestHandler();
            handler.skipProposing(request);
            ServerMetrics.getMetrics().SKIP_COUNT.add(1);
        } else {
            nextProcessor.processRequest(request);
        }
    }

    @Override
    public void shutdown() {
        LOG.info("Shutting down");
        nextProcessor.shutdown();
    }
}