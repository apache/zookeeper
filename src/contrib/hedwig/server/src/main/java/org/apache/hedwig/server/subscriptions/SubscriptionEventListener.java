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
import org.apache.hedwig.util.Callback;

/**
 * For listening to events that are issued by a SubscriptionManager.
 * 
 */
public interface SubscriptionEventListener {

    /**
     * Called by the subscription manager when it previously had zero local
     * subscribers for a topic and is currently accepting its first local
     * subscriber.
     * 
     * @param topic
     *            The topic of interest.
     * @param synchronous
     *            Whether this request was actually initiated by a new local
     *            subscriber, or whether it was an existing subscription
     *            inherited by the hub (e.g. when recovering the state from ZK).
     * @param cb
     *            The subscription will not complete until success is called on
     *            this callback. An error on cb will result in a subscription
     *            error.
     */
    public void onFirstLocalSubscribe(ByteString topic, boolean synchronous, Callback<Void> cb);

    /**
     * Called by the SubscriptionManager when it previously had non-zero local
     * subscribers for a topic and is currently dropping its last local
     * subscriber. This is fully asynchronous so there is no callback.
     * 
     * @param topic
     *            The topic of interest.
     */
    public void onLastLocalUnsubscribe(ByteString topic);
    
}
