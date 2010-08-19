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

import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.exceptions.PubSubException.ClientNotSubscribedException;
import org.apache.hedwig.protocol.PubSubProtocol.ConsumeRequest;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.server.handlers.Handler;
import org.apache.hedwig.server.netty.UmbrellaHandler;

public class ProxyConsumeHandler implements Handler {

    static final Logger logger = Logger.getLogger(ProxyConsumeHandler.class);

    Subscriber subscriber;

    public ProxyConsumeHandler(Subscriber subscriber) {
        this.subscriber = subscriber;
    }

    @Override
    public void handleRequest(PubSubRequest request, Channel channel) {
        if (!request.hasConsumeRequest()) {
            UmbrellaHandler.sendErrorResponseToMalformedRequest(channel, request.getTxnId(),
                    "Missing consume request data");
            return;
        }

        ConsumeRequest consumeRequest = request.getConsumeRequest();
        try {
            subscriber.consume(request.getTopic(), consumeRequest.getSubscriberId(), consumeRequest.getMsgId());
        } catch (ClientNotSubscribedException e) {
            // ignore
            logger.warn("Unexpected consume request", e);
        }

    }
}
