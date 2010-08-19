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
package org.apache.hedwig.server.netty;

import java.io.IOException;
import java.util.Map;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.frame.CorruptedFrameException;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.ssl.SslHandler;

import org.apache.hedwig.exceptions.PubSubException.MalformedRequestException;
import org.apache.hedwig.protocol.PubSubProtocol;
import org.apache.hedwig.protocol.PubSubProtocol.OperationType;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubResponse;
import org.apache.hedwig.protoextensions.PubSubResponseUtils;
import org.apache.hedwig.server.handlers.ChannelDisconnectListener;
import org.apache.hedwig.server.handlers.Handler;

@ChannelPipelineCoverage("all")
public class UmbrellaHandler extends SimpleChannelHandler {
    static Logger logger = Logger.getLogger(UmbrellaHandler.class);

    private Map<OperationType, Handler> handlers;
    private ChannelGroup allChannels;
    private ChannelDisconnectListener subscribeHandler;
    private boolean isSSLEnabled = false;

    public UmbrellaHandler(ChannelGroup allChannels, Map<OperationType, Handler> handlers,
            boolean isSSLEnabled) {
        this.allChannels = allChannels;
        this.isSSLEnabled = isSSLEnabled;
        this.handlers = handlers;
        subscribeHandler = (ChannelDisconnectListener) handlers.get(OperationType.SUBSCRIBE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        Throwable throwable = e.getCause();

        // Add here if there are more exceptions we need to be able to tolerate.
        // 1. IOException may be thrown when a channel is forcefully closed by
        // the other end, or by the ProtobufDecoder when an invalid protobuf is
        // received
        // 2. TooLongFrameException is thrown by the LengthBasedDecoder if it
        // receives a packet that is too big
        // 3. CorruptedFramException is thrown by the LengthBasedDecoder when
        // the length is negative etc.
        if (throwable instanceof IOException || throwable instanceof TooLongFrameException
                || throwable instanceof CorruptedFrameException) {
            e.getChannel().close();
            if (logger.isDebugEnabled()) {
                logger.debug("Uncaught exception", throwable);
            }
        } else {
            // call our uncaught exception handler, which might decide to
            // shutdown the system
            Thread thread = Thread.currentThread();
            thread.getUncaughtExceptionHandler().uncaughtException(thread, throwable);
        }

    }

    @Override
    public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // If SSL is NOT enabled, then we can add this channel to the
        // ChannelGroup. Otherwise, that is done when the channel is connected
        // and the SSL handshake has completed successfully.
        if (!isSSLEnabled) {
            allChannels.add(ctx.getChannel());
        }
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        if (isSSLEnabled) {
            ctx.getPipeline().get(SslHandler.class).handshake(e.getChannel()).addListener(new ChannelFutureListener() {
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("SSL handshake has completed successfully!");
                        }
                        allChannels.add(future.getChannel());
                    } else {
                        future.getChannel().close();
                    }
                }
            });
        }
    }

    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        Channel channel = ctx.getChannel();
        // subscribe handler needs to know about channel disconnects
        subscribeHandler.channelDisconnected(channel);
        channel.close();
    }

    public static void sendErrorResponseToMalformedRequest(Channel channel, long txnId, String msg) {
        if (logger.isDebugEnabled()) {
            logger.debug("Malformed request from " + channel.getRemoteAddress() + " msg, = " + msg);
        }
        MalformedRequestException mre = new MalformedRequestException(msg);
        PubSubResponse response = PubSubResponseUtils.getResponseForException(mre, txnId);
        channel.write(response);
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {

        if (!(e.getMessage() instanceof PubSubProtocol.PubSubRequest)) {
            ctx.sendUpstream(e);
            return;
        }

        PubSubProtocol.PubSubRequest request = (PubSubProtocol.PubSubRequest) e.getMessage();

        Handler handler = handlers.get(request.getType());
        Channel channel = ctx.getChannel();
        long txnId = request.getTxnId();

        if (handler == null) {
            sendErrorResponseToMalformedRequest(channel, txnId, "Request type " + request.getType().getNumber()
                    + " unknown");
            return;
        }

        handler.handleRequest(request, channel);
    }

}
