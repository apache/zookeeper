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

import com.google.protobuf.ByteString;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest;
import org.apache.hedwig.util.Callback;

/**
 * All methods are thread-safe.
 */
public interface SubscriptionManager {

    /**
     * 
     * Register a new subscription for the given subscriber for the given topic.
     * This method should reliably persist the existence of the subscription in
     * a way that it can't be lost. If the subscription already exists,
     * depending on the create or attach flag in the subscribe request, an
     * exception may be returned.
     * 
     * This is an asynchronous method.
     * 
     * @param topic
     * @param subRequest
     * @param consumeSeqId
     *            The seqId to start serving the subscription from, if this is a
     *            brand new subscription
     * @param callback
     *            The seq id returned by the callback is where serving should
     *            start from
     * @param ctx
     */
    public void serveSubscribeRequest(ByteString topic, SubscribeRequest subRequest, MessageSeqId consumeSeqId,
            Callback<MessageSeqId> callback, Object ctx);

    /**
     * Set the consume position of a given subscriber on a given topic. Note
     * that this method need not persist the consume position immediately but
     * can be lazy and persist it later asynchronously, if that is more
     * efficient.
     * 
     * @param topic
     * @param subscriberId
     * @param consumeSeqId
     */
    public void setConsumeSeqIdForSubscriber(ByteString topic, ByteString subscriberId, MessageSeqId consumeSeqId,
            Callback<Void> callback, Object ctx);

    /**
     * Delete a particular subscription
     * 
     * @param topic
     * @param subscriberId
     */
    public void unsubscribe(ByteString topic, ByteString subscriberId, Callback<Void> callback, Object ctx);

    // Management API methods that we will fill in later
    // /**
    // * Get the ids of all subscribers for a given topic
    // *
    // * @param topic
    // * @return A list of subscriber ids that are currently subscribed to the
    // * given topic
    // */
    // public List<ByteString> getSubscriptionsForTopic(ByteString topic);
    //
    // /**
    // * Get the topics to which a given subscriber is subscribed to
    // *
    // * @param subscriberId
    // * @return A list of the topics to which the given subscriber is
    // subscribed
    // * to
    // * @throws ServiceDownException
    // * If there is an error in looking up the subscription
    // * information
    // */
    // public List<ByteString> getTopicsForSubscriber(ByteString subscriberId)
    // throws ServiceDownException;

    /**
     * Add a listener that is notified when topic-subscription pairs are added
     * or removed.
     */
    public void addListener(SubscriptionEventListener listener);
    
}
