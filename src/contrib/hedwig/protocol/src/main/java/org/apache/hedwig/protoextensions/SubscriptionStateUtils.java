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
package org.apache.hedwig.protoextensions;

import com.google.protobuf.ByteString;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionState;

public class SubscriptionStateUtils {

    // For now, to differentiate hub subscribers from local ones, the
    // subscriberId will be prepended with a hard-coded prefix. Local
    // subscribers will validate that the subscriberId used cannot start with
    // this prefix. This is only used internally by the hub subscribers.
    public static final String HUB_SUBSCRIBER_PREFIX = "__";

    public static String toString(SubscriptionState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("consumeSeqId: " + MessageIdUtils.msgIdToReadableString(state.getMsgId()));
        return sb.toString();
    }

    public static boolean isHubSubscriber(ByteString subscriberId) {
        return subscriberId.toStringUtf8().startsWith(HUB_SUBSCRIBER_PREFIX);
    }
    
}
