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
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.protoextensions.PubSubResponseUtils;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.netty.UmbrellaHandler;
import org.apache.hedwig.server.persistence.PersistRequest;
import org.apache.hedwig.server.persistence.PersistenceManager;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.util.Callback;

public class PublishHandler extends BaseHandler {

    private PersistenceManager persistenceMgr;

    public PublishHandler(TopicManager topicMgr, PersistenceManager persistenceMgr, ServerConfiguration cfg) {
        super(topicMgr, cfg);
        this.persistenceMgr = persistenceMgr;
    }

    @Override
    public void handleRequestAtOwner(final PubSubRequest request, final Channel channel) {
        if (!request.hasPublishRequest()) {
            UmbrellaHandler.sendErrorResponseToMalformedRequest(channel, request.getTxnId(),
                    "Missing publish request data");
            return;
        }

        Message msgToSerialize = Message.newBuilder(request.getPublishRequest().getMsg()).setSrcRegion(
                cfg.getMyRegionByteString()).build();

        PersistRequest persistRequest = new PersistRequest(request.getTopic(), msgToSerialize,
                new Callback<Long>() {
                    @Override
                    public void operationFailed(Object ctx, PubSubException exception) {
                        channel.write(PubSubResponseUtils.getResponseForException(exception, request.getTxnId()));
                    }

                    @Override
                    public void operationFinished(Object ctx, Long resultOfOperation) {
                        channel.write(PubSubResponseUtils.getSuccessResponse(request.getTxnId()));
                    }
                }, null);

        persistenceMgr.persistMessage(persistRequest);
    }

}
