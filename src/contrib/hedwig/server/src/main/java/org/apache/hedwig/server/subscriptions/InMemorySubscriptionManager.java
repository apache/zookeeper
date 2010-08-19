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
package org.apache.hedwig.server.subscriptions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

import com.google.protobuf.ByteString;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionState;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.persistence.PersistenceManager;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.util.Callback;

public class InMemorySubscriptionManager extends AbstractSubscriptionManager {

    public InMemorySubscriptionManager(TopicManager tm, PersistenceManager pm, ServerConfiguration conf, ScheduledExecutorService scheduler) {
        super(conf, tm, pm, scheduler);
    }

    @Override
    protected void createSubscriptionState(ByteString topic, ByteString subscriberId, SubscriptionState state,
            Callback<Void> callback, Object ctx) {
        // nothing to do, in-memory info is already recorded by base class
        callback.operationFinished(ctx, null);
    }

    @Override
    protected void deleteSubscriptionState(ByteString topic, ByteString subscriberId, Callback<Void> callback,
            Object ctx) {
        // nothing to do, in-memory info is already deleted by base class
        callback.operationFinished(ctx, null);
    }

    @Override
    protected void updateSubscriptionState(ByteString topic, ByteString subscriberId, SubscriptionState state,
            Callback<Void> callback, Object ctx) {
        // nothing to do, in-memory info is already updated by base class
        callback.operationFinished(ctx, null);
    }

    @Override
    public void lostTopic(ByteString topic) {
        // Intentionally do nothing, so that we dont lose in-memory information
    }

    @Override
    protected void readSubscriptions(ByteString topic,
            Callback<Map<ByteString, InMemorySubscriptionState>> cb, Object ctx) {
        // Since we don't lose in-memory information on lostTopic, we can just
        // return that back
        Map<ByteString, InMemorySubscriptionState> topicSubs = top2sub2seq.get(topic);

        if (topicSubs != null) {
            cb.operationFinished(ctx, topicSubs);
        } else {
            cb.operationFinished(ctx, new ConcurrentHashMap<ByteString, InMemorySubscriptionState>());
        }

    }

}
