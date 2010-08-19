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
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.client.data.PubSubData;
import org.apache.hedwig.client.handlers.MessageConsumeCallback;
import org.apache.hedwig.client.ssl.SslClientContextFactory;
import org.apache.hedwig.exceptions.PubSubException.UncertainStateException;

/**
 * This is a top level Hedwig Client class that encapsulates the common
 * functionality needed for both Publish and Subscribe operations.
 * 
 */
public class HedwigClient {

    private static final Logger logger = Logger.getLogger(HedwigClient.class);

    // Global counter used for generating unique transaction ID's for
    // publish and subscribe requests
    protected final AtomicLong globalCounter = new AtomicLong();
    // Static String constants
    protected static final String COLON = ":";

    // The Netty socket factory for making connections to the server.
    protected final ChannelFactory socketFactory;
    // Whether the socket factory is one we created or is owned by whoever
    // instantiated us.
    protected boolean ownChannelFactory = false;

    // PipelineFactory to create netty client channels to the appropriate server
    private ClientChannelPipelineFactory pipelineFactory;

    // Concurrent Map to store the mapping from the Topic to the Host.
    // This could change over time since servers can drop mastership of topics
    // for load balancing or failover. If a server host ever goes down, we'd
    // also want to remove all topic mappings the host was responsible for.
    // The second Map is used as the inverted version of the first one.
    protected final ConcurrentMap<ByteString, InetSocketAddress> topic2Host = new ConcurrentHashMap<ByteString, InetSocketAddress>();
    private final ConcurrentMap<InetSocketAddress, List<ByteString>> host2Topics = new ConcurrentHashMap<InetSocketAddress, List<ByteString>>();

    // Each client instantiation will have a Timer for running recurring
    // threads. One such timer task thread to is to timeout long running
    // PubSubRequests that are waiting for an ack response from the server.
    private final Timer clientTimer = new Timer(true);

    // Boolean indicating if the client is running or has stopped.
    // Once we stop the client, we should sidestep all of the connect,
    // write callback and channel disconnected logic.
    private boolean isStopped = false;

    private HedwigSubscriber sub;
    private final HedwigPublisher pub;
    private final ClientConfiguration cfg;
    private final MessageConsumeCallback consumeCb;
    private SslClientContextFactory sslFactory = null;

