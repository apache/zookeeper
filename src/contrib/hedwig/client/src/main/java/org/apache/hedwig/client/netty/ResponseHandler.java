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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.ssl.SslHandler;

import com.google.protobuf.ByteString;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.client.data.PubSubData;
import org.apache.hedwig.client.exceptions.ServerRedirectLoopException;
import org.apache.hedwig.client.exceptions.TooManyServerRedirectsException;
import org.apache.hedwig.client.handlers.PublishResponseHandler;
import org.apache.hedwig.client.handlers.SubscribeReconnectCallback;
import org.apache.hedwig.client.handlers.SubscribeResponseHandler;
import org.apache.hedwig.client.handlers.UnsubscribeResponseHandler;
import org.apache.hedwig.exceptions.PubSubException.ServiceDownException;
import org.apache.hedwig.exceptions.PubSubException.UncertainStateException;
import org.apache.hedwig.protocol.PubSubProtocol.OperationType;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubResponse;
import org.apache.hedwig.protocol.PubSubProtocol.StatusCode;
import org.apache.hedwig.util.HedwigSocketAddress;

@ChannelPipelineCoverage("all")
public class ResponseHandler extends SimpleChannelHandler {

    private static Logger logger = Logger.getLogger(ResponseHandler.class);

    // Concurrent Map to store for each async PubSub request, the txn ID
    // and the corresponding PubSub call's data which stores the VoidCallback to
    // invoke when we receive a PubSub ack response from the server.
    // This is specific to this instance of the ResponseHandler which is
    // tied to a specific netty Channel Pipeline.
    protected final ConcurrentMap<Long, PubSubData> txn2PubSubData = new ConcurrentHashMap<Long, PubSubData>();

    // Boolean indicating if we closed the channel this ResponseHandler is
    // attached to explicitly or not. If so, we do not need to do the
    // channel disconnected logic here.
    public boolean channelClosedExplicitly = false;

    private final HedwigClient client;
    private final HedwigPublisher pub;
    private final HedwigSubscriber sub;
    private final ClientConfiguration cfg;

    private final PublishResponseHandler pubHandler;
    private final SubscribeResponseHandler subHandler;
    private final UnsubscribeResponseHandler unsubHandler;

    public ResponseHandler(HedwigClient client) {
        this.client = client;
        this.sub = client.getSubscriber();
        this.pub = client.getPublisher();
        this.cfg = client.getConfiguration();
        this.pubHandler = new PublishResponseHandler(this);
        this.subHandler = new SubscribeResponseHandler(this);
        this.unsubHandler = new UnsubscribeResponseHandler(this);
    }

    // Public getters needed for the private members
    public HedwigClient getClient() {
        return client;
    }

    public HedwigSubscriber getSubscriber() {
        return sub;
    }

    public ClientConfiguration getConfiguration() {
        return cfg;
    }

    public SubscribeResponseHandler getSubscribeResponseHandler() {
        return subHandler;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        // If the Message is not a PubSubResponse, just send it upstream and let
        // something else handle it.
        if (!(e.getMessage() instanceof PubSubResponse)) {
            ctx.sendUpstream(e);
        }
        // Retrieve the PubSubResponse from the Message that was sent by the
        // server.
        PubSubResponse response = (PubSubResponse) e.getMessage();
        if (logger.isDebugEnabled())
            logger.debug("Response received from host: " + HedwigClient.getHostFromChannel(ctx.getChannel())
                    + ", response: " + response);

        // Determine if this PubSubResponse is an ack response for a PubSub
        // Request or if it is a message being pushed to the client subscriber.
        if (response.hasMessage()) {
            // Subscribed messages being pushed to the client so handle/consume
            // it and return.
            subHandler.handleSubscribeMessage(response);
            return;
        }

        // Response is an ack to a prior PubSubRequest so first retrieve the
        // PubSub data for this txn.
        PubSubData pubSubData = txn2PubSubData.containsKey(response.getTxnId()) ? txn2PubSubData.get(response
                .getTxnId()) : null;
        // Validate that the PubSub data for this txn is stored. If not, just
        // log an error message and return since we don't know how to handle
        // this.
        if (pubSubData == null) {
            logger.error("PubSub Data was not found for PubSubResponse: " + response);
            return;
        }

        // Now that we've retrieved the PubSubData for this specific Txn ID, we
        // can remove it from the Map.
        txn2PubSubData.remove(response.getTxnId());

        // Store the topic2Host mapping if this wasn't a server redirect. We'll
        // assume that if the server was able to have an open Channel connection
        // to the client, and responded with an ack message other than the
        // NOT_RESPONSIBLE_FOR_TOPIC one, it is the correct topic master.
        if (!response.getStatusCode().equals(StatusCode.NOT_RESPONSIBLE_FOR_TOPIC)) {
            client.storeTopic2HostMapping(pubSubData, ctx.getChannel());
        }

        // Depending on the operation type, call the appropriate handler.
        switch (pubSubData.operationType) {
        case PUBLISH:
            pubHandler.handlePublishResponse(response, pubSubData, ctx.getChannel());
            break;
        case SUBSCRIBE:
            subHandler.handleSubscribeResponse(response, pubSubData, ctx.getChannel());
            break;
        case UNSUBSCRIBE:
            unsubHandler.handleUnsubscribeResponse(response, pubSubData, ctx.getChannel());
            break;
        default:
            // The above are the only expected PubSubResponse messages received
            // from the server for the various client side requests made.
            logger.error("Response received from server is for an unhandled operation type, txnId: "
                    + response.getTxnId() + ", operationType: " + pubSubData.operationType);
        }
    }

