package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class SkipRequestHandlerTest {
    private static class TestSkipRequestHandler implements SkipRequestHandler {
        boolean isSkipProcessed = false;
        SkippedRequestQueue skippedRequestQueue = spy(new SkippedRequestQueue());

        @Override
        public void skipProposing(Request request) {
            List<Request> requestsToSkip = skippedRequestQueue.addAndGet(request);
            for (Request requestToSkip : requestsToSkip) {
                processSkip(requestToSkip);
            }
        }

        void processCommit(Request request) {
            List<Request> requestsToSkip = skippedRequestQueue.setLastCommittedAndGet(request.zxid);
            for (Request requestToSkip : requestsToSkip) {
                processSkip(requestToSkip);
            }
        }

        @Override
        public void processSkip(Request request) {
            isSkipProcessed = true;
        }
    }

    @Test
    public void testSkipProposingAndSendingRequestWithoutWaiting() throws Exception {
        TestSkipRequestHandler handler = spy(new TestSkipRequestHandler());
        handler.skippedRequestQueue.setLastCommitted(1);

        Request request = getSampleErrorRequest();
        request.zxid = 1;
        handler.skipProposing(request);

        verify(handler.skippedRequestQueue, times(1)).getSkippedRequests();
        assertTrue(handler.isSkipProcessed);
    }

    @Test
    public void testSkipProposingAndWaitingToSendRequest() throws Exception {
        TestSkipRequestHandler handler = spy(new TestSkipRequestHandler());
        handler.skippedRequestQueue.setLastCommitted(1);

        Request request = getSampleErrorRequest();
        request.zxid = 2;
        handler.skipProposing(request);

        verify(handler.skippedRequestQueue, times(1)).getSkippedRequests();
        assertFalse(handler.isSkipProcessed);
    }

    @Test
    public void testProcessCommit() throws Exception {
        TestSkipRequestHandler handler = spy(new TestSkipRequestHandler());
        handler.skippedRequestQueue.setLastCommitted(1);

        Request errRequest = getSampleErrorRequest();
        errRequest.zxid = 2;
        handler.skipProposing(errRequest);

        verify(handler.skippedRequestQueue, times(1)).getSkippedRequests();
        assertFalse(handler.isSkipProcessed);

        Request okRequest = getSampleRequest();
        okRequest.zxid = 2;
        handler.processCommit(okRequest);

        assertTrue(handler.isSkipProcessed);
    }

    private Request getSampleErrorRequest() {
        Request request = spy(new Request(null, 0, 0, ZooDefs.OpCode.delete, null, null));

        request.setException(new KeeperException.NoNodeException());
        request.setHdr(new TxnHeader());
        request.getHdr().setType(ZooDefs.OpCode.error);
        request.setSkipRequestHandler(mock(LearnerHandler.class));

        return request;
    }

    private Request getSampleRequest() {
        Request request = spy(new Request(null, 0, 0, ZooDefs.OpCode.delete, null, null));

        request.setException(new KeeperException.NoNodeException());
        request.setHdr(new TxnHeader());
        request.getHdr().setType(ZooDefs.OpCode.create);
        request.setSkipRequestHandler(mock(LearnerHandler.class));

        return request;
    }
}