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

import org.jboss.netty.channel.socket.ClientSocketChannelFactory;

import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.client.netty.HedwigClient;

/**
 * This is a hub specific implementation of the HedwigClient. All this does
 * though is to override the HedwigSubscriber with the hub specific child class.
 * Creating this class so we can call the protected method in the parent to set
 * the subscriber since we don't want to expose that API to the public.
 */
public class HedwigHubClient extends HedwigClient {

    // Constructor when we already have a ChannelFactory instantiated.
    public HedwigHubClient(ClientConfiguration cfg, ClientSocketChannelFactory channelFactory) {
        super(cfg, channelFactory);
        // Override the type of HedwigSubscriber with the hub specific one.
        setSubscriber(new HedwigHubSubscriber(this));
    }

    // Constructor when we don't have a ChannelFactory. The super constructor
    // will create one for us.
    public HedwigHubClient(ClientConfiguration cfg) {
        super(cfg);
        // Override the type of HedwigSubscriber with the hub specific one.
        setSubscriber(new HedwigHubSubscriber(this));
    }

}
