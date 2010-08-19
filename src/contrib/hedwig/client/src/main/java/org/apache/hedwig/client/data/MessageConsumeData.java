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
package org.apache.hedwig.client.data;

import com.google.protobuf.ByteString;
import org.apache.hedwig.protocol.PubSubProtocol.Message;

/**
 * Wrapper class to store all of the data points needed to encapsulate Message
 * Consumption in the Subscribe flow for consuming a message sent from the
 * server for a given TopicSubscriber. This will be used as the Context in the
 * VoidCallback for the MessageHandlers once they've completed consuming the
 * message.
 * 
 */
public class MessageConsumeData {

    // Member variables
    public final ByteString topic;
    public final ByteString subscriberId;
    // This is the Message sent from the server for Subscribes for consumption
    // by the client.
    public final Message msg;

    // Constructor
    public MessageConsumeData(final ByteString topic, final ByteString subscriberId, final Message msg) {
        this.topic = topic;
        this.subscriberId = subscriberId;
        this.msg = msg;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (topic != null)
            sb.append("Topic: " + topic.toStringUtf8());
        if (subscriberId != null)
            sb.append(PubSubData.COMMA).append("SubscriberId: " + subscriberId.toStringUtf8());
        if (msg != null)
            sb.append(PubSubData.COMMA).append("Message: " + msg);
        return sb.toString();
    }
}
