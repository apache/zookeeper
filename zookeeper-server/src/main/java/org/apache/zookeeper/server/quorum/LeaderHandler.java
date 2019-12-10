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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * This class is used to process commits on the Leader side.
 *
 * When -Dzookeeper.quorum.skipTxnEnabled=true:
 *   After commit is processed, LeaderHandler will commit
 *   the request and try to flush a queue of skipped transactions
 *   that occurred right before that commit.
 */
public class LeaderHandler implements SkipRequestHandler {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderHandler.class);

    private final CommitProcessor commitProcessor;
    private final SkippedRequestQueue skippedRequestQueue;

    public LeaderHandler(CommitProcessor commitProcessor) {
        this.commitProcessor = commitProcessor;
        this.skippedRequestQueue = new SkippedRequestQueue();
    }

    /**
     * This method must be called from the RP chain thread
     * right after PrepRequestProcessor. Once it's known that
     * the request is invalid we can skip sending PROPOSE and go directly to
     * COMMIT but we need to wait to do that in order with the other requests.
     *
     * The request will be immediately sent back to the learner if there is no pending commits
     * otherwise the request will be put inside the handler's queue to wait for pending commit
     * to complete before is sent so we make sure we send all requests in order.
     */
    @Override
    public void skipProposing(Request request) {
        List<Request> requests = skippedRequestQueue.addAndGet(request);
        processSkippedRequests(requests);
    }

    /**
     * Leader specific commitProcessing logic
     */
    public void processCommit(Request request) {
        commitProcessor.commit(request);
        List<Request> requests = skippedRequestQueue.setLastCommittedAndGet(request.zxid);
        processSkippedRequests(requests);
    }

    /**
     * This method gets called from the getSkippedRequests() method
     * For each request inside the queue we will run this method.
     *
     * Note this method is differently implemented
     * in LeaderHandler and LearnerHandler.
     *
     * LeaderHandler commits directly in it's own commit processor.
     * LearnerHandler must send SKIP message to the Learners so they can
     * commit the request in their own commit processor.
     */
    @Override
    public void processSkip(Request request) {
        commitProcessor.commit(request);
    }
}