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

package org.apache.zookeeper.server.quorum;

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.ServerMetrics;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LearnerHandlerMetricsTest {
    private MockLearnerHandler learnerHandler;
    private long sid = 5;

    class MockLearnerHandler extends LearnerHandler {
        MockLearnerHandler(Socket socket, Leader leader) throws IOException {
            super(socket, null, leader);
        }
    }

    @Before
    public void setup() throws IOException {
        Leader leader = mock(Leader.class);
        when(leader.getQuorumAuthServer()).thenReturn(null);

        Socket socket = mock(Socket.class);
        when(socket.getRemoteSocketAddress()).thenReturn(new InetSocketAddress(32));

        //adding 5ms artificial delay when sending each packet
        BinaryOutputArchive oa = mock(BinaryOutputArchive.class);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Thread.sleep(5);
                return  null;
            }
        }).when(oa).writeRecord(any(QuorumPacket.class), Matchers.anyString());

        learnerHandler = new MockLearnerHandler(socket, leader);
        learnerHandler.setOutputArchive(oa);
        learnerHandler.setBufferedOutput(mock(BufferedOutputStream.class));
        learnerHandler.sid = sid;
    }

    @Test
    public void testMetrics() {
        ServerMetrics.getMetrics().resetAll();

        //adding 1001 packets in the queue, two marker packets will be added since the interval is every 1000 packets
        for (int i=0; i<1001; i++) {
            learnerHandler.queuePacket(new QuorumPacket());
        }
        learnerHandler.startSendingPackets();

        //make sure we have enough time to send all the packets in the queue
        try {
            Thread.sleep(8000);
        } catch (Exception e) {

        }

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        String sidStr = Long.toString(sid);

        //we record time for each marker packet and we have two marker packets
        Assert.assertEquals(2L,  values.get("cnt_" + sidStr + "_learner_handler_qp_time_ms"));

        //the second marker has 1000 packets in front of it and each takes 5 ms to send so the time in queue should be
        //longer than 5*1000
        Assert.assertThat((long)values.get("max_" + sidStr + "_learner_handler_qp_time_ms"), greaterThan(5000L));

        //we send 1001 packets + 2 marker packets so the queue size is recorded 1003 times
        Assert.assertEquals(1003L, values.get("cnt_" + sidStr + "_learner_handler_qp_size"));

        //the longest queue size is recorded when we are sending the first packet
        Assert.assertEquals(1002L, values.get("max_" + sidStr + "_learner_handler_qp_size"));

        //this is when the queue is emptied
        Assert.assertEquals(0L, values.get("min_" + sidStr + "_learner_handler_qp_size"));

    }
}
