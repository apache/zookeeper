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
package org.apache.hedwig.server.integration;

import java.util.concurrent.SynchronousQueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.netty.HedwigClient;
import org.apache.hedwig.client.netty.HedwigPublisher;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.server.HedwigRegionTestBase;
import org.apache.hedwig.server.integration.TestHedwigHub.TestCallback;
import org.apache.hedwig.server.integration.TestHedwigHub.TestMessageHandler;

public class TestHedwigRegion extends HedwigRegionTestBase {

    // SynchronousQueues to verify async calls
    private final SynchronousQueue<Boolean> queue = new SynchronousQueue<Boolean>();
    private final SynchronousQueue<Boolean> consumeQueue = new SynchronousQueue<Boolean>();

    @Override
    @Before
    public void setUp() throws Exception {
        numRegions = 3;
        numServersPerRegion = 4;
        super.setUp();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testMultiRegionSubscribeAndConsume() throws Exception {
        int batchSize = 10;
        // Subscribe to topics for clients in all regions
        for (HedwigClient client : regionClientsMap.values()) {
            for (int i = 0; i < batchSize; i++) {
                client.getSubscriber().asyncSubscribe(ByteString.copyFromUtf8("Topic" + i),
                        ByteString.copyFromUtf8("LocalSubscriber"), CreateOrAttach.CREATE_OR_ATTACH,
                        new TestCallback(queue), null);
                assertTrue(queue.take());
            }
        }

        // Start delivery for the local subscribers in all regions
        for (HedwigClient client : regionClientsMap.values()) {
            for (int i = 0; i < batchSize; i++) {
                client.getSubscriber().startDelivery(ByteString.copyFromUtf8("Topic" + i),
                        ByteString.copyFromUtf8("LocalSubscriber"), new TestMessageHandler(consumeQueue));
            }
        }

        // Now start publishing messages for the subscribed topics in one of the
        // regions and verify that it gets delivered and consumed in all of the
        // other ones.
        HedwigPublisher publisher = regionClientsMap.values().iterator().next().getPublisher();
        for (int i = 0; i < batchSize; i++) {
            publisher.asyncPublish(ByteString.copyFromUtf8("Topic" + i), Message.newBuilder().setBody(
                    ByteString.copyFromUtf8("Message" + i)).build(), new TestCallback(queue), null);
            assertTrue(queue.take());
        }
        // Make sure each region consumes the same set of published messages.
        for (int i = 0; i < regionClientsMap.size(); i++) {
            for (int j = 0; j < batchSize; j++) {
                assertTrue(consumeQueue.take());
            }
        }       
    }

}
