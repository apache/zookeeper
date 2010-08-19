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
package org.apache.hedwig.server.proxy;

import org.jboss.netty.channel.Channel;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.protoextensions.PubSubResponseUtils;
import org.apache.hedwig.server.handlers.Handler;
import org.apache.hedwig.server.netty.UmbrellaHandler;
import org.apache.hedwig.util.Callback;

public class ProxyUnsubscribeHandler implements Handler {

    Subscriber subscriber;
    ChannelTracker tracker;

    public ProxyUnsubscribeHandler(Subscriber subscriber, ChannelTracker tracker) {
        this.subscriber = subscriber;
        this.tracker = tracker;
    }

    @Override
    public void handleRequest(final PubSubRequest request, final Channel channel) {
        if (!request.hasUnsubscribeRequest()) {
            UmbrellaHandler.sendErrorResponseToMalformedRequest(channel, request.getTxnId(),
                    "Missing unsubscribe request data");
            return;
        }

        ByteString topic = request.getTopic();
        ByteString subscriberId = request.getUnsubscribeRequest().getSubscriberId();

        synchronized (tracker) {

            // Even if unsubscribe fails, the hedwig client closes the channel
            // on which the subscription is being served. Hence better to tell
            // the tracker beforehand that this subscription is no longer served
            tracker.aboutToUnsubscribe(topic, subscriberId);

            subscriber.asyncUnsubscribe(topic, subscriberId, new Callback<Void>() {
                @Override
                public void operationFailed(Object ctx, PubSubException exception) {
                    channel.write(PubSubResponseUtils.getResponseForException(exception, request.getTxnId()));
                }

                @Override
                public void operationFinished(Object ctx, Void resultOfOperation) {
                    channel.write(PubSubResponseUtils.getSuccessResponse(request.getTxnId()));
                }
            }, null);
        }

    }

}
