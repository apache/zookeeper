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

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.ClientNotSubscribedException;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.server.handlers.Handler;
import org.apache.hedwig.server.netty.UmbrellaHandler;

public class ProxyStopDeliveryHandler implements Handler {

    static final Logger logger = Logger.getLogger(ProxyStopDeliveryHandler.class);

    Subscriber subscriber;
    ChannelTracker tracker;

    public ProxyStopDeliveryHandler(Subscriber subscriber, ChannelTracker tracker) {
        this.subscriber = subscriber;
        this.tracker = tracker;
    }

    @Override
    public void handleRequest(PubSubRequest request, Channel channel) {
        if (!request.hasStopDeliveryRequest()) {
            UmbrellaHandler.sendErrorResponseToMalformedRequest(channel, request.getTxnId(),
                    "Missing stop delivery request data");
            return;
        }

        final ByteString topic = request.getTopic();
        final ByteString subscriberId = request.getStartDeliveryRequest().getSubscriberId();

        synchronized (tracker) {
            try {
                tracker.checkChannelMatches(topic, subscriberId, channel);
            } catch (PubSubException e) {
                // intentionally ignore this error, since stop delivery doesn't
                // send back a response
                return;
            }

            try {
                subscriber.stopDelivery(topic, subscriberId);
            } catch (ClientNotSubscribedException e) {
                // This should not happen, since we already checked the correct
                // channel and so on
                logger.warn("Unexpected: No subscription when attempting to stop delivery", e);
            }
        }

    }
}
