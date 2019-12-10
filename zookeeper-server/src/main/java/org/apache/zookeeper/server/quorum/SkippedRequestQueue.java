package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ServerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class SkippedRequestQueue {
    private static final Logger LOG = LoggerFactory.getLogger(SkippedRequestQueue.class);

    private final AtomicLong lastCommitted = new AtomicLong(-1);
    private final LinkedBlockingQueue<Request> waitingRequests = new LinkedBlockingQueue<>();

    /**
     * Setter method for lastCommitted zxid
     */
    public void setLastCommitted(long zxid) {
        lastCommitted.set(zxid);
    }

    public List<Request> setLastCommittedAndGet(long zxid) {
        synchronized (this) {
            setLastCommitted(zxid);
            return getSkippedRequests();
        }
    }

    /**
     * Adds skipped request to the end of the queue that way we ensure the order.
     * Returns all the skipped requests that are ready to be sent back to the origin,
     * meaning if there was a COMMIT that they were waiting for
     */
    public List<Request> addAndGet(Request request) {
        ServerMetrics.getMetrics().QUORUM_PEER_SKIP_QUEUE_SIZE.add(1);
        synchronized (this) {
            waitingRequests.add(request);
            return getSkippedRequests();
        }
    }

    /**
     * This method flushed the error request queue. This method gets called from two different
     * threads thorough skipProposing() and updateLastZxidAndGetSkippedRequests().
     *
     * Leader thread calls this method every time the leader processes a COMMIT in order
     * to try to flush all the pending error requests that we short circuited.
     *
     * The RP chain thread will try to call this when short-circuiting requests
     *
     */
    public List<Request> getSkippedRequests() {
        List<Request> requestsToSkip = new ArrayList<>();
        long lastZxid = lastCommitted.get();

        while (!waitingRequests.isEmpty()) {
            Request request = waitingRequests.peek();
            if (lastZxid > request.zxid) {
                ServerMetrics.getMetrics().QUORUM_PEER_SKIP_OUT_OF_ORDER.add(1);
                LOG.error("Skip request is out of order!!!");
                System.exit(ExitCode.QUORUM_PEER_SKIP_OUT_OF_ORDER.getValue());
            }
            if (lastZxid < request.zxid) {
                break;
            }
            waitingRequests.poll();
            requestsToSkip.add(request);
            ServerMetrics.getMetrics().QUORUM_PEER_SKIP_SENT.add(1);
        }

        return requestsToSkip;
    }
}