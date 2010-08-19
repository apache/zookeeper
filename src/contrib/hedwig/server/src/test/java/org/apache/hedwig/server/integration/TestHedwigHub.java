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

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.SynchronousQueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.client.exceptions.InvalidSubscriberIdException;
import org.apache.hedwig.client.netty.HedwigClient;
import org.apache.hedwig.client.netty.HedwigPublisher;
import org.apache.hedwig.client.netty.HedwigSubscriber;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.ClientNotSubscribedException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.OperationType;
import org.apache.hedwig.protocol.PubSubProtocol.ProtocolVersion;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubResponse;
import org.apache.hedwig.protocol.PubSubProtocol.StartDeliveryRequest;
import org.apache.hedwig.protocol.PubSubProtocol.StatusCode;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.protoextensions.SubscriptionStateUtils;
import org.apache.hedwig.server.HedwigHubTestBase;
import org.apache.hedwig.server.netty.WriteRecordingChannel;
import org.apache.hedwig.server.proxy.HedwigProxy;
import org.apache.hedwig.server.proxy.ProxyConfiguration;
import org.apache.hedwig.server.regions.HedwigHubClient;
import org.apache.hedwig.util.Callback;
import org.apache.hedwig.util.ConcurrencyUtils;

@RunWith(Parameterized.class)
public class TestHedwigHub extends HedwigHubTestBase {

    // Client side variables
    protected HedwigClient client;
    protected HedwigPublisher publisher;
    protected HedwigSubscriber subscriber;

    // Common ByteStrings used in tests.
    private final ByteString localSubscriberId = ByteString.copyFromUtf8("LocalSubscriber");
    private final ByteString hubSubscriberId = ByteString.copyFromUtf8(SubscriptionStateUtils.HUB_SUBSCRIBER_PREFIX
            + "HubSubcriber");

    enum Mode {
        REGULAR, PROXY, SSL
    };

    @Parameters
    public static Collection<Object[]> configs() {
        return Arrays.asList(new Object[][] { { Mode.PROXY }, { Mode.REGULAR }, { Mode.SSL }});
    }

    protected Mode mode;

    public TestHedwigHub(Mode mode) {
        this.mode = mode;
    }

    protected HedwigProxy proxy;
    protected ProxyConfiguration proxyConf = new ProxyConfiguration();

    // SynchronousQueues to verify async calls
    private final SynchronousQueue<Boolean> queue = new SynchronousQueue<Boolean>();
    private final SynchronousQueue<Boolean> consumeQueue = new SynchronousQueue<Boolean>();

    // Test implementation of Callback for async client actions.
    static class TestCallback implements Callback<Void> {
        private final SynchronousQueue<Boolean> queue;

        public TestCallback(SynchronousQueue<Boolean> queue) {
            this.queue = queue;
        }

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
    static class TestMessageHandler implements MessageHandler {
        // For subscribe reconnect testing, the server could send us back
        // messages we've already processed and consumed. We need to keep
        // track of the ones we've encountered so we only signal back to the
        // consumeQueue once.
        private HashSet<MessageSeqId> consumedMessages = new HashSet<MessageSeqId>();
        private long largestMsgSeqIdConsumed = -1;
        private final SynchronousQueue<Boolean> consumeQueue;

        public TestMessageHandler(SynchronousQueue<Boolean> consumeQueue) {
            this.consumeQueue = consumeQueue;
        }

