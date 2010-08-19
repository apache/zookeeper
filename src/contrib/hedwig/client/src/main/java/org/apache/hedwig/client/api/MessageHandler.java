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
package org.apache.hedwig.client.api;

import com.google.protobuf.ByteString;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.util.Callback;

/**
 * Interface to define the client handler logic to consume messages it is
 * subscribed to.
 * 
 */
public interface MessageHandler {

    /**
     * Consumes a message it is subscribed to and has been delivered to it.
     * 
     * @param topic
     *            The topic name where the message came from.
     * @param subscriberId
     *            ID of the subscriber.
     * @param msg
     *            The message object to consume.
     * @param callback
     *            Callback to invoke when the message consumption has been done.
     * @param context
     *            Calling context that the Callback needs since this is done
     *            asynchronously.
     */
    public void consume(ByteString topic, ByteString subscriberId, Message msg, Callback<Void> callback, Object context);

}