    /**
     * Logic to repost a PubSubRequest when the server responds with a redirect
     * indicating they are not the topic master.
     * 
     * @param response
     *            PubSubResponse from the server for the redirect
     * @param pubSubData
     *            PubSubData for the original PubSubRequest made
     * @param channel
     *            Channel Channel we used to make the original PubSubRequest
     * @throws Exception
     *             Throws an exception if there was an error in doing the
     *             redirect repost of the PubSubRequest
     */
    public void handleRedirectResponse(PubSubResponse response, PubSubData pubSubData, Channel channel)
            throws Exception {
        if (logger.isDebugEnabled())
            logger.debug("Handling a redirect from host: " + HedwigClient.getHostFromChannel(channel) + ", response: "
                    + response + ", pubSubData: " + pubSubData);
        // In this case, the PubSub request was done to a server that is not
        // responsible for the topic. First make sure that we haven't
        // exceeded the maximum number of server redirects.
        int curNumServerRedirects = (pubSubData.triedServers == null) ? 0 : pubSubData.triedServers.size();
        if (curNumServerRedirects >= cfg.getMaximumServerRedirects()) {
            // We've already exceeded the maximum number of server redirects
            // so consider this as an error condition for the client.
            // Invoke the operationFailed callback and just return.
            if (logger.isDebugEnabled())
                logger.debug("Exceeded the number of server redirects (" + curNumServerRedirects + ") so error out.");
            pubSubData.callback.operationFailed(pubSubData.context, new ServiceDownException(
                    new TooManyServerRedirectsException("Already reached max number of redirects: "
                            + curNumServerRedirects)));
            return;
        }

        // We will redirect and try to connect to the correct server
        // stored in the StatusMsg of the response. First store the
        // server that we sent the PubSub request to for the topic.
        ByteString triedServer = ByteString.copyFromUtf8(HedwigSocketAddress.sockAddrStr(HedwigClient
                .getHostFromChannel(channel)));
        if (pubSubData.triedServers == null)
            pubSubData.triedServers = new LinkedList<ByteString>();
        pubSubData.shouldClaim = true;
        pubSubData.triedServers.add(triedServer);

        // Now get the redirected server host (expected format is
        // Hostname:Port:SSLPort) from the server's response message. If one is
        // not given for some reason, then redirect to the default server
        // host/VIP to repost the request.
        String statusMsg = response.getStatusMsg();
        InetSocketAddress redirectedHost;
        if (statusMsg != null && statusMsg.length() > 0) {
            if (cfg.isSSLEnabled()) {
                redirectedHost = new HedwigSocketAddress(statusMsg).getSSLSocketAddress();
            } else {
                redirectedHost = new HedwigSocketAddress(statusMsg).getSocketAddress();
            }
        } else {
            redirectedHost = cfg.getDefaultServerHost();
        }

        // Make sure the redirected server is not one we've already attempted
        // already before in this PubSub request.
        if (pubSubData.triedServers.contains(ByteString.copyFromUtf8(HedwigSocketAddress.sockAddrStr(redirectedHost)))) {
            logger.error("We've already sent this PubSubRequest before to redirectedHost: " + redirectedHost
                    + ", pubSubData: " + pubSubData);
            pubSubData.callback.operationFailed(pubSubData.context, new ServiceDownException(
                    new ServerRedirectLoopException("Already made the request before to redirected host: "
                            + redirectedHost)));
            return;
        }

        // Check if we already have a Channel open to the redirected server
        // host.
        boolean redirectedHostChannelExists = pub.host2Channel.containsKey(redirectedHost) ? true : false;
        if (pubSubData.operationType.equals(OperationType.SUBSCRIBE) || !redirectedHostChannelExists) {
            // We don't have an existing channel to the redirected host OR this
            // is a redirected Subscribe request. For Subscribe requests, we
            // always want to create a new unique Channel connection to the
            // topic master server for the TopicSubscriber.
            client.doConnect(pubSubData, redirectedHost);
        } else {
            // For Publish and Unsubscribe requests, we can just post the
            // request again directly on the existing cached redirected host
            // channel.
            if (pubSubData.operationType.equals(OperationType.PUBLISH)) {
                pub.doPublish(pubSubData, pub.host2Channel.get(redirectedHost));
            } else if (pubSubData.operationType.equals(OperationType.UNSUBSCRIBE)) {
                sub.doSubUnsub(pubSubData, pub.host2Channel.get(redirectedHost));
            }
        }
    }

