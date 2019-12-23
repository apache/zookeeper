package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.Request;

import java.util.List;

public interface SkipRequestHandler {
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
    void skipProposing(Request request);

    /**
     * This may be called from the RP chain and Leader threads.
     * Note that LeaderHandler and LearnerHandler process skipped requests differently.
     *
     * LeaderHandler modifies the CommitProcessor directly while
     * LearnerHandler needs to send the SKIP packet over the network.
     */
    void processSkip(Request request);


    /**
     * Default method to abstract skip request processing
     */
    default void processSkippedRequests(List<Request> requests) {
        for (Request request : requests) {
            processSkip(request);
        }
    }
}