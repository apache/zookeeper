/*
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

package org.apache.zookeeper.server.instrument;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.zookeeper.common.NettyUtils;

/**
 * Simple TraceLoggerServer that is used in unit tests but can also
 * be run standalone when debugging/testing the {@link org.apache.zookeeper.server.instrument.TraceLogger}.
 */
public final class TraceLoggerServer {
    private static final AtomicReference<ByteBufAllocator> TEST_ALLOCATOR =
            new AtomicReference<>(null);

    // Accessible for testing
    public static class Message {
        LinkedHashMap<String, String> vals = new LinkedHashMap<>();

        void clear() {
            vals.clear();
        }
    }

    private class TraceLoggerServerHandler extends SimpleChannelInboundHandler<String> {
        String key;
        Message message = new Message();
        boolean header = true;
        String destination;

        void handleHeader(Message message) {
            if (!message.vals.get("header").equals("zookeeper-trace")) {
                throw new RuntimeException("Invalid header");
            } else {
                report(message);
            }
            header = false;
            destination = message.vals.get("destination");
            if (destination == null) {
                throw new RuntimeException("Invalid header: missing destination");
            }
            if (allMessages != null) {
                allMessages.put(destination, new ConcurrentLinkedQueue<>());
            }
        }

        void report(Message message) {
            if (print) {
                StringBuilder sb = new StringBuilder();
                sb.append("[");
                for (Map.Entry<String, String> entry : message.vals.entrySet()) {
                    sb.append(entry.getKey()).append("=").append(entry.getValue()).append(",");
                }
                sb.append("]");
                System.out.println(sb.toString());
            }
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, String request) {
            if (request.equals("[")) {
                key = null;
                message.clear();
            } else if (request.equals("]")) {
                key = null;
                if (header) {
                    handleHeader(message);
                } else {
                    report(message);
                }
                if (allMessages != null) {
                    ConcurrentLinkedQueue<Map<String, String>> messages = allMessages.get(destination);
                    if (message.vals.isEmpty()) {
                        System.err.println("Warning: received empty message!");
                    } else {
                        messages.add(new LinkedHashMap<>(message.vals));
                    }
                }
                message.clear();
                // Send ack every message
                ctx.channel().writeAndFlush("1");
            } else {
                if (key == null) {
                    key = request;
                } else {
                    message.vals.put(key, request);
                    key = null;
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.channel().close();
        }
    }

    private final ServerBootstrap bootstrap;
    private final Channel channel;
    // messages received for each destination
    private final Map<String, ConcurrentLinkedQueue<Map<String, String>>> allMessages;
    private final boolean print;

    public TraceLoggerServer(int port, boolean capture, boolean print) {
        if (capture) {
            allMessages = new HashMap<>();
        } else {
            allMessages = null;
        }
        this.print = print;

        EventLoopGroup bossGroup = NettyUtils.newNioOrEpollEventLoopGroup(
                NettyUtils.getClientReachableLocalInetAddressCount());
        EventLoopGroup workerGroup = NettyUtils.newNioOrEpollEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NettyUtils.nioOrEpollServerSocketChannel())
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        // Decoder
                        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                                65535, 0, 2, 0, 2));
                        pipeline.addLast("decoder", new StringDecoder());
                        // Encoder
                        pipeline.addLast("frameEncoder", new LengthFieldPrepender(2));
                        pipeline.addLast("encoder", new StringEncoder());
                        // Handler
                        pipeline.addLast("handler", new TraceLoggerServerHandler());
                    }
                });
        this.bootstrap = configureBootstrapAllocator(bootstrap);
        this.bootstrap.validate();
        channel = this.bootstrap.bind(new InetSocketAddress(port)).syncUninterruptibly().channel();
    }

    private ServerBootstrap configureBootstrapAllocator(ServerBootstrap bootstrap) {
        ByteBufAllocator testAllocator = TEST_ALLOCATOR.get();
        if (testAllocator != null) {
            return bootstrap
                    .option(ChannelOption.ALLOCATOR, testAllocator)
                    .childOption(ChannelOption.ALLOCATOR, testAllocator);
        } else {
            return bootstrap;
        }
    }

    // Used in testing
    public ConcurrentLinkedQueue<Map<String, String>> getMessages(String destination) {
        if (allMessages != null) {
            return allMessages.getOrDefault(destination, new ConcurrentLinkedQueue<>());
        }
        return null;
    }

    public void shutdown() {
        channel.close().syncUninterruptibly();
        EventLoopGroup bossGroup = bootstrap.config().group();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        EventLoopGroup workerGroup = bootstrap.config().childGroup();
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    public static void main(String[] args) throws Exception {
        final int port = Integer.getInteger("zookeeper.traceLoggerPort", TraceLogger.DEFAULT_PORT);
        TraceLoggerServer server = new TraceLoggerServer(port, false, true);
        server.channel.closeFuture().sync();
    }

    /**
     * Sets the test ByteBufAllocator. This allocator will be used by all
     * future instances of this class.
     * It is not recommended to use this method outside of testing.
     * @param allocator the ByteBufAllocator to use for all netty buffer
     *                  allocations.
     */
    public static void setTestAllocator(ByteBufAllocator allocator) {
        TEST_ALLOCATOR.set(allocator);
    }

    /**
     * Clears the test ByteBufAllocator. The default allocator will be used
     * by all future instances of this class.
     * It is not recommended to use this method outside of testing.
     */
    public static void clearTestAllocator() {
        TEST_ALLOCATOR.set(null);
    }
}
