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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.api.Publisher;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.client.data.PubSubData;
import org.apache.hedwig.client.handlers.PubSubCallback;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.CouldNotConnectException;
import org.apache.hedwig.exceptions.PubSubException.ServiceDownException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.OperationType;
import org.apache.hedwig.protocol.PubSubProtocol.ProtocolVersion;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubRequest;
import org.apache.hedwig.protocol.PubSubProtocol.PublishRequest;
import org.apache.hedwig.util.Callback;

/**
 * This is the Hedwig Netty specific implementation of the Publisher interface.
 * 
 */
public class HedwigPublisher implements Publisher {

    private static Logger logger = Logger.getLogger(HedwigPublisher.class);

    // Concurrent Map to store the mappings for a given Host (Hostname:Port) to
    // the Channel that has been established for it previously. This channel
    // will be used whenever we publish on a topic that the server is the master
    // of currently. The channels used here will only be used for publish and
    // unsubscribe requests.
    protected final ConcurrentMap<InetSocketAddress, Channel> host2Channel = new ConcurrentHashMap<InetSocketAddress, Channel>();

    private final HedwigClient client;
    private final ClientConfiguration cfg;

    protected HedwigPublisher(HedwigClient client) {
        this.client = client;
        this.cfg = client.getConfiguration();
    }

    public void publish(ByteString topic, Message msg) throws CouldNotConnectException, ServiceDownException {
        if (logger.isDebugEnabled())
            logger.debug("Calling a sync publish for topic: " + topic.toStringUtf8() + ", msg: " + msg);
        PubSubData pubSubData = new PubSubData(topic, msg, null, OperationType.PUBLISH, null, null, null);
        synchronized (pubSubData) {
            PubSubCallback pubSubCallback = new PubSubCallback(pubSubData);
            asyncPublish(topic, msg, pubSubCallback, null);
            try {
                while (!pubSubData.isDone)
                    pubSubData.wait();
            } catch (InterruptedException e) {
                throw new ServiceDownException("Interrupted Exception while waiting for async publish call");
            }
            // Check from the PubSubCallback if it was successful or not.
            if (!pubSubCallback.getIsCallSuccessful()) {
                // See what the exception was that was thrown when the operation
                // failed.
                PubSubException failureException = pubSubCallback.getFailureException();
                if (failureException == null) {
                    // This should not happen as the operation failed but a null
                    // PubSubException was passed. Log a warning message but
                    // throw a generic ServiceDownException.
                    logger.error("Sync Publish operation failed but no PubSubException was passed!");
                    throw new ServiceDownException("Server ack response to publish request is not successful");
                }
                // For the expected exceptions that could occur, just rethrow
                // them.
                else if (failureException instanceof CouldNotConnectException) {
                    throw (CouldNotConnectException) failureException;
                } else if (failureException instanceof ServiceDownException) {
                    throw (ServiceDownException) failureException;
                } else {
                    // For other types of PubSubExceptions, just throw a generic
                    // ServiceDownException but log a warning message.
                    logger.error("Unexpected exception type when a sync publish operation failed: " + failureException);
                    throw new ServiceDownException("Server ack response to publish request is not successful");
                }
            }
        }
    }

    public void asyncPublish(ByteString topic, Message msg, Callback<Void> callback, Object context) {
        if (logger.isDebugEnabled())
            logger.debug("Calling an async publish for topic: " + topic.toStringUtf8() + ", msg: " + msg);
        // Check if we already have a Channel connection set up to the server
        // for the given Topic.
        PubSubData pubSubData = new PubSubData(topic, msg, null, OperationType.PUBLISH, null, callback, context);
        if (client.topic2Host.containsKey(topic)) {
            InetSocketAddress host = client.topic2Host.get(topic);
            if (host2Channel.containsKey(host)) {
                // We already have the Channel connection for the server host so
                // do the publish directly. We will deal with redirect logic
                // later on if that server is no longer the current host for
                // the topic.
                doPublish(pubSubData, host2Channel.get(host));
            } else {
                // We have a mapping for the topic to host but don't have a
                // Channel for that server. This can happen if the Channel
                // is disconnected for some reason. Do the connect then to
                // the specified server host to create a new Channel connection.
                client.doConnect(pubSubData, host);
            }
        } else {
            // Server host for the given topic is not known yet so use the
            // default server host/port as defined in the configs. This should
            // point to the server VIP which would redirect to a random server
            // (which might not be the server hosting the topic).
            client.doConnect(pubSubData, cfg.getDefaultServerHost());
        }
    }

