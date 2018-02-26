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
package org.apache.zookeeper.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;

import org.apache.jute.Record;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.ReplyHeader;
import org.junit.Assert;
import org.junit.Test;

public class ServerCnxnTest extends ZKTestCase {

    /**
     * Test getting a copy of authinfo to avoid parallel modification impact
     */
    @Test
    public void testServerCnxnGetAuthInfoWithCopy() throws Exception {
            MockServerCnxn serverCnxn = new MockServerCnxn();
                List<Id> authInfo = serverCnxn.getAuthInfo();
                Id id = new Id("testscheme", "test");
                serverCnxn.addAuthInfo(id);
                Assert.assertFalse(authInfo.contains(id));
                Assert.assertTrue(serverCnxn.getAuthInfo().contains(id));
    }
    
    /**
     * Mock extension of ServerCnxn dummy to test for
     * AuthInfo behavior (ZOOKEEPER-2977).
     */
    private static class MockServerCnxn extends ServerCnxn {
        public MockServerCnxn() {
        }

        @Override
        int getSessionTimeout() {
                return 0;
        }

        @Override
        void close() {
        }

        @Override
        public void sendResponse(ReplyHeader h, Record r, String tag) throws IOException {
        }

        @Override
        void sendCloseSession() {
        }

        @Override
        public void process(WatchedEvent event) {
        }

        @Override
        long getSessionId() {
                return 0;
        }

        @Override
        void setSessionId(long sessionId) {
        }

        @Override
        void sendBuffer(ByteBuffer closeConn) {
        }

        @Override
        void enableRecv() {
        }

        @Override
        void disableRecv() {
        }

        @Override
        void setSessionTimeout(int sessionTimeout) {
        }

        @Override
        protected ServerStats serverStats() {
                return null;
        }

        @Override
        public long getOutstandingRequests() {
                return 0;
        }

        @Override
        public InetSocketAddress getRemoteSocketAddress() {
                return null;
        }

        @Override
        public int getInterestOps() {
                return 0;
        }

        @Override
        public InetAddress getSocketAddress() {
            return null;
        }
    }
    
}
