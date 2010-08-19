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
package org.apache.hedwig.client.handlers;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.Channel;

import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.client.data.MessageConsumeData;
import org.apache.hedwig.client.data.PubSubData;
import org.apache.hedwig.client.data.TopicSubscriber;
import org.apache.hedwig.client.netty.HedwigClient;
import org.apache.hedwig.client.netty.ResponseHandler;
import org.apache.hedwig.exceptions.PubSubException.ClientAlreadySubscribedException;
import org.apache.hedwig.exceptions.PubSubException.ServiceDownException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubResponse;
import org.apache.hedwig.protocol.PubSubProtocol.StatusCode;

public class SubscribeResponseHandler {

    private static Logger logger = Logger.getLogger(SubscribeResponseHandler.class);

    private final ResponseHandler responseHandler;

    // Member variables used when this ResponseHandler is for a Subscribe
    // channel. We need to be able to consume messages sent back to us from
    // the server, and to also recreate the Channel connection if it ever goes
    // down. For that, we need to store the original PubSubData for the
    // subscribe request, and also the MessageHandler that was registered when
    // delivery of messages started for the subscription.
    private PubSubData origSubData;
    private Channel subscribeChannel;
    private MessageHandler messageHandler;
    // Counter for the number of consumed messages so far to buffer up before we
    // send the Consume message back to the server along with the last/largest
    // message seq ID seen so far in that batch.
    private int numConsumedMessagesInBuffer = 0;
    private MessageSeqId lastMessageSeqId;
    // Queue used for subscribes when the MessageHandler hasn't been registered
    // yet but we've already received subscription messages from the server.
    // This will be lazily created as needed.
    private Queue<Message> subscribeMsgQueue;
    // Set to store all of the outstanding subscribed messages that are pending
    // to be consumed by the client app's MessageHandler. If this ever grows too
    // big (e.g. problem at the client end for message consumption), we can
    // throttle things by temporarily setting the Subscribe Netty Channel
    // to not be readable. When the Set has shrunk sufficiently, we can turn the
    // channel back on to read new messages.
    private Set<Message> outstandingMsgSet;

    public SubscribeResponseHandler(ResponseHandler responseHandler) {
        this.responseHandler = responseHandler;
    }

    // Public getter to retrieve the original PubSubData used for the Subscribe
    // request.
    public PubSubData getOrigSubData() {
        return origSubData;
    }

    // Main method to handle Subscribe responses from the server that we sent
    // a Subscribe Request to.
    public void handleSubscribeResponse(PubSubResponse response, PubSubData pubSubData, Channel channel)
            throws Exception {
        // If this was not a successful response to the Subscribe request, we
        // won't be using the Netty Channel created so just close it.
        if (!response.getStatusCode().equals(StatusCode.SUCCESS)) {
            HedwigClient.getResponseHandlerFromChannel(channel).channelClosedExplicitly = true;
            channel.close();
        }

        if (logger.isDebugEnabled())
            logger.debug("Handling a Subscribe response: " + response + ", pubSubData: " + pubSubData + ", host: "
                    + HedwigClient.getHostFromChannel(channel));
        switch (response.getStatusCode()) {
        case SUCCESS:
            // For successful Subscribe requests, store this Channel locally
            // and set it to not be readable initially.
            // This way we won't be delivering messages for this topic
            // subscription until the client explicitly says so.
            subscribeChannel = channel;
            subscribeChannel.setReadable(false);
            // Store the original PubSubData used to create this successful
            // Subscribe request.
            origSubData = pubSubData;
            // Store the mapping for the TopicSubscriber to the Channel.
            // This is so we can control the starting and stopping of
            // message deliveries from the server on that Channel. Store
            // this only on a successful ack response from the server.
            TopicSubscriber topicSubscriber = new TopicSubscriber(pubSubData.topic, pubSubData.subscriberId);
            responseHandler.getSubscriber().setChannelForTopic(topicSubscriber, channel);
            // Lazily create the Set to keep track of outstanding Messages
            // to be consumed by the client app. At this stage, delivery for
            // that topic hasn't started yet so creation of this Set should
            // be thread safe. We'll create the Set with an initial capacity
            // equal to the configured parameter for the maximum number of
            // outstanding messages to allow. The load factor will be set to
            // 1.0f which means we'll only rehash and allocate more space if
            // we ever exceed the initial capacity. That should be okay
            // because when that happens, things are slow already and piling
            // up on the client app side to consume messages.
            outstandingMsgSet = new HashSet<Message>(
                    responseHandler.getConfiguration().getMaximumOutstandingMessages(), 1.0f);
            // Response was success so invoke the callback's operationFinished
            // method.
            pubSubData.callback.operationFinished(pubSubData.context, null);
            break;
        case CLIENT_ALREADY_SUBSCRIBED:
            // For Subscribe requests, the server says that the client is
            // already subscribed to it.
            pubSubData.callback.operationFailed(pubSubData.context, new ClientAlreadySubscribedException(
                    "Client is already subscribed for topic: " + pubSubData.topic.toStringUtf8() + ", subscriberId: "
                            + pubSubData.subscriberId.toStringUtf8()));
            break;
        case SERVICE_DOWN:
            // Response was service down failure so just invoke the callback's
            // operationFailed method.
            pubSubData.callback.operationFailed(pubSubData.context, new ServiceDownException(
                    "Server responded with a SERVICE_DOWN status"));
            break;
        case NOT_RESPONSIBLE_FOR_TOPIC:
            // Redirect response so we'll need to repost the original Subscribe
            // Request
            responseHandler.handleRedirectResponse(response, pubSubData, channel);
            break;
        default:
            // Consider all other status codes as errors, operation failed
            // cases.
            logger.error("Unexpected error response from server for PubSubResponse: " + response);
            pubSubData.callback.operationFailed(pubSubData.context, new ServiceDownException(
                    "Server responded with a status code of: " + response.getStatusCode()));
            break;
        }
    }

