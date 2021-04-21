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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.test.TestByteBufAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ClientCnxnSocketTest {

    @BeforeEach
    public void setUp() {
        ClientCnxnSocketNetty.setTestAllocator(TestByteBufAllocator.getInstance());
    }

    @AfterEach
    public void tearDown() {
        ClientCnxnSocketNetty.clearTestAllocator();
        TestByteBufAllocator.checkForLeaks();
    }

    @Test
    public void testWhenInvalidJuteMaxBufferIsConfiguredIOExceptionIsThrown() {
        ZKClientConfig clientConfig = new ZKClientConfig();
        String value = "SomeInvalidInt";
        clientConfig.setProperty(ZKConfig.JUTE_MAXBUFFER, value);
        // verify ClientCnxnSocketNIO creation
        try {
            new ClientCnxnSocketNIO(clientConfig);
            fail("IOException is expected.");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains(value));
        }
        // verify ClientCnxnSocketNetty creation
        try {
            new ClientCnxnSocketNetty(clientConfig);
            fail("IOException is expected.");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains(value));
        }

    }

    /*
     * Tests readLength():
     * 1. successfully read packet if length == jute.maxbuffer;
     * 2. IOException is thrown if packet length is greater than jute.maxbuffer.
     */
    @Test
    public void testIOExceptionIsThrownWhenPacketLenExceedsJuteMaxBuffer() throws IOException {
        ClientCnxnSocket clientCnxnSocket = new ClientCnxnSocketNIO(new ZKClientConfig());

        // Should successfully read packet length == jute.maxbuffer
        int length = ZKClientConfig.CLIENT_MAX_PACKET_LENGTH_DEFAULT;
        clientCnxnSocket.incomingBuffer.putInt(length);
        clientCnxnSocket.incomingBuffer.rewind();
        clientCnxnSocket.readLength();

        // Failed to read packet length > jute.maxbuffer
        length = ZKClientConfig.CLIENT_MAX_PACKET_LENGTH_DEFAULT + 1;
        clientCnxnSocket.incomingBuffer.putInt(length);
        clientCnxnSocket.incomingBuffer.rewind();
        try {
            clientCnxnSocket.readLength();
            fail("IOException is expected.");
        } catch (IOException e) {
            assertEquals("Packet len " + length + " is out of range!", e.getMessage());
        }
    }
}