        public void consume(ByteString topic, ByteString subscriberId, final Message msg, Callback<Void> callback,
                Object context) {
            if (!consumedMessages.contains(msg.getMsgId())) {
                // New message to consume. Add it to the Set of consumed
                // messages.
                consumedMessages.add(msg.getMsgId());
                // Check that the msg seq ID is incrementing by 1 compared to
                // the last consumed message. Don't do this check if this is the
                // initial message being consumed.
                if (largestMsgSeqIdConsumed >= 0 && msg.getMsgId().getLocalComponent() != largestMsgSeqIdConsumed + 1) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if (logger.isDebugEnabled())
                                logger.debug("Consuming message that is out of order for msgId: "
                                        + msg.getMsgId().getLocalComponent());
                            ConcurrencyUtils.put(consumeQueue, false);
                        }
                    }).start();
                } else {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if (logger.isDebugEnabled())
                                logger.debug("Consume operation finished successfully!");
                            ConcurrencyUtils.put(consumeQueue, true);
                        }
                    }).start();
                }
                // Store the consumed message as the new last msg id consumed.
                largestMsgSeqIdConsumed = msg.getMsgId().getLocalComponent();
            } else {
                if (logger.isDebugEnabled())
                    logger.debug("Consumed a message that we've processed already: " + msg);
            }
            callback.operationFinished(context, null);
        }
    }

    class TestClientConfiguration extends ClientConfiguration {
        @Override
        public InetSocketAddress getDefaultServerHost() {
            if (mode == Mode.PROXY) {
                return new InetSocketAddress(proxyConf.getProxyPort());
            } else {
                return super.getDefaultServerHost();
            }
        }

        @Override
        public boolean isSSLEnabled() {
            if (mode == Mode.SSL)
                return true;
            else
                return false;
        }
    }

    // ClientConfiguration to use for this test.
    protected ClientConfiguration getClientConfiguration() {
        return new TestClientConfiguration();
    }

    @Override
    @Before
    public void setUp() throws Exception {
        numServers = 3;
        super.setUp();
        if (mode == Mode.PROXY) {
            proxy = new HedwigProxy(proxyConf);
        }
        client = new HedwigClient(getClientConfiguration());
        publisher = client.getPublisher();
        subscriber = client.getSubscriber();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        client.stop();
        if (mode == Mode.PROXY) {
            proxy.shutdown();
        }
        super.tearDown();

    }

    // Helper function to generate Messages
    protected Message getMsg(int msgNum) {
        return Message.newBuilder().setBody(ByteString.copyFromUtf8("Message" + msgNum)).build();
    }

    // Helper function to generate Topics
    protected ByteString getTopic(int topicNum) {
        return ByteString.copyFromUtf8("Topic" + topicNum);
    }

    protected void startDelivery(ByteString topic, ByteString subscriberId, MessageHandler handler) throws Exception {
        startDelivery(subscriber, topic, subscriberId, handler);
    }

    protected void startDelivery(Subscriber subscriber, ByteString topic, ByteString subscriberId,
            MessageHandler handler) throws Exception {
        subscriber.startDelivery(topic, subscriberId, handler);
        if (mode == Mode.PROXY) {
            WriteRecordingChannel channel = new WriteRecordingChannel();
            PubSubRequest request = PubSubRequest.newBuilder().setProtocolVersion(ProtocolVersion.VERSION_ONE)
                    .setTopic(topic).setTxnId(0).setType(OperationType.START_DELIVERY).setStartDeliveryRequest(
                            StartDeliveryRequest.newBuilder().setSubscriberId(subscriberId)).build();
            proxy.getStartDeliveryHandler().handleRequest(request, channel);
            assertEquals(StatusCode.SUCCESS, ((PubSubResponse) channel.getMessagesWritten().get(0)).getStatusCode());
        }
    }

    protected void publishFirstBatch(int batchSize, boolean messagesToBeConsumed) throws Exception {
        if (logger.isDebugEnabled())
            logger.debug("Publishing first batch of messages.");
        for (int i = 0; i < batchSize; i++) {
            publisher.asyncPublish(getTopic(i), getMsg(i), new TestCallback(queue), null);
            assertTrue(queue.take());
            if (messagesToBeConsumed)
                assertTrue(consumeQueue.take());
        }
    }

    protected void publishSecondBatch(int batchSize, boolean messagesToBeConsumed) throws Exception {
        if (logger.isDebugEnabled())
            logger.debug("Publishing second batch of messages.");
        for (int i = 0; i < batchSize; i++) {
            publisher.asyncPublish(getTopic(i), getMsg(i + batchSize), new TestCallback(queue), null);
            assertTrue(queue.take());
            if (messagesToBeConsumed)
                assertTrue(consumeQueue.take());
        }
    }

    protected void subscribeToTopics(int batchSize) throws Exception {
        if (logger.isDebugEnabled())
            logger.debug("Subscribing to topics and starting delivery.");
        for (int i = 0; i < batchSize; i++) {
            subscriber.asyncSubscribe(getTopic(i), localSubscriberId, CreateOrAttach.CREATE_OR_ATTACH,
                    new TestCallback(queue), null);
            assertTrue(queue.take());
        }

        // Start delivery for the subscriber
        for (int i = 0; i < batchSize; i++) {
            startDelivery(getTopic(i), localSubscriberId, new TestMessageHandler(consumeQueue));
        }
    }

    protected void shutDownLastServer() {
        if (logger.isDebugEnabled())
            logger.debug("Shutting down the last server in the Hedwig hub cluster.");
        serversList.get(serversList.size() - 1).shutdown();
        // Due to a possible race condition, after we've shutdown the server,
        // the client could still be caching the channel connection to that
        // server. It is possible for a publish request to go to the shutdown
        // server using the closed/shutdown channel before the channel
        // disconnect logic kicks in. What could happen is that the publish
        // is done successfully on the channel but the server on the other end
        // can't/won't read it. This publish request will time out and the
        // Junit test will fail. Since that particular scenario is not what is
        // tested here, use a workaround of sleeping in this thread (so the
        // channel disconnect logic can complete) before we publish again.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            logger.error("Thread was interrupted while sleeping after shutting down last server!", e);
        }
    }

    // This tests out the manual sending of consume messages to the server
    // instead of relying on the automatic sending by the client lib for it.
    @Test
    public void testManualConsumeClient() throws Exception {
        HedwigClient myClient = new HedwigClient(new TestClientConfiguration() {
            @Override
            public boolean isAutoSendConsumeMessageEnabled() {
                return false;
            }

        });
        HedwigSubscriber mySubscriber = myClient.getSubscriber();
        HedwigPublisher myPublisher = myClient.getPublisher();
        ByteString myTopic = getTopic(0);
        // Subscribe to a topic and start delivery on it
        mySubscriber.asyncSubscribe(myTopic, localSubscriberId, CreateOrAttach.CREATE_OR_ATTACH,
                new TestCallback(queue), null);
        assertTrue(queue.take());
        startDelivery(mySubscriber, myTopic, localSubscriberId, new TestMessageHandler(consumeQueue));
        // Publish some messages
        int batchSize = 10;
        for (int i = 0; i < batchSize; i++) {
            myPublisher.asyncPublish(myTopic, getMsg(i), new TestCallback(queue), null);
            assertTrue(queue.take());
            assertTrue(consumeQueue.take());
        }
        // Now manually send a consume message for each message received
        for (int i = 0; i < batchSize; i++) {
            boolean success = true;
            try {
                mySubscriber.consume(myTopic, localSubscriberId, MessageSeqId.newBuilder().setLocalComponent(i + 1)
                        .build());
            } catch (ClientNotSubscribedException e) {
                success = false;
            }
            assertTrue(success);
        }
        // Since the consume call eventually does an async write to the Netty
        // channel, the writing of the consume requests may not have completed
        // yet before we stop the client. Sleep a little before we stop the
        // client just so error messages are not logged.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            logger.error("Thread was interrupted while waiting to stop client for manual consume test!!", e);
        }
        myClient.stop();
    }

    @Test
    public void testAttachToSubscriptionSuccess() throws Exception {
        ByteString topic = getTopic(0);
        subscriber.asyncSubscribe(topic, localSubscriberId, CreateOrAttach.CREATE_OR_ATTACH, new TestCallback(queue),
                null);
        assertTrue(queue.take());
        // Close the subscription asynchronously
        subscriber.asyncCloseSubscription(topic, localSubscriberId, new TestCallback(queue), null);
        assertTrue(queue.take());
        // Now try to attach to the subscription
        subscriber.asyncSubscribe(topic, localSubscriberId, CreateOrAttach.ATTACH, new TestCallback(queue), null);
        assertTrue(queue.take());
        // Start delivery and publish some messages. Make sure they are consumed
        // correctly.
        startDelivery(topic, localSubscriberId, new TestMessageHandler(consumeQueue));
        int batchSize = 5;
        for (int i = 0; i < batchSize; i++) {
            publisher.asyncPublish(topic, getMsg(i), new TestCallback(queue), null);
            assertTrue(queue.take());
            assertTrue(consumeQueue.take());
        }
    }

    @Test
    public void testServerRedirect() throws Exception {
        int batchSize = 10;
        publishFirstBatch(batchSize, false);
    }

    @Test
    public void testSubscribeAndConsume() throws Exception {
        int batchSize = 10;
        subscribeToTopics(batchSize);
        publishFirstBatch(batchSize, true);
    }

    @Test
    public void testServerFailoverPublishOnly() throws Exception {
        int batchSize = 10;
        publishFirstBatch(batchSize, false);
        shutDownLastServer();
        publishSecondBatch(batchSize, false);
    }

    @Test
    public void testServerFailover() throws Exception {
        int batchSize = 10;
        subscribeToTopics(batchSize);
        publishFirstBatch(batchSize, true);
        shutDownLastServer();
        publishSecondBatch(batchSize, true);
    }

    @Test
    public void testUnsubscribe() throws Exception {
        ByteString topic = getTopic(0);
        subscriber.asyncSubscribe(topic, localSubscriberId, CreateOrAttach.CREATE_OR_ATTACH, new TestCallback(queue),
                null);
        assertTrue(queue.take());
        startDelivery(topic, localSubscriberId, new TestMessageHandler(consumeQueue));
        publisher.asyncPublish(topic, getMsg(0), new TestCallback(queue), null);
        assertTrue(queue.take());
        assertTrue(consumeQueue.take());
        // Send an Unsubscribe request
        subscriber.asyncUnsubscribe(topic, localSubscriberId, new TestCallback(queue), null);
        assertTrue(queue.take());
        // Now publish a message and make sure it is not consumed by the client
        publisher.asyncPublish(topic, getMsg(1), new TestCallback(queue), null);
        assertTrue(queue.take());
        // Wait a little bit just in case the message handler is still active,
        // consuming the message, and then putting a true value in the
        // consumeQueue.
        Thread.sleep(1000);
        // Put a False value on the consumeQueue so we can verify that it
        // is not blocked by a message consume action which already put a True
        // value into the queue.
        new Thread(new Runnable() {
            @Override
            public void run() {
                ConcurrencyUtils.put(consumeQueue, false);
            }
        }).start();
        assertFalse(consumeQueue.take());
    }

    @Test
    public void testSyncUnsubscribeWithoutSubscription() throws Exception {
        boolean unsubscribeSuccess = false;
        try {
            subscriber.unsubscribe(getTopic(0), localSubscriberId);
        } catch (ClientNotSubscribedException e) {
            unsubscribeSuccess = true;
        } catch (Exception ex) {
            unsubscribeSuccess = false;
        }
        assertTrue(unsubscribeSuccess);
    }

    @Test
    public void testAsyncUnsubscribeWithoutSubscription() throws Exception {
        subscriber.asyncUnsubscribe(getTopic(0), localSubscriberId, new TestCallback(queue), null);
        assertFalse(queue.take());
    }

    @Test
    public void testCloseSubscription() throws Exception {
        ByteString topic = getTopic(0);
        subscriber.asyncSubscribe(topic, localSubscriberId, CreateOrAttach.CREATE_OR_ATTACH, new TestCallback(queue),
                null);
        assertTrue(queue.take());
        startDelivery(topic, localSubscriberId, new TestMessageHandler(consumeQueue));
        publisher.asyncPublish(topic, getMsg(0), new TestCallback(queue), null);
        assertTrue(queue.take());
        assertTrue(consumeQueue.take());
        // Close the subscription asynchronously
        subscriber.asyncCloseSubscription(topic, localSubscriberId, new TestCallback(queue), null);
        assertTrue(queue.take());
        // Now publish a message and make sure it is not consumed by the client
        publisher.asyncPublish(topic, getMsg(1), new TestCallback(queue), null);
        assertTrue(queue.take());
        // Wait a little bit just in case the message handler is still active,
        // consuming the message, and then putting a true value in the
        // consumeQueue.
        Thread.sleep(1000);
        // Put a False value on the consumeQueue so we can verify that it
        // is not blocked by a message consume action which already put a True
        // value into the queue.
        new Thread(new Runnable() {
            @Override
            public void run() {
                ConcurrencyUtils.put(consumeQueue, false);
            }
        }).start();
        assertFalse(consumeQueue.take());
    }

    @Test
    public void testStopDelivery() throws Exception {
        ByteString topic = getTopic(0);
        subscriber.asyncSubscribe(topic, localSubscriberId, CreateOrAttach.CREATE_OR_ATTACH, new TestCallback(queue),
                null);
        assertTrue(queue.take());
        startDelivery(topic, localSubscriberId, new TestMessageHandler(consumeQueue));
        publisher.asyncPublish(topic, getMsg(0), new TestCallback(queue), null);
        assertTrue(queue.take());
        assertTrue(consumeQueue.take());
        // Stop the delivery for this subscription
        subscriber.stopDelivery(topic, localSubscriberId);
        // Publish some more messages so they are queued up to be delivered to
        // the client
        int batchSize = 10;
        for (int i = 0; i < batchSize; i++) {
            publisher.asyncPublish(topic, getMsg(i + 1), new TestCallback(queue), null);
            assertTrue(queue.take());
        }
        // Wait a little bit just in case the message handler is still active,
        // consuming the message, and then putting a true value in the
        // consumeQueue.
        Thread.sleep(1000);
        // Put a False value on the consumeQueue so we can verify that it
        // is not blocked by a message consume action which already put a True
        // value into the queue.
        new Thread(new Runnable() {
            @Override
            public void run() {
                ConcurrencyUtils.put(consumeQueue, false);
            }
        }).start();
        assertFalse(consumeQueue.take());
        // Now start delivery again and verify that the queued up messages are
        // consumed
        startDelivery(topic, localSubscriberId, new TestMessageHandler(consumeQueue));
        for (int i = 0; i < batchSize; i++) {
            assertTrue(consumeQueue.take());
        }
    }

    @Test
    public void testConsumedMessagesInOrder() throws Exception {
        ByteString topic = getTopic(0);
        subscriber.asyncSubscribe(topic, localSubscriberId, CreateOrAttach.CREATE_OR_ATTACH, new TestCallback(queue),
                null);
        assertTrue(queue.take());
        startDelivery(topic, localSubscriberId, new TestMessageHandler(consumeQueue));
        // Now publish some messages and verify that they are delivered in order
        // to the subscriber
        int batchSize = 100;
        for (int i = 0; i < batchSize; i++) {
            publisher.asyncPublish(topic, getMsg(i), new TestCallback(queue), null);
        }
        // We've sent out all of the publish messages asynchronously,
        // now verify that they are consumed in the correct order.
        for (int i = 0; i < batchSize; i++) {
            assertTrue(queue.take());
            assertTrue(consumeQueue.take());
        }
    }

    @Test
    public void testCreateSubscriptionFailure() throws Exception {
        ByteString topic = getTopic(0);
        subscriber.asyncSubscribe(topic, localSubscriberId, CreateOrAttach.CREATE_OR_ATTACH, new TestCallback(queue),
                null);
        assertTrue(queue.take());
        // Close the subscription asynchronously
        subscriber.asyncCloseSubscription(topic, localSubscriberId, new TestCallback(queue), null);
        assertTrue(queue.take());
        // Now try to create the subscription when it already exists
        subscriber.asyncSubscribe(topic, localSubscriberId, CreateOrAttach.CREATE, new TestCallback(queue), null);
        assertFalse(queue.take());
    }

    @Test
    public void testCreateSubscriptionSuccess() throws Exception {
        ByteString topic = getTopic(0);
        subscriber.asyncSubscribe(topic, localSubscriberId, CreateOrAttach.CREATE, new TestCallback(queue), null);
        assertTrue(queue.take());
        startDelivery(topic, localSubscriberId, new TestMessageHandler(consumeQueue));
        int batchSize = 5;
        for (int i = 0; i < batchSize; i++) {
            publisher.asyncPublish(topic, getMsg(i), new TestCallback(queue), null);
            assertTrue(queue.take());
            assertTrue(consumeQueue.take());
        }
    }

    @Test
    public void testAttachToSubscriptionFailure() throws Exception {
        ByteString topic = getTopic(0);
        subscriber.asyncSubscribe(topic, localSubscriberId, CreateOrAttach.ATTACH, new TestCallback(queue), null);
        assertFalse(queue.take());
    }

    // The following 4 tests are to make sure that the subscriberId validation
    // works when it is a local subscriber and we're expecting the subscriberId
    // to be in the "local" specific format.
    @Test
    public void testSyncSubscribeWithInvalidSubscriberId() throws Exception {
        boolean subscribeSuccess = false;
        try {
            subscriber.subscribe(getTopic(0), hubSubscriberId, CreateOrAttach.CREATE_OR_ATTACH);
        } catch (InvalidSubscriberIdException e) {
            subscribeSuccess = true;
        } catch (Exception ex) {
            subscribeSuccess = false;
        }
        assertTrue(subscribeSuccess);
    }

    @Test
    public void testAsyncSubscribeWithInvalidSubscriberId() throws Exception {
        subscriber.asyncSubscribe(getTopic(0), hubSubscriberId, CreateOrAttach.CREATE_OR_ATTACH,
                new TestCallback(queue), null);
        assertFalse(queue.take());
    }

    @Test
    public void testSyncUnsubscribeWithInvalidSubscriberId() throws Exception {
        boolean unsubscribeSuccess = false;
        try {
            subscriber.unsubscribe(getTopic(0), hubSubscriberId);
        } catch (InvalidSubscriberIdException e) {
            unsubscribeSuccess = true;
        } catch (Exception ex) {
            unsubscribeSuccess = false;
        }
        assertTrue(unsubscribeSuccess);
    }

    @Test
    public void testAsyncUnsubscribeWithInvalidSubscriberId() throws Exception {
        subscriber.asyncUnsubscribe(getTopic(0), hubSubscriberId, new TestCallback(queue), null);
        assertFalse(queue.take());
    }

    // The following 4 tests are to make sure that the subscriberId validation
    // also works when it is a hub subscriber and we're expecting the
    // subscriberId to be in the "hub" specific format.
    @Test
    public void testSyncHubSubscribeWithInvalidSubscriberId() throws Exception {
        HedwigClient hubClient = new HedwigHubClient(new ClientConfiguration());
        HedwigSubscriber hubSubscriber = hubClient.getSubscriber();
        boolean subscribeSuccess = false;
        try {
            hubSubscriber.subscribe(getTopic(0), localSubscriberId, CreateOrAttach.CREATE_OR_ATTACH);
        } catch (InvalidSubscriberIdException e) {
            subscribeSuccess = true;
        } catch (Exception ex) {
            subscribeSuccess = false;
        }
        assertTrue(subscribeSuccess);
        hubClient.stop();
    }

    @Test
    public void testAsyncHubSubscribeWithInvalidSubscriberId() throws Exception {
        HedwigClient hubClient = new HedwigHubClient(new ClientConfiguration());
        HedwigSubscriber hubSubscriber = hubClient.getSubscriber();
        hubSubscriber.asyncSubscribe(getTopic(0), localSubscriberId, CreateOrAttach.CREATE_OR_ATTACH, new TestCallback(
                queue), null);
        assertFalse(queue.take());
        hubClient.stop();
    }

    @Test
    public void testSyncHubUnsubscribeWithInvalidSubscriberId() throws Exception {
        HedwigClient hubClient = new HedwigHubClient(new ClientConfiguration());
        HedwigSubscriber hubSubscriber = hubClient.getSubscriber();
        boolean unsubscribeSuccess = false;
        try {
            hubSubscriber.unsubscribe(getTopic(0), localSubscriberId);
        } catch (InvalidSubscriberIdException e) {
            unsubscribeSuccess = true;
        } catch (Exception ex) {
            unsubscribeSuccess = false;
        }
        assertTrue(unsubscribeSuccess);
        hubClient.stop();
    }

    @Test
    public void testAsyncHubUnsubscribeWithInvalidSubscriberId() throws Exception {
        HedwigClient hubClient = new HedwigHubClient(new ClientConfiguration());
        HedwigSubscriber hubSubscriber = hubClient.getSubscriber();
        hubSubscriber.asyncUnsubscribe(getTopic(0), localSubscriberId, new TestCallback(queue), null);
        assertFalse(queue.take());
        hubClient.stop();
    }

}