    // Main method to handle consuming a message for a topic that the client is
    // subscribed to.
    public void handleSubscribeMessage(PubSubResponse response) {
        if (logger.isDebugEnabled())
            logger.debug("Handling a Subscribe message in response: " + response + ", topic: "
                    + origSubData.topic.toStringUtf8() + ", subscriberId: " + origSubData.subscriberId.toStringUtf8());
        Message message = response.getMessage();
        // Consume the message asynchronously that the client is subscribed
        // to. Do this only if delivery for the subscription has started and
        // a MessageHandler has been registered for the TopicSubscriber.
        if (messageHandler != null) {
            asyncMessageConsume(message);
        } else {
            // MessageHandler has not yet been registered so queue up these
            // messages for the Topic Subscription. Make the initial lazy
            // creation of the message queue thread safe just so we don't
            // run into a race condition where two simultaneous threads process
            // a received message and both try to create a new instance of
            // the message queue. Performance overhead should be okay
            // because the delivery of the topic has not even started yet
            // so these messages are not consumed and just buffered up here.
            synchronized (this) {
                if (subscribeMsgQueue == null)
                    subscribeMsgQueue = new LinkedList<Message>();
            }
            if (logger.isDebugEnabled())
                logger
                        .debug("Message has arrived but Subscribe channel does not have a registered MessageHandler yet so queueing up the message: "
                                + message);
            subscribeMsgQueue.add(message);
        }
    }

    /**
     * Method called when a message arrives for a subscribe Channel and we want
     * to consume it asynchronously via the registered MessageHandler (should
     * not be null when called here).
     * 
     * @param message
     *            Message from Subscribe Channel we want to consume.
     */
    protected void asyncMessageConsume(Message message) {
        if (logger.isDebugEnabled())
            logger.debug("Call the client app's MessageHandler asynchronously to consume the message: " + message
                    + ", topic: " + origSubData.topic.toStringUtf8() + ", subscriberId: "
                    + origSubData.subscriberId.toStringUtf8());
        // Add this "pending to be consumed" message to the outstandingMsgSet.
        outstandingMsgSet.add(message);
        // Check if we've exceeded the max size for the outstanding message set.
        if (outstandingMsgSet.size() >= responseHandler.getConfiguration().getMaximumOutstandingMessages()
                && subscribeChannel.isReadable()) {
            // Too many outstanding messages so throttle it by setting the Netty
            // Channel to not be readable.
            if (logger.isDebugEnabled())
                logger.debug("Too many outstanding messages (" + outstandingMsgSet.size()
                        + ") so throttling the subscribe netty Channel");
            subscribeChannel.setReadable(false);
        }
        MessageConsumeData messageConsumeData = new MessageConsumeData(origSubData.topic, origSubData.subscriberId,
                message);
        messageHandler.consume(origSubData.topic, origSubData.subscriberId, message, responseHandler.getClient()
                .getConsumeCallback(), messageConsumeData);
    }

