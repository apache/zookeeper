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
package org.apache.hedwig.client.handlers;

import java.util.TimerTask;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.Channel;

import org.apache.hedwig.client.data.MessageConsumeData;
import org.apache.hedwig.client.data.TopicSubscriber;
import org.apache.hedwig.client.netty.HedwigClient;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.util.Callback;

/**
 * This is the Callback used by the MessageHandlers on the client app when
 * they've finished consuming a subscription message sent from the server
 * asynchronously. This callback back to the client libs will be stateless so we
 * can use a singleton for the class. The object context used should be the
 * MessageConsumeData type. That will contain all of the information needed to
 * call the message consume logic in the client lib ResponseHandler.
 * 
 */
public class MessageConsumeCallback implements Callback<Void> {

    private static Logger logger = Logger.getLogger(MessageConsumeCallback.class);

    private final HedwigClient client;

    public MessageConsumeCallback(HedwigClient client) {
        this.client = client;
    }

    class MessageConsumeRetryTask extends TimerTask {
        private final MessageConsumeData messageConsumeData;
        private final TopicSubscriber topicSubscriber;

        public MessageConsumeRetryTask(MessageConsumeData messageConsumeData, TopicSubscriber topicSubscriber) {
            this.messageConsumeData = messageConsumeData;
            this.topicSubscriber = topicSubscriber;
        }

        @Override
        public void run() {
            // Try to consume the message again
            Channel topicSubscriberChannel = client.getSubscriber().getChannelForTopic(topicSubscriber);
            HedwigClient.getResponseHandlerFromChannel(topicSubscriberChannel).getSubscribeResponseHandler()
                    .asyncMessageConsume(messageConsumeData.msg);
        }
    }

    public void operationFinished(Object ctx, Void resultOfOperation) {
        MessageConsumeData messageConsumeData = (MessageConsumeData) ctx;
        TopicSubscriber topicSubscriber = new TopicSubscriber(messageConsumeData.topic, messageConsumeData.subscriberId);
        // Message has been successfully consumed by the client app so callback
        // to the ResponseHandler indicating that the message is consumed.
        Channel topicSubscriberChannel = client.getSubscriber().getChannelForTopic(topicSubscriber);
        HedwigClient.getResponseHandlerFromChannel(topicSubscriberChannel).getSubscribeResponseHandler()
                .messageConsumed(messageConsumeData.msg);
    }

    public void operationFailed(Object ctx, PubSubException exception) {
        // Message has NOT been successfully consumed by the client app so
        // callback to the ResponseHandler to try the async MessageHandler
        // Consume logic again.
        MessageConsumeData messageConsumeData = (MessageConsumeData) ctx;
        TopicSubscriber topicSubscriber = new TopicSubscriber(messageConsumeData.topic, messageConsumeData.subscriberId);
        logger.error("Message was not consumed successfully by client MessageHandler: " + messageConsumeData);

        // Sleep a pre-configured amount of time (in milliseconds) before we
        // do the retry. In the future, we can have more dynamic logic on
        // what duration to sleep based on how many times we've retried, or
        // perhaps what the last amount of time we slept was. We could stick
        // some of this meta-data into the MessageConsumeData when we retry.
        client.getClientTimer().schedule(new MessageConsumeRetryTask(messageConsumeData, topicSubscriber),
                client.getConfiguration().getMessageConsumeRetryWaitTime());
    }

}
