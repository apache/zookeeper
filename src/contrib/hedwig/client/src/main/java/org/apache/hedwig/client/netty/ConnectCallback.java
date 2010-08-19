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
package org.apache.hedwig.client.netty;

import java.net.InetSocketAddress;
import java.util.LinkedList;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.client.data.PubSubData;
import org.apache.hedwig.exceptions.PubSubException.CouldNotConnectException;
import org.apache.hedwig.protocol.PubSubProtocol.OperationType;
import org.apache.hedwig.util.HedwigSocketAddress;

public class ConnectCallback implements ChannelFutureListener {

    private static Logger logger = Logger.getLogger(ConnectCallback.class);

    // Private member variables
    private PubSubData pubSubData;
    private InetSocketAddress host;
    private final HedwigClient client;
    private final HedwigPublisher pub;
    private final HedwigSubscriber sub;
    private final ClientConfiguration cfg;

    // Constructor
    public ConnectCallback(PubSubData pubSubData, InetSocketAddress host, HedwigClient client) {
        super();
        this.pubSubData = pubSubData;
        this.host = host;
        this.client = client;
        this.pub = client.getPublisher();
        this.sub = client.getSubscriber();
        this.cfg = client.getConfiguration();
    }

    public void operationComplete(ChannelFuture future) throws Exception {
        // If the client has stopped, there is no need to proceed with any
        // callback logic here.
        if (client.hasStopped())
            return;

        // Check if the connection to the server was done successfully.
        if (!future.isSuccess()) {
            logger.error("Error connecting to host: " + host);
            // If we were not able to connect to the host, it could be down.
            ByteString hostString = ByteString.copyFromUtf8(HedwigSocketAddress.sockAddrStr(host));
            if (pubSubData.connectFailedServers != null && pubSubData.connectFailedServers.contains(hostString)) {
                // We've already tried to connect to this host before so just
                // invoke the operationFailed callback.
                logger.error("Error connecting to host more than once so just invoke the operationFailed callback!");
                pubSubData.callback.operationFailed(pubSubData.context, new CouldNotConnectException(
                        "Could not connect to host: " + host));
            } else {
                if (logger.isDebugEnabled())
                    logger.debug("Try to connect to server: " + host + " again for pubSubData: " + pubSubData);
                // Keep track of this current server that we failed to connect
                // to but retry the request on the default server host/VIP.
                // The topic2Host mapping might need to be updated.
                if (pubSubData.connectFailedServers == null)
                    pubSubData.connectFailedServers = new LinkedList<ByteString>();
                pubSubData.connectFailedServers.add(hostString);
                client.doConnect(pubSubData, cfg.getDefaultServerHost());
            }
            // Finished with failure logic so just return.
            return;
        }

        // Now that we have connected successfully to the server, see what type
        // of PubSub request this was.
        if (logger.isDebugEnabled())
            logger.debug("Connection to host: " + host + " was successful for pubSubData: " + pubSubData);
        if (pubSubData.operationType.equals(OperationType.PUBLISH)) {
            // Publish Request so store this Channel connection in the
            // HedwigPublisher Map (if it doesn't exist yet) and then
            // do the publish on the cached channel mapped to the host.
            // Note that due to race concurrency situations, it is
            // possible that the cached channel is not the same one
            // as the channel established here. If that is the case,
            // this channel will be closed but we'll always publish on the
            // cached channel in the HedwigPublisher.host2Channel map.
            pub.storeHost2ChannelMapping(future.getChannel());
            pub.doPublish(pubSubData, pub.host2Channel.get(HedwigClient.getHostFromChannel(future.getChannel())));
        } else if (pubSubData.operationType.equals(OperationType.UNSUBSCRIBE)) {
            // Unsubscribe Request so store this Channel connection in the
            // HedwigPublisher Map (if it doesn't exist yet) and then do the
            // unsubscribe. Unsubscribe requests will share and reuse
            // the netty Channel connections that Publish requests use.
            pub.storeHost2ChannelMapping(future.getChannel());
            sub.doSubUnsub(pubSubData, pub.host2Channel.get(HedwigClient.getHostFromChannel(future.getChannel())));
        } else {
            // Subscribe Request. We do not store the Channel connection yet for
            // Subscribes here. This will be done only when we've found the
            // right server topic master. That is only determined when we
            // receive a successful server ack response to the Subscribe
            // request (handled in ResponseHandler). There is no need to store
            // the Unsubscribe channel connection as we won't use it again.
            sub.doSubUnsub(pubSubData, future.getChannel());
        }
    }

}
