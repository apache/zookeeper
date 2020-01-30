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
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
import org.apache.zookeeper.common.NettyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trace Server accepts connection from {@link TraceLogger} and
 * reads traces through stateful connection and writes traces
 * into slf4j log by default.
 *
 * Upon connection from TraceLogger, it expects the first message
 * to be a header containing trace metadata which is then stored
 * inside {@link TraceServerHandler}. The connection is dropped
 * immediately if header is invalid. Subsequent traces are persisted
 * through slf4j but can be overridden to other tracing system.
 *
 * Trace Server should be set up on separate hardware from zookeeper
 * server if they are both writing to the same persistence storage,
 * otherwise it could co-exist on the same hardware.
 *
 */
public class TraceServer {
    private final int port;
    private ServerBootstrap bootstrap;
    private Channel channel;
    private static final Logger LOG = LoggerFactory.getLogger(TraceServer.class);

    protected class TraceServerHandler extends SimpleChannelInboundHandler<String> {
        private String key;
        private Map<String, String> message = new HashMap<>();

        private boolean readHeader = true;

        private String hostname;
        private String ensembleName;
        private String destination;
        private List<TraceField> schema = new ArrayList<>();
        private int count;
        private int ackWindowSize = TraceLogger.DEFAULT_WINDOW_SIZE;

        private boolean handleHeader(Map<String, String> message) {
            if (!message.get("header").equals("zookeeper-trace")) {
                LOG.error("Invalid trace header {}", message);
                return false;
            }

            hostname = message.getOrDefault("hostname", "");
            ensembleName = message.getOrDefault("ensemble", "");

            destination = message.getOrDefault("destination", "");
            if (destination == null) {
                LOG.error("Missing destination in trace header: {}", message);
                return false;
            }

            String windowSize = message.getOrDefault("window", "");
            if (StringUtils.isNotEmpty(windowSize)) {
                ackWindowSize = Math.min(ackWindowSize, Integer.parseInt(windowSize));
            }

            String schemaStr = message.get("schema");
            if (!parseSchema(schemaStr)) {
                return false;
            }

            readHeader = false;
            logHeader(hostname, ensembleName, destination, schema);
            return true;
        }

        private boolean parseSchema(String schemaStr) {
            try {
                String[] fields = schemaStr.split(",");
                for (String field : fields) {
                    if (field.length() > 1) {
                        schema.add(TraceField.get(field.substring(1)));
                    } else {
                        LOG.error("Unrecognizable trace schema fields: {}", schemaStr);
                        return false;
                    }
                }
                return true;
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
                LOG.error("Invalid trace schema: {}", schemaStr, e);
                return false;
            }
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, String trace) {
            // reading trace Message
            if (trace.equals("[")) {
                messageStarted();
            } else if (trace.equals("]")) {
                messageEnded(ctx);
            } else {
                if (key == null) {
                    key = trace;
                } else {
                    message.put(key, trace);
                    key = null;
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.channel().close();
        }

        private void messageStarted() {
            key = null;
            message.clear();
        }

        private void messageEnded(ChannelHandlerContext ctx) {
            // message ended
            key = null;
            if (readHeader) {
                if (!handleHeader(message)) {
                    ctx.close();
                    return;
                }
            } else {
                logTrace(hostname, ensembleName, destination, schema, message);
            }

            message.clear();
            count++;

            // // send back received count as acknowledgement and reset counter
            if (count >= ackWindowSize) {
                ctx.channel().writeAndFlush(Integer.toString(count));
                count = 0;
            }
        }
    }

    public TraceServer(int port) {
        this.port = port;
    }

    public void startup() {
        EventLoopGroup bossGroup = NettyUtils.newNioOrEpollEventLoopGroup(
                NettyUtils.getClientReachableLocalInetAddressCount());
        EventLoopGroup workerGroup = NettyUtils.newNioOrEpollEventLoopGroup();
        this.bootstrap = new ServerBootstrap()
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
                        pipeline.addLast("handler", new TraceServerHandler());
                    }
                });
        this.bootstrap.validate();
        channel = this.bootstrap.bind(new InetSocketAddress(port)).syncUninterruptibly().channel();
        LOG.info("Trace server started listening on port " + port);
    }

    protected String getType(char traceFieldType) {
        switch (traceFieldType) {
            case TraceField.Types.INTEGER:
                return "INT";
            case TraceField.Types.STRING:
                return "STRING";
            case TraceField.Types.STRING_ARRAY:
                return "STRING ARRAY";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Write trace header which is sent from trace logger right after connection establishment.
     *
     * @param hostname
     * @param ensembleName
     * @param destination
     * @param schema
     */
    protected void logHeader(final String hostname, final String ensembleName, final String destination,
                             List<TraceField> schema) {
        LOG.info("Established connection with {} (ensemble={}, destination={})", hostname, ensembleName, destination);
        for (TraceField field: schema) {
            LOG.info("Schema field: name={} type={}", field.getName(), getType(field.getType()));
        }
    }

    /**
     * Write trace message. Current implementation writes to SLF4J and
     * can be overridden to write to distributed tracing.
     *
     * @param schema
     * @param message
     */
    protected void logTrace(final String hostname, final String ensembleName, final String destination,
                            List<TraceField> schema, Map<String, String> message) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(hostname).append("]");
        sb.append("[").append(ensembleName).append("]");
        sb.append("[").append(destination).append("] ");
        for (Map.Entry<String, String> entry : message.entrySet()) {
            int fieldIndex = Integer.parseInt(entry.getKey());
            sb.append(schema.get(fieldIndex).getName()).append("=").append(entry.getValue()).append(" ");
        }
        LOG.info(sb.toString());
    }

    /**
     * Shut down trace server.
     */
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

    public static void main(String[] args) {
        final int port = Integer.getInteger(TraceLogger.TRACE_LOGGER_PORT, TraceLogger.DEFAULT_PORT);
        TraceServer server = new TraceServer(port);
        try {
            server.startup();
        } finally {
            server.shutdown();
        }
    }
}