    /**
     * Method called when the client app's MessageHandler has asynchronously
     * completed consuming a subscribed message sent from the server. The
     * contract with the client app is that messages sent to the handler to be
     * consumed will have the callback response done in the same order. So if we
     * asynchronously call the MessageHandler to consume messages #1-5, that
     * should call the messageConsumed method here via the VoidCallback in the
     * same order. To make this thread safe, since multiple outstanding messages
     * could be consumed by the client app and then called back to here, make
     * this method synchronized.
     * 
     * @param message
     *            Message sent from server for topic subscription that has been
     *            consumed by the client.
     */
    protected synchronized void messageConsumed(Message message) {
        if (logger.isDebugEnabled())
            logger.debug("Message has been successfully consumed by the client app for message: " + message
                    + ", topic: " + origSubData.topic.toStringUtf8() + ", subscriberId: "
                    + origSubData.subscriberId.toStringUtf8());
        // Update the consumed messages buffer variables
        if (responseHandler.getConfiguration().isAutoSendConsumeMessageEnabled()) {
            // Update these variables only if we are auto-sending consume
            // messages to the server. Otherwise the onus is on the client app
            // to call the Subscriber consume API to let the server know which
            // messages it has successfully consumed.
            numConsumedMessagesInBuffer++;
            lastMessageSeqId = message.getMsgId();
        }
        // Remove this consumed message from the outstanding Message Set.
        outstandingMsgSet.remove(message);

        // For consume response to server, there is a config param on how many
        // messages to consume and buffer up before sending the consume request.
        // We just need to keep a count of the number of messages consumed
        // and the largest/latest msg ID seen so far in this batch. Messages
        // should be delivered in order and without gaps. Do this only if
        // auto-sending of consume messages is enabled.
        if (responseHandler.getConfiguration().isAutoSendConsumeMessageEnabled()
                && numConsumedMessagesInBuffer >= responseHandler.getConfiguration().getConsumedMessagesBufferSize()) {
            // Send the consume request and reset the consumed messages buffer
            // variables. We will use the same Channel created from the
            // subscribe request for the TopicSubscriber.
            if (logger.isDebugEnabled())
                logger
                        .debug("Consumed message buffer limit reached so send the Consume Request to the server with lastMessageSeqId: "
                                + lastMessageSeqId);
            responseHandler.getSubscriber().doConsume(origSubData, subscribeChannel, lastMessageSeqId);
            numConsumedMessagesInBuffer = 0;
            lastMessageSeqId = null;
        }

        // Check if we throttled message consumption previously when the
        // outstanding message limit was reached. For now, only turn the
        // delivery back on if there are no more outstanding messages to
        // consume. We could make this a configurable parameter if needed.
        if (!subscribeChannel.isReadable() && outstandingMsgSet.size() == 0) {
            if (logger.isDebugEnabled())
                logger
                        .debug("Message consumption has caught up so okay to turn off throttling of messages on the subscribe channel for topic: "
                                + origSubData.topic.toStringUtf8()
                                + ", subscriberId: "
                                + origSubData.subscriberId.toStringUtf8());
            subscribeChannel.setReadable(true);
        }
    }

    /**
     * Setter used for Subscribe flows when delivery for the subscription is
     * started. This is used to register the MessageHandler needed to consumer
     * the subscribed messages for the topic.
     * 
     * @param messageHandler
     *            MessageHandler to register for this ResponseHandler instance.
     */
    public void setMessageHandler(MessageHandler messageHandler) {
        if (logger.isDebugEnabled())
            logger.debug("Setting the messageHandler for topic: " + origSubData.topic.toStringUtf8()
                    + ", subscriberId: " + origSubData.subscriberId.toStringUtf8());
        this.messageHandler = messageHandler;
        // Once the MessageHandler is registered, see if we have any queued up
        // subscription messages sent to us already from the server. If so,
        // consume those first. Do this only if the MessageHandler registered is
        // not null (since that would be the HedwigSubscriber.stopDelivery
        // call).
        if (messageHandler != null && subscribeMsgQueue != null && subscribeMsgQueue.size() > 0) {
            if (logger.isDebugEnabled())
                logger.debug("Consuming " + subscribeMsgQueue.size() + " queued up messages for topic: "
                        + origSubData.topic.toStringUtf8() + ", subscriberId: "
                        + origSubData.subscriberId.toStringUtf8());
            for (Message message : subscribeMsgQueue) {
                asyncMessageConsume(message);
            }
            // Now we can remove the queued up messages since they are all
            // consumed.
            subscribeMsgQueue.clear();
        }
    }

    /**
     * Getter for the MessageHandler that is set for this subscribe channel.
     * 
     * @return The MessageHandler for consuming messages
     */
    public MessageHandler getMessageHandler() {
        return messageHandler;
    }
}
