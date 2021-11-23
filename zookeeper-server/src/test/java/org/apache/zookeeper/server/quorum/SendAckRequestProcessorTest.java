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

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.Test;

class SendAckRequestProcessorTest extends ZKTestCase {
    static class FakeLearner extends Learner {
        public FakeLearner() {
            sock = null;
        }

        void writePacket(QuorumPacket pp, boolean flush) throws IOException {
            throw new IOException();
        }
    }

    @Test
    public void learnerSocketCloseTest() {
        SendAckRequestProcessor processor = new SendAckRequestProcessor(new FakeLearner());
        processor.processRequest(new Request(0L, 0, ZooDefs.OpCode.sync, new TxnHeader(), new LearnerInfo(), 0L));
        assertTrue(true, "should get here without exception");
    }
}