    // Logic to deal with what happens when a Channel to a server host is
    // disconnected.
    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // If this channel was closed explicitly by the client code,
        // we do not need to do any of this logic. This could happen
        // for redundant Publish channels created or redirected subscribe
        // channels that are not used anymore or when we shutdown the
        // client and manually close all of the open channels.
        // Also don't do any of the disconnect logic if the client has stopped.
        if (channelClosedExplicitly || client.hasStopped())
            return;

        // Make sure the host retrieved is not null as there could be some weird
        // channel disconnect events happening during a client shutdown.
        // If it is, just return as there shouldn't be anything we need to do.
        InetSocketAddress host = HedwigClient.getHostFromChannel(ctx.getChannel());
        logger.warn("Channel was disconnected to host: " + host);
        if (host == null)
            return;

        // If this Channel was used for Publish and Unsubscribe flows, just
        // remove it from the HewdigPublisher's host2Channel map. We will
        // re-establish a Channel connection to that server when the next
        // publish/unsubscribe request to a topic that the server owns occurs.
        PubSubData origSubData = subHandler.getOrigSubData();

        // Now determine what type of operation this channel was used for.
        if (origSubData == null) {
            // Only remove the Channel from the mapping if this current
            // disconnected channel is the same as the cached entry.
            // Due to race concurrency situations, it is possible to
            // create multiple channels to the same host for publish
            // and unsubscribe requests.
            if (pub.host2Channel.containsKey(host) && pub.host2Channel.get(host).equals(ctx.getChannel())) {
                if (logger.isDebugEnabled())
                    logger.debug("Disconnected channel for host: " + host
                            + " was for Publish/Unsubscribe requests so remove all references to it.");
                pub.host2Channel.remove(host);
                client.clearAllTopicsForHost(host);
            }
        } else {
            // Subscribe channel disconnected so first close and clear all
            // cached Channel data set up for this topic subscription.
            sub.closeSubscription(origSubData.topic, origSubData.subscriberId);
            client.clearAllTopicsForHost(host);
            // Since the connection to the server host that was responsible
            // for the topic died, we are not sure about the state of that
            // server. Resend the original subscribe request data to the default
            // server host/VIP. Also clear out all of the servers we've
            // contacted or attempted to from this request as we are starting a
            // "fresh" subscribe request.
            origSubData.clearServersList();
            // Set a new type of VoidCallback for this async call. We need this
            // hook so after the subscribe reconnect has completed, delivery for
            // that topic subscriber should also be restarted (if it was that
            // case before the channel disconnect).
            origSubData.callback = new SubscribeReconnectCallback(origSubData, client, subHandler.getMessageHandler());
            origSubData.context = null;
            if (logger.isDebugEnabled())
                logger.debug("Disconnected subscribe channel so reconnect with origSubData: " + origSubData);
            client.doConnect(origSubData, cfg.getDefaultServerHost());
        }

        // Finally, all of the PubSubRequests that are still waiting for an ack
        // response from the server need to be removed and timed out. Invoke the
        // operationFailed callbacks on all of them. Use the
        // UncertainStateException since the server did receive the request but
        // we're not sure of the state of the request since the ack response was
        // never received.
        for (PubSubData pubSubData : txn2PubSubData.values()) {
            if (logger.isDebugEnabled())
                logger.debug("Channel disconnected so invoking the operationFailed callback for pubSubData: "
                        + pubSubData);
            pubSubData.callback.operationFailed(pubSubData.context, new UncertainStateException(
                    "Server ack response never received before server connection disconnected!"));
        }
        txn2PubSubData.clear();
    }

    // Logic to deal with what happens when a Channel to a server host is
    // connected. This is needed if the client is using an SSL port to
    // communicate with the server. If so, we need to do the SSL handshake here
    // when the channel is first connected.
    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // No need to initiate the SSL handshake if we are closing this channel
        // explicitly or the client has been stopped.
        if (cfg.isSSLEnabled() && !channelClosedExplicitly && !client.hasStopped()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Initiating the SSL handshake");
            }
            ctx.getPipeline().get(SslHandler.class).handshake(e.getChannel());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        e.getCause().printStackTrace();
        e.getChannel().close();
    }

}
