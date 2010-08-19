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

import org.apache.hedwig.client.api.Publisher;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.protocol.PubSubProtocol.PublishRequest;
import org.apache.hedwig.protoextensions.PubSubResponseUtils;
import org.apache.hedwig.server.handlers.Handler;
import org.apache.hedwig.server.netty.UmbrellaHandler;
import org.apache.hedwig.util.Callback;

public class ProxyPublishHander implements Handler {
    Publisher publisher;

    public ProxyPublishHander(Publisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void handleRequest(final PubSubRequest request, final Channel channel) {
        if (!request.hasPublishRequest()) {
            UmbrellaHandler.sendErrorResponseToMalformedRequest(channel, request.getTxnId(),
                    "Missing publish request data");
            return;
        }

        final PublishRequest publishRequest = request.getPublishRequest();

        publisher.asyncPublish(request.getTopic(), publishRequest.getMsg(), new Callback<Void>() {
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
