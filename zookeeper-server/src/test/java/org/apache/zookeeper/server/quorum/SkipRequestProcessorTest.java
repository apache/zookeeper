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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Test;

public class SkipRequestProcessorTest {

    private SkipRequestProcessor processor;

    @Test
    public void testProcessingValidRequests() throws Exception {
        ProposalRequestProcessor proposalProcessor = mock(ProposalRequestProcessor.class);
        CommitProcessor commitProcessor = mock(CommitProcessor.class);

        Request request = spy(new Request(null, 0, 0, OpCode.create, null, null));
        request.setHdr(new TxnHeader());

        processor = spy(new SkipRequestProcessor(proposalProcessor, commitProcessor));
        processor.processRequest(request);

        verify(request, times(1)).isSkipped();
        verify(proposalProcessor, times(1)).processRequest(request);
        verify(commitProcessor, times(0)).processRequest(request);
    }

    @Test
    public void testSkippingInvalidRequestsFromFollower() throws Exception {
        ProposalRequestProcessor proposalProcessor = mock(ProposalRequestProcessor.class);
        CommitProcessor commitProcessor = mock(CommitProcessor.class);

        Request request = spy(new Request(null, 0, 0, OpCode.delete, null, null));
        LearnerHandler handler = mock(LearnerHandler.class);

        request.setException(new KeeperException.NoNodeException());
        request.setHdr(new TxnHeader());
        request.getHdr().setType(OpCode.error);
        request.setSkipRequestHandler(handler);
        request.setSkipped();

        processor = spy(new SkipRequestProcessor(proposalProcessor, commitProcessor));
        processor.processRequest(request);

        verify(request, times(1)).isSkipped();
        verify(handler, times(1)).skipProposing(request);
        verify(proposalProcessor, times(0)).processRequest(request);
    }

    @Test
    public void testSkippingInvalidRequestsFromLeader() throws Exception {
        LeaderZooKeeperServer server = mock(LeaderZooKeeperServer.class);
        Leader leader = mock(Leader.class);
        leader.lastCommitted = 100;
        when(server.getLeader()).thenReturn(leader);

        ProposalRequestProcessor proposalProcessor = mock(ProposalRequestProcessor.class);
        CommitProcessor commitProcessor = mock(CommitProcessor.class);

        Request request = spy(new Request(null, 0, 0, OpCode.error, null, null));
        LeaderHandler handler = mock(LeaderHandler.class);

        request.setException(new KeeperException.NoNodeException());
        request.setHdr(new TxnHeader());
        request.getHdr().setType(OpCode.error);
        request.setSkipRequestHandler(handler);
        request.setOwner(ServerCnxn.me);
        request.setSkipped();

        processor = spy(new SkipRequestProcessor(proposalProcessor, commitProcessor));
        processor.processRequest(request);

        verify(request, times(1)).isSkipped();
        verify(handler, times(1)).skipProposing(request);
        verify(proposalProcessor, times(0)).processRequest(request);
        verify(commitProcessor, times(1)).processRequest(request);
    }
}