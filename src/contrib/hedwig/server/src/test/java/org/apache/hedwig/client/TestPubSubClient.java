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
package org.apache.hedwig.client;

import java.util.concurrent.SynchronousQueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.client.netty.HedwigClient;
import org.apache.hedwig.client.netty.HedwigPublisher;
import org.apache.hedwig.client.netty.HedwigSubscriber;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.ClientNotSubscribedException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.server.PubSubServerStandAloneTestBase;
import org.apache.hedwig.util.Callback;
import org.apache.hedwig.util.ConcurrencyUtils;

public class TestPubSubClient extends PubSubServerStandAloneTestBase {

    // Client side variables
    protected HedwigClient client;
    protected HedwigPublisher publisher;
    protected HedwigSubscriber subscriber;

    // SynchronousQueues to verify async calls
    private final SynchronousQueue<Boolean> queue = new SynchronousQueue<Boolean>();
    private final SynchronousQueue<Boolean> consumeQueue = new SynchronousQueue<Boolean>();

    // Test implementation of Callback for async client actions.
    class TestCallback implements Callback<Void> {

        @Override
        public void operationFinished(Object ctx, Void resultOfOperation) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (logger.isDebugEnabled())
                        logger.debug("Operation finished!");
                    ConcurrencyUtils.put(queue, true);
                }
            }).start();
        }

        @Override
        public void operationFailed(Object ctx, final PubSubException exception) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    logger.error("Operation failed!", exception);
                    ConcurrencyUtils.put(queue, false);
                }
            }).start();
        }
    }

    // Test implementation of subscriber's message handler.
    class TestMessageHandler implements MessageHandler {
        public void consume(ByteString topic, ByteString subscriberId, Message msg, Callback<Void> callback,
                Object context) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (logger.isDebugEnabled())
                        logger.debug("Consume operation finished successfully!");
                    ConcurrencyUtils.put(consumeQueue, true);
                }
            }).start();
            callback.operationFinished(context, null);
        }
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        client = new HedwigClient(new ClientConfiguration());
        publisher = client.getPublisher();
        subscriber = client.getSubscriber();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        client.stop();
        super.tearDown();
    }

    @Test
    public void testSyncPublish() throws Exception {
        boolean publishSuccess = true;
        try {
            publisher.publish(ByteString.copyFromUtf8("mySyncTopic"), Message.newBuilder().setBody(
                    ByteString.copyFromUtf8("Hello Sync World!")).build());
        } catch (Exception e) {
            publishSuccess = false;
        }
        assertTrue(publishSuccess);
    }

    @Test
    public void testAsyncPublish() throws Exception {
        publisher.asyncPublish(ByteString.copyFromUtf8("myAsyncTopic"), Message.newBuilder().setBody(
                ByteString.copyFromUtf8("Hello Async World!")).build(), new TestCallback(), null);
        assertTrue(queue.take());
    }

    @Test
    public void testMultipleAsyncPublish() throws Exception {
        ByteString topic1 = ByteString.copyFromUtf8("myFirstTopic");
        ByteString topic2 = ByteString.copyFromUtf8("myNewTopic");

        publisher.asyncPublish(topic1, Message.newBuilder().setBody(ByteString.copyFromUtf8("Hello World!")).build(),
                new TestCallback(), null);
        assertTrue(queue.take());
        publisher.asyncPublish(topic2, Message.newBuilder().setBody(ByteString.copyFromUtf8("Hello on new topic!"))
                .build(), new TestCallback(), null);
        assertTrue(queue.take());
        publisher.asyncPublish(topic1, Message.newBuilder().setBody(
                ByteString.copyFromUtf8("Hello Again on old topic!")).build(), new TestCallback(), null);
        assertTrue(queue.take());
    }

    @Test
    public void testSyncSubscribe() throws Exception {
        boolean subscribeSuccess = true;
        try {
            subscriber.subscribe(ByteString.copyFromUtf8("mySyncSubscribeTopic"), ByteString.copyFromUtf8("1"), CreateOrAttach.CREATE_OR_ATTACH);
        } catch (Exception e) {
            subscribeSuccess = false;
        }
        assertTrue(subscribeSuccess);
    }

    @Test
    public void testAsyncSubscribe() throws Exception {
        subscriber.asyncSubscribe(ByteString.copyFromUtf8("myAsyncSubscribeTopic"), ByteString.copyFromUtf8("1"),
                CreateOrAttach.CREATE_OR_ATTACH, new TestCallback(), null);
        assertTrue(queue.take());
    }

    @Test
    public void testSubscribeAndConsume() throws Exception {
        ByteString topic = ByteString.copyFromUtf8("myConsumeTopic");
        ByteString subscriberId = ByteString.copyFromUtf8("1");
        subscriber.asyncSubscribe(topic, subscriberId, CreateOrAttach.CREATE_OR_ATTACH, new TestCallback(), null);
        assertTrue(queue.take());

        // Start delivery for the subscriber
        subscriber.startDelivery(topic, subscriberId, new TestMessageHandler());

        // Now publish some messages for the topic to be consumed by the
        // subscriber.
        publisher.asyncPublish(topic, Message.newBuilder().setBody(ByteString.copyFromUtf8("Message #1")).build(),
                new TestCallback(), null);
        assertTrue(queue.take());
        assertTrue(consumeQueue.take());
        publisher.asyncPublish(topic, Message.newBuilder().setBody(ByteString.copyFromUtf8("Message #2")).build(),
                new TestCallback(), null);
        assertTrue(queue.take());
        assertTrue(consumeQueue.take());
        publisher.asyncPublish(topic, Message.newBuilder().setBody(ByteString.copyFromUtf8("Message #3")).build(),
                new TestCallback(), null);
        assertTrue(queue.take());
        assertTrue(consumeQueue.take());
        publisher.asyncPublish(topic, Message.newBuilder().setBody(ByteString.copyFromUtf8("Message #4")).build(),
                new TestCallback(), null);
        assertTrue(queue.take());
        assertTrue(consumeQueue.take());
        publisher.asyncPublish(topic, Message.newBuilder().setBody(ByteString.copyFromUtf8("Message #5")).build(),
                new TestCallback(), null);
        assertTrue(queue.take());
        assertTrue(consumeQueue.take());
    }

    @Test
    public void testAsyncSubscribeAndUnsubscribe() throws Exception {
        ByteString topic = ByteString.copyFromUtf8("myAsyncUnsubTopic");
        ByteString subscriberId = ByteString.copyFromUtf8("1");
        subscriber.asyncSubscribe(topic, subscriberId, CreateOrAttach.CREATE_OR_ATTACH, new TestCallback(), null);
        assertTrue(queue.take());
        subscriber.asyncUnsubscribe(topic, subscriberId, new TestCallback(), null);
        assertTrue(queue.take());
    }

    @Test
    public void testSyncUnsubscribeWithoutSubscription() throws Exception {
        boolean unsubscribeSuccess = false;
        try {
            subscriber.unsubscribe(ByteString.copyFromUtf8("mySyncUnsubTopic"), ByteString.copyFromUtf8("1"));
        } catch (ClientNotSubscribedException e) {
            unsubscribeSuccess = true;
        } catch (Exception ex) {
            unsubscribeSuccess = false;
        }
        assertTrue(unsubscribeSuccess);
    }

    @Test
    public void testAsyncSubscribeAndCloseSubscription() throws Exception {
        ByteString topic = ByteString.copyFromUtf8("myAsyncSubAndCloseSubTopic");
        ByteString subscriberId = ByteString.copyFromUtf8("1");
        subscriber.asyncSubscribe(topic, subscriberId, CreateOrAttach.CREATE_OR_ATTACH, new TestCallback(), null);
        assertTrue(queue.take());
        subscriber.closeSubscription(topic, subscriberId);
        assertTrue(true);
    }

}