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

import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionState;

public class InMemorySubscriptionState {
    SubscriptionState subscriptionState;
    MessageSeqId lastConsumeSeqId;

    public InMemorySubscriptionState(SubscriptionState subscriptionState, MessageSeqId lastConsumeSeqId) {
        this.subscriptionState = subscriptionState;
        this.lastConsumeSeqId = lastConsumeSeqId;
    }

    public InMemorySubscriptionState(SubscriptionState subscriptionState) {
        this(subscriptionState, subscriptionState.getMsgId());
    }

    public SubscriptionState getSubscriptionState() {
        return subscriptionState;
    }

    public MessageSeqId getLastConsumeSeqId() {
        return lastConsumeSeqId;
    }

    /**
     * 
     * @param lastConsumeSeqId
     * @param consumeInterval
     *            The amount of laziness we want in persisting the consume
     *            pointers
     * @return true if the resulting structure needs to be persisted, false
     *         otherwise
     */
    public boolean setLastConsumeSeqId(MessageSeqId lastConsumeSeqId, int consumeInterval) {
        this.lastConsumeSeqId = lastConsumeSeqId;

        if (lastConsumeSeqId.getLocalComponent() - subscriptionState.getMsgId().getLocalComponent() < consumeInterval) {
            return false;
        }

        subscriptionState = SubscriptionState.newBuilder(subscriptionState).setMsgId(lastConsumeSeqId).build();
        return true;
    }

}