    // Base constructor that takes in a Configuration object.
    // This will create its own client socket channel factory.
    public HedwigClient(ClientConfiguration cfg) {
        this(cfg, new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool()));
        ownChannelFactory = true;
    }

    // Constructor that takes in a Configuration object and a ChannelFactory
    // that has already been instantiated by the caller.
    public HedwigClient(ClientConfiguration cfg, ChannelFactory socketFactory) {
        this.cfg = cfg;
        this.socketFactory = socketFactory;
        pub = new HedwigPublisher(this);
        sub = new HedwigSubscriber(this);
        pipelineFactory = new ClientChannelPipelineFactory(this);
        consumeCb = new MessageConsumeCallback(this);
        if (cfg.isSSLEnabled()) {
            sslFactory = new SslClientContextFactory(cfg);
        }
        // Schedule all of the client timer tasks. Currently we only have the
        // Request Timeout task.
        clientTimer.schedule(new PubSubRequestTimeoutTask(), 0, cfg.getTimeoutThreadRunInterval());
    }

    // Public getters for the various components of a client.
    public ClientConfiguration getConfiguration() {
        return cfg;
    }

    public HedwigSubscriber getSubscriber() {
        return sub;
    }

    // Protected method to set the subscriber. This is needed currently for hub
    // versions of the client subscriber.
    protected void setSubscriber(HedwigSubscriber sub) {
        this.sub = sub;
    }

    public HedwigPublisher getPublisher() {
        return pub;
    }

    public MessageConsumeCallback getConsumeCallback() {
        return consumeCb;
    }

    public SslClientContextFactory getSslFactory() {
        return sslFactory;
    }

    // We need to deal with the possible problem of a PubSub request being
    // written to successfully to the server host but for some reason, the
    // ack message back never comes. What could happen is that the VoidCallback
    // stored in the ResponseHandler.txn2PublishData map will never be called.
    // We should have a configured timeout so if that passes from the time a
    // write was successfully done to the server, we can fail this async PubSub
    // transaction. The caller could possibly redo the transaction if needed at
    // a later time. Creating a timeout cleaner TimerTask to do this here.
    class PubSubRequestTimeoutTask extends TimerTask {
        /**
         * Implement the TimerTask's abstract run method.
         */
        @Override
        public void run() {
            if (logger.isDebugEnabled())
                logger.debug("Running the PubSubRequest Timeout Task");
            // Loop through all outstanding PubSubData requests and check if
            // the requestWriteTime has timed out compared to the current time.
            long curTime = System.currentTimeMillis();
            long timeoutInterval = cfg.getServerAckResponseTimeout();

            // First check the ResponseHandlers associated with cached
            // channels in HedwigPublisher.host2Channel. This stores the
            // channels used for Publish and Unsubscribe requests.
            for (Channel channel : pub.host2Channel.values()) {
                ResponseHandler responseHandler = getResponseHandlerFromChannel(channel);
                for (PubSubData pubSubData : responseHandler.txn2PubSubData.values()) {
                    checkPubSubDataToTimeOut(pubSubData, responseHandler, curTime, timeoutInterval);
                }
            }
            // Now do the same for the cached channels in
            // HedwigSubscriber.topicSubscriber2Channel. This stores the
            // channels used exclusively for Subscribe requests.
            for (Channel channel : sub.topicSubscriber2Channel.values()) {
                ResponseHandler responseHandler = getResponseHandlerFromChannel(channel);
                for (PubSubData pubSubData : responseHandler.txn2PubSubData.values()) {
                    checkPubSubDataToTimeOut(pubSubData, responseHandler, curTime, timeoutInterval);
                }
            }
        }

        private void checkPubSubDataToTimeOut(PubSubData pubSubData, ResponseHandler responseHandler, long curTime,
                long timeoutInterval) {
            if (curTime > pubSubData.requestWriteTime + timeoutInterval) {
                // Current PubSubRequest has timed out so remove it from the
                // ResponseHandler's map and invoke the VoidCallback's
                // operationFailed method.
                logger.error("Current PubSubRequest has timed out for pubSubData: " + pubSubData);
                responseHandler.txn2PubSubData.remove(pubSubData.txnId);
                pubSubData.callback.operationFailed(pubSubData.context, new UncertainStateException(
                        "Server ack response never received so PubSubRequest has timed out!"));
            }
        }
    }

    // When we are done with the client, this is a clean way to gracefully close
    // all channels/sockets created by the client and to also release all
    // resources used by netty.
    public void stop() {
        logger.info("Stopping the client!");
        // Set the client boolean flag to indicate the client has stopped.
        isStopped = true;
        // Stop the timer and all timer task threads.
        clientTimer.cancel();
        // Close all of the open Channels.
        for (Channel channel : pub.host2Channel.values()) {
            getResponseHandlerFromChannel(channel).channelClosedExplicitly = true;
            channel.close().awaitUninterruptibly();
        }
        for (Channel channel : sub.topicSubscriber2Channel.values()) {
            getResponseHandlerFromChannel(channel).channelClosedExplicitly = true;
            channel.close().awaitUninterruptibly();
        }
        // Clear out all Maps.
        topic2Host.clear();
        host2Topics.clear();
        pub.host2Channel.clear();
        sub.topicSubscriber2Channel.clear();
        // Release resources used by the ChannelFactory on the client if we are
        // the owner that created it.
        if (ownChannelFactory) {
            socketFactory.releaseExternalResources();
        }
        logger.info("Completed stopping the client!");
    }

    /**
     * This is a helper method to do the connect attempt to the server given the
     * inputted host/port. This can be used to connect to the default server
     * host/port which is the VIP. That will pick a server in the cluster at
     * random to connect to for the initial PubSub attempt (with redirect logic
     * being done at the server side). Additionally, this could be called after
     * the client makes an initial PubSub attempt at a server, and is redirected
     * to the one that is responsible for the topic. Once the connect to the
     * server is done, we will perform the corresponding PubSub write on that
     * channel.
     * 
     * @param pubSubData
     *            PubSub call's data wrapper object
     * @param serverHost
     *            Input server host to connect to of type InetSocketAddress
     */
    public void doConnect(PubSubData pubSubData, InetSocketAddress serverHost) {
        if (logger.isDebugEnabled())
            logger.debug("Connecting to host: " + serverHost + " with pubSubData: " + pubSubData);
        // Set up the ClientBootStrap so we can create a new Channel connection
        // to the server.
        ClientBootstrap bootstrap = new ClientBootstrap(socketFactory);
        bootstrap.setPipelineFactory(pipelineFactory);
        bootstrap.setOption("tcpNoDelay", true);
        bootstrap.setOption("keepAlive", true);

        // Start the connection attempt to the input server host.
        ChannelFuture future = bootstrap.connect(serverHost);
        future.addListener(new ConnectCallback(pubSubData, serverHost, this));
    }

    /**
     * Helper method to store the topic2Host mapping in the HedwigClient cache
     * map. This method is assumed to be called when we've done a successful
     * connection to the correct server topic master.
     * 
     * @param pubSubData
     *            PubSub wrapper data
     * @param channel
     *            Netty Channel
     */
    protected void storeTopic2HostMapping(PubSubData pubSubData, Channel channel) {
        // Retrieve the server host that we've connected to and store the
        // mapping from the topic to this host. For all other non-redirected
        // server statuses, we consider that as a successful connection to the
        // correct topic master.
        InetSocketAddress host = getHostFromChannel(channel);
        if (topic2Host.containsKey(pubSubData.topic) && topic2Host.get(pubSubData.topic).equals(host)) {
            // Entry in map exists for the topic but it is the same as the
            // current host. In this case there is nothing to do.
            return;
        }

        // Store the relevant mappings for this topic and host combination.
        if (logger.isDebugEnabled())
            logger.debug("Storing info for topic: " + pubSubData.topic.toStringUtf8() + ", old host: "
                    + topic2Host.get(pubSubData.topic) + ", new host: " + host);
        topic2Host.put(pubSubData.topic, host);
        if (host2Topics.containsKey(host)) {
            host2Topics.get(host).add(pubSubData.topic);
        } else {
            LinkedList<ByteString> topicsList = new LinkedList<ByteString>();
            topicsList.add(pubSubData.topic);
            host2Topics.put(host, topicsList);
        }
    }

    /**
     * Helper static method to get the String Hostname:Port from a netty
     * Channel. Assumption is that the netty Channel was originally created with
     * an InetSocketAddress. This is true with the Hedwig netty implementation.
     * 
     * @param channel
     *            Netty channel to extract the hostname and port from.
     * @return String representation of the Hostname:Port from the Netty Channel
     */
    public static InetSocketAddress getHostFromChannel(Channel channel) {
        return (InetSocketAddress) channel.getRemoteAddress();
    }

    /**
     * Helper static method to get the ResponseHandler instance from a Channel
     * via the ChannelPipeline it is associated with. The assumption is that the
     * last ChannelHandler tied to the ChannelPipeline is the ResponseHandler.
     * 
     * @param channel
     *            Channel we are retrieving the ResponseHandler instance for
     * @return ResponseHandler Instance tied to the Channel's Pipeline
     */
    public static ResponseHandler getResponseHandlerFromChannel(Channel channel) {
        return (ResponseHandler) channel.getPipeline().getLast();
    }

    // Public getter for entries in the topic2Host Map.
    public InetSocketAddress getHostForTopic(ByteString topic) {
        return topic2Host.get(topic);
    }

    // If a server host goes down or the channel to it gets disconnected,
    // we want to clear out all relevant cached information. We'll
    // need to remove all of the topic mappings that the host was
    // responsible for.
    public void clearAllTopicsForHost(InetSocketAddress host) {
        if (logger.isDebugEnabled())
            logger.debug("Clearing all topics for host: " + host);
        // For each of the topics that the host was responsible for,
        // remove it from the topic2Host mapping.
        if (host2Topics.containsKey(host)) {
            for (ByteString topic : host2Topics.get(host)) {
                if (logger.isDebugEnabled())
                    logger.debug("Removing mapping for topic: " + topic.toStringUtf8() + " from host: " + host);
                topic2Host.remove(topic);
            }
            // Now it is safe to remove the host2Topics mapping entry.
            host2Topics.remove(host);
        }
    }

    // Public getter to see if the client has been stopped.
    public boolean hasStopped() {
        return isStopped;
    }

    // Public getter to get the client's Timer object.
    // This is so we can reuse this and not have to create multiple Timer
    // objects.
    public Timer getClientTimer() {
        return clientTimer;
    }

}
