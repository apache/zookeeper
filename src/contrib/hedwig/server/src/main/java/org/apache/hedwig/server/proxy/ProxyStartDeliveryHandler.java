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

import org.apache.log4j.Logger;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.ClientNotSubscribedException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.ProtocolVersion;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubResponse;
import org.apache.hedwig.protocol.PubSubProtocol.StatusCode;
import org.apache.hedwig.protoextensions.PubSubResponseUtils;
import org.apache.hedwig.server.handlers.Handler;
import org.apache.hedwig.server.netty.UmbrellaHandler;
import org.apache.hedwig.util.Callback;

public class ProxyStartDeliveryHandler implements Handler {

    static final Logger logger = Logger.getLogger(ProxyStartDeliveryHandler.class);

    Subscriber subscriber;
    ChannelTracker tracker;

    public ProxyStartDeliveryHandler(Subscriber subscriber, ChannelTracker tracker) {
        this.subscriber = subscriber;
        this.tracker = tracker;
    }

    @Override
    public void handleRequest(PubSubRequest request, Channel channel) {

        if (!request.hasStartDeliveryRequest()) {
            UmbrellaHandler.sendErrorResponseToMalformedRequest(channel, request.getTxnId(),
                    "Missing start delivery request data");
            return;
        }

        final ByteString topic = request.getTopic();
        final ByteString subscriberId = request.getStartDeliveryRequest().getSubscriberId();

        synchronized (tracker) {
            // try {
            // tracker.checkChannelMatches(topic, subscriberId, channel);
            // } catch (PubSubException e) {
            // channel.write(PubSubResponseUtils.getResponseForException(e,
            // request.getTxnId()));
            // return;
            // }

            final Channel subscribedChannel = tracker.getChannel(topic, subscriberId);
            
            if (subscribedChannel == null) {
                channel.write(PubSubResponseUtils.getResponseForException(
                        new PubSubException.ClientNotSubscribedException("no subscription to start delivery on"),
                        request.getTxnId()));
                return;
            }
            
            MessageHandler handler = new MessageHandler() {
                @Override
                public void consume(ByteString topic, ByteString subscriberId, Message msg,
                        final Callback<Void> callback, final Object context) {

                    PubSubResponse response = PubSubResponse.newBuilder().setProtocolVersion(
                            ProtocolVersion.VERSION_ONE).setStatusCode(StatusCode.SUCCESS).setTxnId(0).setMessage(msg)
                            .setTopic(topic).setSubscriberId(subscriberId).build();

                    ChannelFuture future = subscribedChannel.write(response);

                    future.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            if (!future.isSuccess()) {
                                // ignoring this failure, because this will
                                // only happen due to channel disconnect.
                                // Channel disconnect will in turn stop
                                // delivery, and stop these errors
                                return;
                            }

                            // Tell the hedwig client, that it can send me
                            // more messages
                            callback.operationFinished(context, null);
                        }
                    });
                }
            };

            channel.write(PubSubResponseUtils.getSuccessResponse(request.getTxnId()));

            try {
                subscriber.startDelivery(topic, subscriberId, handler);
            } catch (ClientNotSubscribedException e) {
                // This should not happen, since we already checked the correct
                // channel and so on
                logger.fatal("Unexpected: No subscription when attempting to start delivery", e);
                throw new RuntimeException(e);
            }
            


        }

    }

}
