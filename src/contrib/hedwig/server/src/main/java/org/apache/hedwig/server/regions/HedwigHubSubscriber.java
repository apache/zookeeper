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
package org.apache.hedwig.server.regions;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.exceptions.InvalidSubscriberIdException;
import org.apache.hedwig.client.netty.HedwigClient;
import org.apache.hedwig.client.netty.HedwigSubscriber;
import org.apache.hedwig.exceptions.PubSubException.ClientAlreadySubscribedException;
import org.apache.hedwig.exceptions.PubSubException.ClientNotSubscribedException;
import org.apache.hedwig.exceptions.PubSubException.CouldNotConnectException;
import org.apache.hedwig.exceptions.PubSubException.ServiceDownException;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.util.Callback;

/**
 * This is a hub specific child class of the HedwigSubscriber. The main thing is
 * does is wrap the public subscribe/unsubscribe methods by calling the
 * overloaded protected ones passing in a true value for the input boolean
 * parameter isHub. That will just make sure we validate the subscriberId
 * passed, ensuring it is of the right format either for a local or hub
 * subscriber.
 */
public class HedwigHubSubscriber extends HedwigSubscriber {

    public HedwigHubSubscriber(HedwigClient client) {
        super(client);
    }

    @Override
    public void subscribe(ByteString topic, ByteString subscriberId, CreateOrAttach mode)
            throws CouldNotConnectException, ClientAlreadySubscribedException, ServiceDownException,
            InvalidSubscriberIdException {
        subscribe(topic, subscriberId, mode, true);
    }

    @Override
    public void asyncSubscribe(ByteString topic, ByteString subscriberId, CreateOrAttach mode, Callback<Void> callback,
            Object context) {
        asyncSubscribe(topic, subscriberId, mode, callback, context, true);
    }

    @Override
    public void unsubscribe(ByteString topic, ByteString subscriberId) throws CouldNotConnectException,
            ClientNotSubscribedException, ServiceDownException, InvalidSubscriberIdException {
        unsubscribe(topic, subscriberId, true);
    }

    @Override
    public void asyncUnsubscribe(final ByteString topic, final ByteString subscriberId, final Callback<Void> callback,
            final Object context) {
        asyncUnsubscribe(topic, subscriberId, callback, context, true);
    }

}
