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
package org.apache.hedwig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.google.protobuf.ByteString;
import org.apache.hedwig.protocol.PubSubProtocol.Message;

public class HelperMethods {
    static Random rand = new Random();

    public static List<Message> getRandomPublishedMessages(int numMessages, int size) {
        ByteString[] regions = { ByteString.copyFromUtf8("sp1"), ByteString.copyFromUtf8("re1"),
                ByteString.copyFromUtf8("sg") };
        return getRandomPublishedMessages(numMessages, size, regions);
    }

    public static List<Message> getRandomPublishedMessages(int numMessages, int size, ByteString[] regions) {
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < numMessages; i++) {
            byte[] body = new byte[size];
            rand.nextBytes(body);
            msgs.add(Message.newBuilder().setBody(ByteString.copyFrom(body)).setSrcRegion(
                    regions[rand.nextInt(regions.length)]).build());
        }
        return msgs;
    }

    public static boolean areEqual(Message m1, Message m2) {
        if (m1.hasSrcRegion() != m2.hasSrcRegion()) {
            return false;
        }
        if (m1.hasSrcRegion() && !m1.getSrcRegion().equals(m2.getSrcRegion())) {
            return false;
        }
        return m1.getBody().equals(m2.getBody());
    }

}
