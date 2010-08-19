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

import java.util.concurrent.ScheduledExecutorService;

import com.google.protobuf.ByteString;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.persistence.PersistenceManager;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.util.Callback;

public class StubSubscriptionManager extends InMemorySubscriptionManager {
    boolean fail = false;

    public void setFail(boolean fail) {
        this.fail = fail;
    }

    public StubSubscriptionManager(TopicManager tm, PersistenceManager pm, ServerConfiguration conf, ScheduledExecutorService scheduler) {
        super(tm, pm, conf, scheduler);
    }

    @Override
    public void serveSubscribeRequest(ByteString topic, SubscribeRequest subRequest, MessageSeqId consumeSeqId,
            Callback<MessageSeqId> callback, Object ctx) {
        if (fail) {
            callback.operationFailed(ctx, new PubSubException.ServiceDownException("Asked to fail"));
            return;
        }
        super.serveSubscribeRequest(topic, subRequest, consumeSeqId, callback, ctx);
    }

}
