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
package org.apache.hedwig.server.topics;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.google.protobuf.ByteString;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.common.TopicOpQueuer;
import org.apache.hedwig.util.Callback;
import org.apache.hedwig.util.CallbackUtils;
import org.apache.hedwig.util.HedwigSocketAddress;

public abstract class AbstractTopicManager implements TopicManager {
    /**
     * My name.
     */
    protected HedwigSocketAddress addr;

    /**
     * Topic change listeners.
     */
    protected ArrayList<TopicOwnershipChangeListener> listeners = new ArrayList<TopicOwnershipChangeListener>();

    /**
     * List of topics I believe I am responsible for.
     */
    protected Set<ByteString> topics = Collections.synchronizedSet(new HashSet<ByteString>());

    protected TopicOpQueuer queuer;
    protected ServerConfiguration cfg;
    protected ScheduledExecutorService scheduler;

    private static final Logger logger = Logger.getLogger(AbstractTopicManager.class);

    private class GetOwnerOp extends TopicOpQueuer.AsynchronousOp<HedwigSocketAddress> {
        public boolean shouldClaim;

        public GetOwnerOp(final ByteString topic, boolean shouldClaim, 
                final Callback<HedwigSocketAddress> cb, Object ctx) {
            queuer.super(topic, cb, ctx);
            this.shouldClaim = shouldClaim;
        }

        @Override
        public void run() {
            realGetOwner(topic, shouldClaim, cb, ctx);
        }
    }

    private class ReleaseOp extends TopicOpQueuer.AsynchronousOp<Void> {
        public ReleaseOp(ByteString topic, Callback<Void> cb, Object ctx) {
            queuer.super(topic, cb, ctx);
        }

        @Override
        public void run() {
            if (!topics.contains(topic)) {
                cb.operationFinished(ctx, null);
                return;
            }
            realReleaseTopic(topic, cb, ctx);
        }
    }

    public AbstractTopicManager(ServerConfiguration cfg, ScheduledExecutorService scheduler)
            throws UnknownHostException {
        this.cfg = cfg;
        this.queuer = new TopicOpQueuer(scheduler);
        this.scheduler = scheduler;
        addr = cfg.getServerAddr();
    }

    @Override
    public synchronized void addTopicOwnershipChangeListener(TopicOwnershipChangeListener listener) {
        listeners.add(listener);
    }

    protected final synchronized void notifyListenersAndAddToOwnedTopics(final ByteString topic,
            final Callback<HedwigSocketAddress> originalCallback, final Object originalContext) {

        Callback<Void> postCb = new Callback<Void>() {

            @Override
            public void operationFinished(Object ctx, Void resultOfOperation) {
                topics.add(topic);
                if (cfg.getRetentionSecs() > 0) {
                    scheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            // Enqueue a release operation. (Recall that release
                            // doesn't "fail" even if the topic is missing.)
                            releaseTopic(topic, new Callback<Void>() {

                                @Override
                                public void operationFailed(Object ctx, PubSubException exception) {
                                    logger.error("failure that should never happen when periodically releasing topic "
                                            + topic, exception);
                                }

                                @Override
                                public void operationFinished(Object ctx, Void resultOfOperation) {
                                    logger.debug("successful periodic release of topic " + topic);
                                }

                            }, null);
                        }
                    }, cfg.getRetentionSecs(), TimeUnit.SECONDS);
                }
                originalCallback.operationFinished(originalContext, addr);
            }

            @Override
            public void operationFailed(Object ctx, PubSubException exception) {
                // TODO: optimization: we can release this as soon as we experience the first error.
                realReleaseTopic(topic, CallbackUtils.curry(originalCallback, addr), originalContext);
                originalCallback.operationFailed(ctx, exception);
            }
        };

        Callback<Void> mcb = CallbackUtils.multiCallback(listeners.size(), postCb, null);
        for (TopicOwnershipChangeListener listener : listeners) {
            listener.acquiredTopic(topic, mcb, null);
        }
    }

    private void realReleaseTopic(ByteString topic, Callback<Void> callback, Object ctx) {
        for (TopicOwnershipChangeListener listener : listeners)
            listener.lostTopic(topic);
        topics.remove(topic);
        postReleaseCleanup(topic, callback, ctx);
    }

    @Override
    public final void getOwner(ByteString topic, boolean shouldClaim,
            Callback<HedwigSocketAddress> cb, Object ctx) {
        queuer.pushAndMaybeRun(topic, new GetOwnerOp(topic, shouldClaim, cb, ctx));
    }

    @Override
    public final void releaseTopic(ByteString topic, Callback<Void> cb, Object ctx) {
        queuer.pushAndMaybeRun(topic, new ReleaseOp(topic, cb, ctx));
    }

    /**
     * This method should "return" the owner of the topic if one has been chosen
     * already. If there is no pre-chosen owner, either this hub or some other
     * should be chosen based on the shouldClaim parameter. If its ends up
     * choosing this hub as the owner, the {@code
     * AbstractTopicManager#notifyListenersAndAddToOwnedTopics(ByteString,
     * OperationCallback, Object)} method must be called.
     * 
     */
    protected abstract void realGetOwner(ByteString topic, boolean shouldClaim,
            Callback<HedwigSocketAddress> cb, Object ctx);

    /**
     * The method should do any cleanup necessary to indicate to other hubs that
     * this topic has been released
     */
    protected abstract void postReleaseCleanup(ByteString topic, Callback<Void> cb, Object ctx);

}
