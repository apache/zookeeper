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
package org.apache.hedwig.server.regions;

import java.util.ArrayList;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.zookeeper.ZooKeeper;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.client.netty.HedwigSubscriber;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.RegionSpecificSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.protoextensions.SubscriptionStateUtils;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.common.TopicOpQueuer;
import org.apache.hedwig.server.persistence.PersistRequest;
import org.apache.hedwig.server.persistence.PersistenceManager;
import org.apache.hedwig.server.subscriptions.SubscriptionEventListener;
import org.apache.hedwig.util.Callback;
import org.apache.hedwig.util.CallbackUtils;
import org.apache.hedwig.util.HedwigSocketAddress;

public class RegionManager implements SubscriptionEventListener {

    protected static final Logger LOGGER = Logger.getLogger(RegionManager.class);

    private final ByteString mySubId;
    private final PersistenceManager pm;
    private final ArrayList<HedwigHubClient> clients = new ArrayList<HedwigHubClient>();
    private final TopicOpQueuer queue;

    public RegionManager(final PersistenceManager pm, final ServerConfiguration cfg, final ZooKeeper zk,
            ScheduledExecutorService scheduler, HedwigHubClientFactory hubClientFactory) {
        this.pm = pm;
        mySubId = ByteString.copyFromUtf8(SubscriptionStateUtils.HUB_SUBSCRIBER_PREFIX + cfg.getMyRegion());
        queue = new TopicOpQueuer(scheduler);
        for (final String hub : cfg.getRegions()) {
            clients.add(hubClientFactory.create(new HedwigSocketAddress(hub)));
        }
    }

    @Override
    public void onFirstLocalSubscribe(final ByteString topic, final boolean synchronous, final Callback<Void> cb) {
        // Whenever we acquire a topic due to a (local) subscribe, subscribe on
        // it to all the other regions (currently using simple all-to-all
        // topology).
        queue.pushAndMaybeRun(topic, queue.new AsynchronousOp<Void>(topic, cb, null) {
            @Override
            public void run() {
                Callback<Void> postCb = synchronous ? cb : CallbackUtils.logger(LOGGER, Level.DEBUG, Level.ERROR,
                        "all cross-region subscriptions succeeded", "at least one cross-region subscription failed");
                final Callback<Void> mcb = CallbackUtils.multiCallback(clients.size(), postCb, ctx);
                for (final HedwigHubClient client : clients) {
                    final HedwigSubscriber sub = client.getSubscriber();
                    sub.asyncSubscribe(topic, mySubId, CreateOrAttach.CREATE_OR_ATTACH, new Callback<Void>() {
                        @Override
                        public void operationFinished(Object ctx, Void resultOfOperation) {
                            if (LOGGER.isDebugEnabled())
                                LOGGER.debug("cross-region subscription done for topic " + topic.toStringUtf8());
                            try {
                                sub.startDelivery(topic, mySubId, new MessageHandler() {
                                    @Override
                                    public void consume(final ByteString topic, ByteString subscriberId, Message msg,
                                            final Callback<Void> callback, final Object context) {
                                        // When messages are first published
                                        // locally, the PublishHandler sets the
                                        // source region in the Message.
                                        if (msg.hasSrcRegion()) {
                                            Message.newBuilder(msg).setMsgId(
                                                    MessageSeqId.newBuilder(msg.getMsgId()).addRemoteComponents(
                                                            RegionSpecificSeqId.newBuilder().setRegion(
                                                                    msg.getSrcRegion()).setSeqId(
                                                                    msg.getMsgId().getLocalComponent())));
                                        }
                                        pm.persistMessage(new PersistRequest(topic, msg, new Callback<Long>() {
                                            @Override
                                            public void operationFinished(Object ctx, Long resultOfOperation) {
                                                if (LOGGER.isDebugEnabled())
                                                    LOGGER.debug("cross-region recv-fwd succeeded for topic "
                                                            + topic.toStringUtf8());
                                                callback.operationFinished(context, null);
                                            }

                                            @Override
                                            public void operationFailed(Object ctx, PubSubException exception) {
                                                if (LOGGER.isDebugEnabled())
                                                    LOGGER.error("cross-region recv-fwd failed for topic "
                                                            + topic.toStringUtf8(), exception);
                                                callback.operationFailed(context, exception);
                                            }
                                        }, null));
                                    }
                                });
                                if (LOGGER.isDebugEnabled())
                                    LOGGER.debug("cross-region start-delivery succeeded for topic "
                                            + topic.toStringUtf8());
                                mcb.operationFinished(ctx, null);
                            } catch (PubSubException ex) {
                                if (LOGGER.isDebugEnabled())
                                    LOGGER.error(
                                            "cross-region start-delivery failed for topic " + topic.toStringUtf8(), ex);
                                mcb.operationFailed(ctx, ex);
                            }
                        }

                        @Override
                        public void operationFailed(Object ctx, PubSubException exception) {
                            if (LOGGER.isDebugEnabled())
                                LOGGER.error("cross-region subscribe failed for topic " + topic.toStringUtf8(),
                                        exception);
                            mcb.operationFailed(ctx, exception);
                        }
                    }, null);
                }
                if (!synchronous)
                    cb.operationFinished(null, null);
            }
        });

    }

    @Override
    public void onLastLocalUnsubscribe(final ByteString topic) {
        // TODO may want to ease up on the eager unsubscribe; this is dropping
        // cross-region subscriptions ASAP
        queue.pushAndMaybeRun(topic, queue.new AsynchronousOp<Void>(topic, new Callback<Void>() {

            @Override
            public void operationFinished(Object ctx, Void result) {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("cross-region unsubscribes succeeded for topic " + topic.toStringUtf8());
            }

            @Override
            public void operationFailed(Object ctx, PubSubException exception) {
                if (LOGGER.isDebugEnabled())
                    LOGGER.error("cross-region unsubscribes failed for topic " + topic.toStringUtf8(), exception);
            }

        }, null) {
            @Override
            public void run() {
                Callback<Void> mcb = CallbackUtils.multiCallback(clients.size(), cb, ctx);
                for (final HedwigHubClient client : clients) {
                    client.getSubscriber().asyncUnsubscribe(topic, mySubId, mcb, null);
                }
            }
        });
    }

    // Method to shutdown and stop all of the cross-region Hedwig clients.
    public void stop() {
        for (HedwigHubClient client : clients) {
            client.stop();
        }
    }

}
