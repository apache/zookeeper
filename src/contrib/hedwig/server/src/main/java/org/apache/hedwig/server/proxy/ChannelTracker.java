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

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.jboss.netty.channel.Channel;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.client.data.TopicSubscriber;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.TopicBusyException;
import org.apache.hedwig.server.handlers.ChannelDisconnectListener;
import org.apache.hedwig.util.Callback;

public class ChannelTracker implements ChannelDisconnectListener {
    HashMap<TopicSubscriber, Channel> topicSub2Channel = new HashMap<TopicSubscriber, Channel>();
    HashMap<Channel, List<TopicSubscriber>> channel2TopicSubs = new HashMap<Channel, List<TopicSubscriber>>();
    Subscriber subscriber;

    public ChannelTracker(Subscriber subscriber) {
        this.subscriber = subscriber;
    }

    static Callback<Void> noOpCallback = new Callback<Void>() {
        public void operationFailed(Object ctx, PubSubException exception) {
        };

        public void operationFinished(Object ctx, Void resultOfOperation) {
        };
    };

    public synchronized void channelDisconnected(Channel channel) {
        List<TopicSubscriber> topicSubs = channel2TopicSubs.remove(channel);

        if (topicSubs == null) {
            return;
        }

        for (TopicSubscriber topicSub : topicSubs) {
            topicSub2Channel.remove(topicSub);
            subscriber.asyncCloseSubscription(topicSub.getTopic(), topicSub.getSubscriberId(), noOpCallback, null);
        }
    }

    public synchronized void subscribeSucceeded(TopicSubscriber topicSubscriber, Channel channel)
            throws TopicBusyException {

        if (!channel.isConnected()) {
            // channel got disconnected while we were processing the
            // subscribe request, nothing much we can do in this case
            return;
        }

        if (topicSub2Channel.containsKey(topicSubscriber)) {
            TopicBusyException pse = new PubSubException.TopicBusyException(
                    "subscription for this topic, subscriberId is already being served on a different channel");
            throw pse;
        }

        topicSub2Channel.put(topicSubscriber, channel);

        List<TopicSubscriber> topicSubs = channel2TopicSubs.get(channel);

        if (topicSubs == null) {
            topicSubs = new LinkedList<TopicSubscriber>();
            channel2TopicSubs.put(channel, topicSubs);
        }
        topicSubs.add(topicSubscriber);

    }

    public synchronized void aboutToUnsubscribe(ByteString topic, ByteString subscriberId) {
        TopicSubscriber topicSub = new TopicSubscriber(topic, subscriberId);

        Channel channel = topicSub2Channel.remove(topicSub);

        if (channel != null) {
            List<TopicSubscriber> topicSubs = channel2TopicSubs.get(channel);
            if (topicSubs != null) {
                topicSubs.remove(topicSub);
            }
        }
    }

    public synchronized void checkChannelMatches(ByteString topic, ByteString subscriberId, Channel channel)
            throws PubSubException {
        Channel subscribedChannel = getChannel(topic, subscriberId);

        if (subscribedChannel == null) {
            throw new PubSubException.ClientNotSubscribedException(
                    "Can't start delivery since client is not subscribed");
        }

        if (subscribedChannel != channel) {
            throw new PubSubException.TopicBusyException(
                    "Can't start delivery since client is subscribed on a different channel");
        }

    }

    public synchronized Channel getChannel(ByteString topic, ByteString subscriberId) {
        return topicSub2Channel.get(new TopicSubscriber(topic, subscriberId));
    }

}
