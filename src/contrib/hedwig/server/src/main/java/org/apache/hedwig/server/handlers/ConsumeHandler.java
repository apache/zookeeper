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

import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.ConsumeRequest;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.netty.UmbrellaHandler;
import org.apache.hedwig.server.subscriptions.SubscriptionManager;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.util.Callback;

public class ConsumeHandler extends BaseHandler {

    SubscriptionManager sm;
    Callback<Void> noopCallback = new NoopCallback<Void>();

    class NoopCallback<T> implements Callback<T> {
        @Override
        public void operationFailed(Object ctx, PubSubException exception) {
        }

        public void operationFinished(Object ctx, T resultOfOperation) {
        };
    }

    @Override
    public void handleRequestAtOwner(PubSubRequest request, Channel channel) {
        if (!request.hasConsumeRequest()) {
            UmbrellaHandler.sendErrorResponseToMalformedRequest(channel, request.getTxnId(),
                    "Missing consume request data");
            return;
        }

        ConsumeRequest consumeRequest = request.getConsumeRequest();

        sm.setConsumeSeqIdForSubscriber(request.getTopic(), consumeRequest.getSubscriberId(),
                consumeRequest.getMsgId(), noopCallback, null);

    }

    public ConsumeHandler(TopicManager tm, SubscriptionManager sm, ServerConfiguration cfg) {
        super(tm, cfg);
        this.sm = sm;
    }
}