    /**
     * This is a helper method to write the actual publish message once the
     * client is connected to the server and a Channel is available.
     * 
     * @param pubSubData
     *            Publish call's data wrapper object
     * @param channel
     *            Netty I/O channel for communication between the client and
     *            server
     */
    protected void doPublish(PubSubData pubSubData, Channel channel) {
        // Create a PubSubRequest
        PubSubRequest.Builder pubsubRequestBuilder = PubSubRequest.newBuilder();
        pubsubRequestBuilder.setProtocolVersion(ProtocolVersion.VERSION_ONE);
        pubsubRequestBuilder.setType(OperationType.PUBLISH);
        if (pubSubData.triedServers != null && pubSubData.triedServers.size() > 0) {
            pubsubRequestBuilder.addAllTriedServers(pubSubData.triedServers);
        }
        long txnId = client.globalCounter.incrementAndGet();
        pubsubRequestBuilder.setTxnId(txnId);
        pubsubRequestBuilder.setShouldClaim(pubSubData.shouldClaim);
        pubsubRequestBuilder.setTopic(pubSubData.topic);

        // Now create the PublishRequest
        PublishRequest.Builder publishRequestBuilder = PublishRequest.newBuilder();

        publishRequestBuilder.setMsg(pubSubData.msg);

        // Set the PublishRequest into the outer PubSubRequest
        pubsubRequestBuilder.setPublishRequest(publishRequestBuilder);

        // Update the PubSubData with the txnId and the requestWriteTime
        pubSubData.txnId = txnId;
        pubSubData.requestWriteTime = System.currentTimeMillis();

        // Before we do the write, store this information into the
        // ResponseHandler so when the server responds, we know what
        // appropriate Callback Data to invoke for the given txn ID.
        HedwigClient.getResponseHandlerFromChannel(channel).txn2PubSubData.put(txnId, pubSubData);

        // Finally, write the Publish request through the Channel.
        if (logger.isDebugEnabled())
            logger.debug("Writing a Publish request to host: " + HedwigClient.getHostFromChannel(channel)
                    + " for pubSubData: " + pubSubData);
        ChannelFuture future = channel.write(pubsubRequestBuilder.build());
        future.addListener(new WriteCallback(pubSubData, client));
    }

    // Synchronized method to store the host2Channel mapping (if it doesn't
    // exist yet). Retrieve the hostname info from the Channel created via the
    // RemoteAddress tied to it.
    protected synchronized void storeHost2ChannelMapping(Channel channel) {
        InetSocketAddress host = HedwigClient.getHostFromChannel(channel);
        if (!host2Channel.containsKey(host)) {
            if (logger.isDebugEnabled())
                logger.debug("Storing a new Channel mapping for host: " + host);
            host2Channel.put(host, channel);
        } else {
            // If we've reached here, that means we already have a Channel
            // mapping for the given host. This should ideally not happen
            // and it means we are creating another Channel to a server host
            // to publish on when we could have used an existing one. This could
            // happen due to a race condition if initially multiple concurrent
            // threads are publishing on the same topic and no Channel exists
            // currently to the server. We are not synchronizing this initial
            // creation of Channels to a given host for performance.
            // Another possible way to have redundant Channels created is if
            // a new topic is being published to, we connect to the default
            // server host which should be a VIP that redirects to a "real"
            // server host. Since we don't know beforehand what is the full
            // set of server hosts, we could be redirected to a server that
            // we already have a channel connection to from a prior existing
            // topic. Close these redundant channels as they won't be used.
            if (logger.isDebugEnabled())
                logger.debug("Channel mapping to host: " + host + " already exists so no need to store it.");
            HedwigClient.getResponseHandlerFromChannel(channel).channelClosedExplicitly = true;
            channel.close();
        }
    }

    // Public getter for entries in the host2Channel Map.
    // This is used for classes that need this information but are not in the
    // same classpath.
    public Channel getChannelForHost(InetSocketAddress host) {
        return host2Channel.get(host);
    }

}
