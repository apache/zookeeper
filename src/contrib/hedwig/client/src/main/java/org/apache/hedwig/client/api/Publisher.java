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
import org.apache.hedwig.exceptions.PubSubException.CouldNotConnectException;
import org.apache.hedwig.exceptions.PubSubException.ServiceDownException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.util.Callback;

/**
 * Interface to define the client Publisher API.
 * 
 */
public interface Publisher {

    /**
     * Publishes a message on the given topic.
     * 
     * @param topic
     *            Topic name to publish on
     * @param msg
     *            Message object to serialize and publish
     * @throws CouldNotConnectException
     *             If we are not able to connect to the server host
     * @throws ServiceDownException
     *             If we are unable to publish the message to the topic.
     */
    public void publish(ByteString topic, Message msg) throws CouldNotConnectException, ServiceDownException;

    /**
     * Publishes a message asynchronously on the given topic.
     * 
     * @param topic
     *            Topic name to publish on
     * @param msg
     *            Message object to serialize and publish
     * @param callback
     *            Callback to invoke when the publish to the server has actually
     *            gone through. This will have to deal with error conditions on
     *            the async publish request.
     * @param context
     *            Calling context that the Callback needs since this is done
     *            asynchronously.
     */
    public void asyncPublish(ByteString topic, Message msg, Callback<Void> callback, Object context);

}