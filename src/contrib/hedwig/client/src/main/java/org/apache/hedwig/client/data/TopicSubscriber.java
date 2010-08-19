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

import org.apache.commons.lang.builder.HashCodeBuilder;

import com.google.protobuf.ByteString;

/**
 * Wrapper class object for the Topic + SubscriberId combination. Since the
 * Subscribe flows always use the Topic + SubscriberId as the logical entity,
 * we'll create a simple class to encapsulate that.
 * 
 */
public class TopicSubscriber {
    private final ByteString topic;
    private final ByteString subscriberId;
    private final int hashCode;

    public TopicSubscriber(final ByteString topic, final ByteString subscriberId) {
        this.topic = topic;
        this.subscriberId = subscriberId;
        hashCode = new HashCodeBuilder().append(topic).append(subscriberId).toHashCode();
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this)
            return true;
        if (!(o instanceof TopicSubscriber))
            return false;
        final TopicSubscriber obj = (TopicSubscriber) o;
        return topic.equals(obj.topic) && subscriberId.equals(obj.subscriberId);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (topic != null)
            sb.append("Topic: " + topic.toStringUtf8());
        if (subscriberId != null)
            sb.append(PubSubData.COMMA).append("SubscriberId: " + subscriberId.toStringUtf8());
        return sb.toString();
    }
    
    public ByteString getTopic() {
        return topic;
    }
    
    public ByteString getSubscriberId() {
        return subscriberId;
    }

}