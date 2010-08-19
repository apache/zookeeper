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
package org.apache.hedwig.server.handlers;

import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.data.TopicSubscriber;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.ServerNotResponsibleForTopicException;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest;
import org.apache.hedwig.protoextensions.PubSubResponseUtils;
import org.apache.hedwig.protoextensions.SubscriptionStateUtils;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.delivery.ChannelEndPoint;
import org.apache.hedwig.server.delivery.DeliveryManager;
import org.apache.hedwig.server.netty.UmbrellaHandler;
import org.apache.hedwig.server.persistence.PersistenceManager;
import org.apache.hedwig.server.subscriptions.SubscriptionManager;
import org.apache.hedwig.server.subscriptions.TrueFilter;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.util.Callback;

public class SubscribeHandler extends BaseHandler implements ChannelDisconnectListener{
    static Logger logger = Logger.getLogger(SubscribeHandler.class);

    private DeliveryManager deliveryMgr;
    private PersistenceManager persistenceMgr;
    private SubscriptionManager subMgr;
    ConcurrentHashMap<TopicSubscriber, Channel> sub2Channel;
    ConcurrentHashMap<Channel, TopicSubscriber> channel2sub;

    public SubscribeHandler(TopicManager topicMgr, DeliveryManager deliveryManager, PersistenceManager persistenceMgr,
            SubscriptionManager subMgr, ServerConfiguration cfg) {
        super(topicMgr, cfg);
        this.deliveryMgr = deliveryManager;
        this.persistenceMgr = persistenceMgr;
        this.subMgr = subMgr;
        sub2Channel = new ConcurrentHashMap<TopicSubscriber, Channel>();
        channel2sub = new ConcurrentHashMap<Channel, TopicSubscriber>();
    }

    public void channelDisconnected(Channel channel) {
        // Evils of synchronized programming: there is a race between a channel
        // getting disconnected, and us adding it to the maps when a subscribe
        // succeeds
        synchronized (channel) {
            TopicSubscriber topicSub = channel2sub.remove(channel);
            if (topicSub != null) {
                sub2Channel.remove(topicSub);
            }
        }
    }

    @Override
    public void handleRequestAtOwner(final PubSubRequest request, final Channel channel) {

        if (!request.hasSubscribeRequest()) {
            UmbrellaHandler.sendErrorResponseToMalformedRequest(channel, request.getTxnId(),
                    "Missing subscribe request data");
            return;
        }

        final ByteString topic = request.getTopic();

        MessageSeqId seqId;
        try {
            seqId = persistenceMgr.getCurrentSeqIdForTopic(topic);
        } catch (ServerNotResponsibleForTopicException e) {
            channel.write(PubSubResponseUtils.getResponseForException(e, request.getTxnId())).addListener(
                    ChannelFutureListener.CLOSE);
            return;
        }

        final SubscribeRequest subRequest = request.getSubscribeRequest();
        final ByteString subscriberId = subRequest.getSubscriberId();

        MessageSeqId lastSeqIdPublished = MessageSeqId.newBuilder(seqId).setLocalComponent(seqId.getLocalComponent()).build();

        subMgr.serveSubscribeRequest(topic, subRequest, lastSeqIdPublished, new Callback<MessageSeqId>() {

            @Override
            public void operationFailed(Object ctx, PubSubException exception) {
                channel.write(PubSubResponseUtils.getResponseForException(exception, request.getTxnId())).addListener(
                        ChannelFutureListener.CLOSE);
            }

            @Override
            public void operationFinished(Object ctx, MessageSeqId resultOfOperation) {

                TopicSubscriber topicSub = new TopicSubscriber(topic, subscriberId);

                // race with channel getting disconnected while we are adding it
                // to the 2 maps
                synchronized (channel) {
                    if (!channel.isConnected()) {
                        // channel got disconnected while we were processing the
                        // subscribe request,
                        // nothing much we can do in this case
                        return;
                    }

                    if (null != sub2Channel.putIfAbsent(topicSub, channel)) {
                        // there was another channel mapped to this sub
                        PubSubException pse = new PubSubException.TopicBusyException(
                                "subscription for this topic, subscriberId is already being served on a different channel");
                        channel.write(PubSubResponseUtils.getResponseForException(pse, request.getTxnId()))
                                .addListener(ChannelFutureListener.CLOSE);
                        return;
                    } else {
                        // channel2sub is just a cache, so we can add to it
                        // without synchronization
                        channel2sub.put(channel, topicSub);
                    }
                }
                // First write success and then tell the delivery manager,
                // otherwise the first message might go out before the response
                // to the subscribe
                channel.write(PubSubResponseUtils.getSuccessResponse(request.getTxnId()));

                // want to start 1 ahead of the consume ptr
                MessageSeqId seqIdToStartFrom = MessageSeqId.newBuilder(resultOfOperation).setLocalComponent(
                        resultOfOperation.getLocalComponent() + 1).build();
                deliveryMgr.startServingSubscription(topic, subscriberId, seqIdToStartFrom,
                        new ChannelEndPoint(channel), TrueFilter.instance(), SubscriptionStateUtils
                                .isHubSubscriber(subRequest.getSubscriberId()));
            }
        }, null);

    }

}
