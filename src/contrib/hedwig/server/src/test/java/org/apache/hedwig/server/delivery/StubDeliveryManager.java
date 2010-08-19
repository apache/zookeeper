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
package org.apache.hedwig.server.delivery;

import java.util.LinkedList;
import java.util.Queue;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.data.TopicSubscriber;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.server.subscriptions.MessageFilter;

public class StubDeliveryManager implements DeliveryManager {

    public static class StartServingRequest {
        public ByteString topic;
        public ByteString subscriberId;
        public MessageSeqId seqIdToStartFrom;
        public DeliveryEndPoint endPoint;
        public MessageFilter filter;
        public boolean isHubSubscriber;

        public StartServingRequest(ByteString topic, ByteString subscriberId, MessageSeqId seqIdToStartFrom,
                DeliveryEndPoint endPoint, MessageFilter filter, boolean isHubSubscriber) {
            this.topic = topic;
            this.subscriberId = subscriberId;
            this.seqIdToStartFrom = seqIdToStartFrom;
            this.endPoint = endPoint;
            this.filter = filter;
            this.isHubSubscriber = isHubSubscriber;
        }

    }

    public Queue<Object> lastRequest = new LinkedList<Object>();

    @Override
    public void startServingSubscription(ByteString topic, ByteString subscriberId, MessageSeqId seqIdToStartFrom,
            DeliveryEndPoint endPoint, MessageFilter filter, boolean isHubSubscriber) {

        lastRequest.add(new StartServingRequest(topic, subscriberId, seqIdToStartFrom, endPoint, filter,
                isHubSubscriber));

    }

    @Override
    public void stopServingSubscriber(ByteString topic, ByteString subscriberId) {
        lastRequest.add(new TopicSubscriber(topic, subscriberId));
    }
}
