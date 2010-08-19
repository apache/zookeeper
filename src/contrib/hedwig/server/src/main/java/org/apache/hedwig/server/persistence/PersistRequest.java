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
package org.apache.hedwig.server.persistence;

import com.google.protobuf.ByteString;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.util.Callback;

/**
 * Encapsulates a request to persist a given message on a given topic. The
 * request is completed asynchronously, callback and context are provided
 * 
 */
public class PersistRequest {
    ByteString topic;
    Message message;
    Callback<Long> callback;
    Object ctx;

    public PersistRequest(ByteString topic, Message message, Callback<Long> callback, Object ctx) {
        this.topic = topic;
        this.message = message;
        this.callback = callback;
        this.ctx = ctx;
    }

    public ByteString getTopic() {
        return topic;
    }

    public Message getMessage() {
        return message;
    }

    public Callback<Long> getCallback() {
        return callback;
    }

    public Object getCtx() {
        return ctx;
    }

}
