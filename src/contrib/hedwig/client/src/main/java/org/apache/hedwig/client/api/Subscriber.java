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
package org.apache.hedwig.client.api;

import java.util.List;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.exceptions.InvalidSubscriberIdException;
import org.apache.hedwig.exceptions.PubSubException.ClientAlreadySubscribedException;
import org.apache.hedwig.exceptions.PubSubException.ClientNotSubscribedException;
import org.apache.hedwig.exceptions.PubSubException.CouldNotConnectException;
import org.apache.hedwig.exceptions.PubSubException.ServiceDownException;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.util.Callback;

/**
 * Interface to define the client Subscriber API.
 * 
 */
public interface Subscriber {

    /**
     * Subscribe to the given topic for the inputted subscriberId.
     * 
     * @param topic
     *            Topic name of the subscription
     * @param subscriberId
     *            ID of the subscriber
     * @param mode
     *            Whether to prohibit, tolerate, or require an existing
     *            subscription.
     * @throws CouldNotConnectException
     *             If we are not able to connect to the server host
     * @throws ClientAlreadySubscribedException
     *             If client is already subscribed to the topic
     * @throws ServiceDownException
     *             If unable to subscribe to topic
     * @throws InvalidSubscriberIdException
     *             If the subscriberId is not valid. We may want to set aside
     *             certain formats of subscriberId's for different purposes.
     *             e.g. local vs. hub subscriber
     */
    public void subscribe(ByteString topic, ByteString subscriberId, CreateOrAttach mode)
            throws CouldNotConnectException, ClientAlreadySubscribedException, ServiceDownException,
            InvalidSubscriberIdException;

    /**
     * Subscribe to the given topic asynchronously for the inputted subscriberId
     * disregarding if the topic has been created yet or not.
     * 
     * @param topic
     *            Topic name of the subscription
     * @param subscriberId
     *            ID of the subscriber
     * @param mode
     *            Whether to prohibit, tolerate, or require an existing
     *            subscription.
     * @param callback
     *            Callback to invoke when the subscribe request to the server
     *            has actually gone through. This will have to deal with error
     *            conditions on the async subscribe request.
     * @param context
     *            Calling context that the Callback needs since this is done
     *            asynchronously.
     */
    public void asyncSubscribe(ByteString topic, ByteString subscriberId, CreateOrAttach mode, Callback<Void> callback,
            Object context);

    /**
     * Unsubscribe from a topic that the subscriberId user has previously
     * subscribed to.
     * 
     * @param topic
     *            Topic name of the subscription
     * @param subscriberId
     *            ID of the subscriber
     * @throws CouldNotConnectException
     *             If we are not able to connect to the server host
     * @throws ClientNotSubscribedException
     *             If the client is not currently subscribed to the topic
     * @throws ServiceDownException
     *             If the server was down and unable to complete the request
     * @throws InvalidSubscriberIdException
     *             If the subscriberId is not valid. We may want to set aside
     *             certain formats of subscriberId's for different purposes.
     *             e.g. local vs. hub subscriber
     */
    public void unsubscribe(ByteString topic, ByteString subscriberId) throws CouldNotConnectException,
            ClientNotSubscribedException, ServiceDownException, InvalidSubscriberIdException;

    /**
     * Unsubscribe from a topic asynchronously that the subscriberId user has
     * previously subscribed to.
     * 
     * @param topic
     *            Topic name of the subscription
     * @param subscriberId
     *            ID of the subscriber
     * @param callback
     *            Callback to invoke when the unsubscribe request to the server
     *            has actually gone through. This will have to deal with error
     *            conditions on the async unsubscribe request.
     * @param context
     *            Calling context that the Callback needs since this is done
     *            asynchronously.
     */
    public void asyncUnsubscribe(ByteString topic, ByteString subscriberId, Callback<Void> callback, Object context);

    /**
     * Manually send a consume message to the server for the given inputs.
     * 
     * @param topic
     *            Topic name of the subscription
     * @param subscriberId
     *            ID of the subscriber
     * @param messageSeqId
     *            Message Sequence ID for the latest message that the client app
     *            has successfully consumed. All messages up to that point will
     *            also be considered as consumed.            
     * @throws ClientNotSubscribedException
     *             If the client is not currently subscribed to the topic based
     *             on the client's local state.
     */
    public void consume(ByteString topic, ByteString subscriberId, MessageSeqId messageSeqId)
            throws ClientNotSubscribedException;

    /**
     * Checks if the subscriberId client is currently subscribed to the given
     * topic.
     * 
     * @param topic
     *            Topic name of the subscription.
     * @param subscriberId
     *            ID of the subscriber
     * @throws CouldNotConnectException
     *             If we are not able to connect to the server host
     * @throws ServiceDownException
     *             If there is an error checking the server if the client has a
     *             subscription
     * @return Boolean indicating if the client has a subscription or not.
     */
    public boolean hasSubscription(ByteString topic, ByteString subscriberId) throws CouldNotConnectException,
            ServiceDownException;

    /**
     * Fills the input List with the subscriptions this subscriberId client is
     * subscribed to.
     * 
     * @param subscriberId
     *            ID of the subscriber
     * @return List filled with subscription name (topic) strings.
     * @throws CouldNotConnectException
     *             If we are not able to connect to the server host
     * @throws ServiceDownException
     *             If there is an error retrieving the list of topics
     */
    public List<ByteString> getSubscriptionList(ByteString subscriberId) throws CouldNotConnectException,
            ServiceDownException;

    /**
     * Begin delivery of messages from the server to us for this topic and
     * subscriberId.
     * 
     * @param topic
     *            Topic name of the subscription
     * @param subscriberId
     *            ID of the subscriber
     * @param messageHandler
     *            Message Handler that will consume the subscribed messages
     * @throws ClientNotSubscribedException
     *             If the client is not currently subscribed to the topic
     */
    public void startDelivery(ByteString topic, ByteString subscriberId, MessageHandler messageHandler)
            throws ClientNotSubscribedException;

    /**
     * Stop delivery of messages for this topic and subscriberId.
     * 
     * @param topic
     *            Topic name of the subscription
     * @param subscriberId
     *            ID of the subscriber
     * @throws ClientNotSubscribedException
     *             If the client is not currently subscribed to the topic
     */
    public void stopDelivery(ByteString topic, ByteString subscriberId) throws ClientNotSubscribedException;

    /**
     * Closes all of the client side cached data for this subscription without
     * actually sending an unsubscribe request to the server. This will close
     * the subscribe channel synchronously (if it exists) for the topic.
     * 
     * @param topic
     *            Topic name of the subscription
     * @param subscriberId
     *            ID of the subscriber
     * @throws ServiceDownException
     *             If the subscribe channel was not able to be closed
     *             successfully
     */
    public void closeSubscription(ByteString topic, ByteString subscriberId) throws ServiceDownException;

    /**
     * Closes all of the client side cached data for this subscription without
     * actually sending an unsubscribe request to the server. This will close
     * the subscribe channel asynchronously (if it exists) for the topic.
     * 
     * @param topic
     *            Topic name of the subscription
     * @param subscriberId
     *            ID of the subscriber
     * @param callback
     *            Callback to invoke when the subscribe channel has been closed.
     * @param context
     *            Calling context that the Callback needs since this is done
     *            asynchronously.
     */
    public void asyncCloseSubscription(ByteString topic, ByteString subscriberId, Callback<Void> callback,
            Object context);

}