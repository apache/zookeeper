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

import org.jboss.netty.channel.Channel;
import com.google.protobuf.ByteString;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.protocol.PubSubProtocol.UnsubscribeRequest;
import org.apache.hedwig.protoextensions.PubSubResponseUtils;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.delivery.DeliveryManager;
import org.apache.hedwig.server.netty.UmbrellaHandler;
import org.apache.hedwig.server.subscriptions.SubscriptionManager;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.util.Callback;

public class UnsubscribeHandler extends BaseHandler {
    SubscriptionManager subMgr;
    DeliveryManager deliveryMgr;

    public UnsubscribeHandler(TopicManager tm, ServerConfiguration cfg, SubscriptionManager subMgr,
            DeliveryManager deliveryMgr) {
        super(tm, cfg);
        this.subMgr = subMgr;
        this.deliveryMgr = deliveryMgr;
    }

    @Override
    public void handleRequestAtOwner(final PubSubRequest request, final Channel channel) {
        if (!request.hasUnsubscribeRequest()) {
            UmbrellaHandler.sendErrorResponseToMalformedRequest(channel, request.getTxnId(),
                    "Missing unsubscribe request data");
            return;
        }

        final UnsubscribeRequest unsubRequest = request.getUnsubscribeRequest();
        final ByteString topic = request.getTopic();
        final ByteString subscriberId = unsubRequest.getSubscriberId();

        subMgr.unsubscribe(topic, subscriberId, new Callback<Void>() {
            @Override
            public void operationFailed(Object ctx, PubSubException exception) {
                channel.write(PubSubResponseUtils.getResponseForException(exception, request.getTxnId()));
            }

            @Override
            public void operationFinished(Object ctx, Void resultOfOperation) {
                deliveryMgr.stopServingSubscriber(topic, subscriberId);
                channel.write(PubSubResponseUtils.getSuccessResponse(request.getTxnId()));

            }
        }, null);

    }

}
