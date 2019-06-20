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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.zookeeper.common.NettyUtils;
import org.apache.zookeeper.server.ServerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The TraceLogger emits zookeeper trace messages to a configured TCP host/port.
 * This can be used to send messages to an external daemon for monitoring/auditing
 * purposes.
 *
 * The TraceLogger is implemented as single-threaded actor which implements an
 * event-driven state machine. The logger starts up and tries to connect to the
 * configured TCP host/port, retrying periodically on failure. Once connected,
 * the logger will send an initial handshake message:
 *
 *   (header = "zookeeper-trace",
 *    version = "1",
 *    hostname = "hostname",                     // server host name
 *    ensemble = "ensembleName",                 // ensemble name
 *    destination = "zookeeper_server_trace"     // unique label for destination
 *    window = windowSize,                       // acknowledge window size
 *    schema = "schema")                         // comma separated trace field name
 *                                               // prefixed by data type
 *
 * Then, the logger moves into steady-state where it emits trace messages as they
 * occur and continues to do so as long as the TCP connection stays open.
 *
 * The logger emits messages using a simple encoding that is based on length-prefixed
 * strings. These are generated via Netty's {@link LengthFieldPrepender} using a 2-byte
 * header.
 *
 * For example, the string "Hello World" would be sent as:
 *   (0x000b, "Hello World")
 *
 * This encoding allows arbitrary strings to be sent without worrying about escaping
 * special delimiter characters (eg. newlines, tabs, etc).
 *
 * Actual messages are sent as a set of key/value pairs which are sent as a sequence
 * of length-prefixed strings surrounded by message begin ("[") and message end ("]")
 * indicators. Keys are always sent as trace field index while values are encoded as strings.
 *
 * For example, assume we have the following message:
 *   (stage = "FINAL", zxid = 1234, session_id = 2)
 *
 * The three trace fields have the following indices:
 *
 *   zxid = 1, session_id = 2, stage = 8
 *
 * This will be sent as the following series of length-prefixed strings:
 *
 *   (0x0001, "[")                // Message begin
 *   (0x0001, "8")                // key = "stage"
 *   (0x0005, "FINAL")            // value = "FINAL"
 *   (0x0001, "1")                // key = "zxid"
 *   (0x0004, "1234")             // value = 1234
 *   (0x0001, "3")                // key = "session_id"
 *   (0x0001, "2")                // value = 2
 *   (0x0001, "]")                // Message end
 *
 * The maximum trace field value size is 64K and will be truncated automatically if exceeded.
 *
 * Message are submitted to the logger asynchronously to limit impact on normal
 * ZooKeeper performance. The logger has a configured maximum number of outstanding
 * requests, if this limit is hit additional messages are discarded rather than
 * submitted to the logger. This prevents unbounded queue growth if ZooKeeper is
 * submitting messages faster than the logger can respond.
 *
 * The logger also has a window of maximum messages sent over TCP at a time. The server
 * at the other end of the TCP connection is responsible for sending back periodic ACKs,
 * sent as as length-prefixed string encoded integer, to acknowledge received messages.
 * The logger maintains a count of sent-but-not-acked messages and will continue to send
 * traces if the count is less than the window size. (assuming the external system ACKs
 * only after successfully handling the message).
 *
 * See {@link org.apache.zookeeper.server.instrument.TraceField}
 * See {@link org.apache.zookeeper.server.ZooTrace}
 */
public class TraceLogger {
    private static final Logger LOG = LoggerFactory.getLogger(TraceLogger.class);

    public static final int DEFAULT_PORT = 2200;
    public static final int DEFAULT_WINDOW_SIZE = 1000;
    public static final int DEFAULT_BUFFER_SIZE = 100000;
    public static final String DESTINATION_SERVER_TRACE = "zookeeper_server_trace";
    public static final String TRACE_LOGGER_HOST = "zookeeper.traceLoggerHost";
    public static final String TRACE_LOGGER_PORT = "zookeeper.traceLoggerPort";
    public static final String TRACE_LOGGER_WINDOW_SIZE = "zookeeper.traceLoggerWindowSize";
    public static final String TRACE_LOGGER_MAX_OUTSTANDING_TRACE = "zookeeper.traceLoggerMaxOutstanding";

    private final String[] loggerHosts = System.getProperty(TRACE_LOGGER_HOST, "127.0.0.1").split(",");
    private final int loggerPort = Integer.getInteger(TRACE_LOGGER_PORT, DEFAULT_PORT);

    // Maximum number of sent-but-not-acked messages. After hitting this limit, the logger will stop
    // sending messages until receiving an ack.
    private final int windowSize = Integer.getInteger(TRACE_LOGGER_WINDOW_SIZE, DEFAULT_WINDOW_SIZE);

    // Maximum number of unsent messages submitted to the logger. When hit, new messages will be dropped
    // rather than submitted to the logger.
    private static volatile long maxOutstanding = Integer.getInteger(TRACE_LOGGER_MAX_OUTSTANDING_TRACE, DEFAULT_BUFFER_SIZE);

    private static long maxMessageBufferSize = 1000000;
    private final long messageBufferSize;

    private static final String messageBegin = "[";
    private static final String messageEnd = "]";
    private static final String messageBeginEscaped = "\\[";
    private static final String messageEndEscaped = "\\]";
    private static final int minMessagesToFlush = 10;

    private static final AtomicReference<ByteBufAllocator> TEST_ALLOCATOR =
            new AtomicReference<>(null);

    private final Bootstrap bootstrap;
    private final TraceChannelInitializer traceChannelInitializer;
    private ChannelFuture lastConnect = null;
    private ChannelFuture lastWrite = null;
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    private final ArrayDeque<Message> messages = new ArrayDeque<>();
    private final AtomicLong outstandingMessages = new AtomicLong();
    private final AtomicInteger sentMessagesNoAck = new AtomicInteger();
    private final String ensembleName;
    // trace destination
    private final String destination;

    private Channel connectedChannel;
    private Random rng = new Random(System.nanoTime());

    private enum State {
        DISCONNECTED,
        CONNECTING,
        HANDSHAKE,
        SENDING
    }

    private AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);

    public TraceLogger(String ensembleName) {
        this(ensembleName, DESTINATION_SERVER_TRACE, DEFAULT_BUFFER_SIZE);
    }

    public TraceLogger(String ensembleName, String destination) {
        this(ensembleName, destination, DEFAULT_BUFFER_SIZE);
    }

    public TraceLogger(String ensembleName, String destination, long messageBufferSize) {
        this.ensembleName = ensembleName;
        this.destination = destination;
        this.messageBufferSize = Math.min(messageBufferSize, maxMessageBufferSize);

        traceChannelInitializer = new TraceChannelInitializer();
        EventLoopGroup group = NettyUtils.newNioOrEpollEventLoopGroup(1 /* nThreads */);
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NettyUtils.nioOrEpollSocketChannel())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_RCVBUF, 1048576)
                .option(ChannelOption.SO_SNDBUF, 1048576)
                // channel buffer is writable up to 640KB
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(320 * 1024, 640 * 1024))
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(traceChannelInitializer);
        this.bootstrap = configureBootstrapAllocator(bootstrap);
        this.bootstrap.validate();
        reconnect();
        LOG.info("Trace logger started connecting to server port {}", loggerPort);
    }

    private Bootstrap configureBootstrapAllocator(Bootstrap bootstrap) {
        ByteBufAllocator testAllocator = TEST_ALLOCATOR.get();
        if (testAllocator != null) {
            return bootstrap.option(ChannelOption.ALLOCATOR, testAllocator);
        } else {
            return bootstrap;
        }
    }

    public static long getMaxOutstanding() {
        return maxOutstanding;
    }

    public static void setMaxOutstanding(long value) {
        if (value > 0) {
            maxOutstanding = value;
        }
    }

    /**
     * Trace Message
     */
    public class Message {
        private static final int maxSize = (1 << 16) - 36;
        protected ArrayList<String> values = new ArrayList<>();
        protected boolean sending = true;

        /**
         * @param key  trace field name
         * @param val  trace field value
         * @return  current trace message
         */
        public Message add(String key, String val) {
            values.add(key);
            values.add(limitSize(val));
            return this;
        }

        /**
         * @param key  trace field name
         * @param num  trace field value
         * @return  current trace message
         */
        public Message add(String key, long num) {
            values.add(key);
            values.add(Long.toString(num));
            return this;
        }

        /**
         * @param field  trace field
         * @param val    trace field value
         * @return  current trace message
         */
        public Message add(TraceField field, String val) {
            TraceFieldFilter f = field.getFilter();
            if (sending && f != null && f.accept(val)) {
                values.add(Integer.toString(field.ordinal()));
                values.add(limitSize(val));
            } else {
                sending = false;
            }
            return this;
        }

        /**
         * @param field  trace field
         * @param num    trace field value
         * @return  current trace message
         */
        public Message add(TraceField field, long num) {
            TraceFieldFilter f = field.getFilter();
            if (sending && f != null && f.accept(num)) {
                values.add(Integer.toString(field.ordinal()));
                values.add(Long.toString(num));
            } else {
                sending = false;
            }
            return this;
        }

        /**
         * @param field    trace field
         * @param boolVal  trace field value
         * @return  current trace message
         */
        public Message add(TraceField field, boolean boolVal) {
            return add(field, boolVal ? 1 : 0);
        }

        /**
         * @param field   trace field
         * @param values  trace field value
         * @return  current trace message
         */
        public Message add(TraceField field, List<String> values) {
            if (sending && values != null) {
                this.values.add(Integer.toString(field.ordinal()));

                StringBuilder sb = new StringBuilder(Math.min(values.size(), maxSize >> 4) << 4);
                int capacity = maxSize;
                for (String s : values) {
                    if (s == null) {
                        continue;
                    }
                    if (capacity > s.length()) {
                        sb.append(s).append(",");
                        capacity = maxSize - sb.length();
                    } else {
                        sb.append(s, 0, capacity);
                        break;
                    }
                }
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
                    sb.deleteCharAt(sb.length() - 1);
                }
                this.values.add(sb.toString());
            }
            return this;
        }

        private String limitSize(String s) {
            if (s != null && s.length() >= maxSize) {
                return s.substring(0, maxSize);
            }
            return s;
        }

        /**
         * Submit trace message asynchronously.
         */
        public void send() {
            try {
                if (sending) {
                    sendMsg(this);
                }
            } catch (Exception e) {
                LOG.debug("Error in send", e);
            }
        }
    }

    /**
     * @return  true if logger is in connected state and ready to send messages.
     */
    public boolean isReady() {
        return state.get() == State.SENDING;
    }

    /**
     * @return  new trace message.
     */
    public Message msg() {
        return new Message();
    }

    private boolean maybeSubmit(Runnable runnable) {
        if (!executor.isShutdown()) {
            try {
                executor.submit(runnable);
            } catch (RejectedExecutionException ree) {
                return false;
            }
            return true;
        }
        return false;
    }

    private void flush() {
        if (connectedChannel != null) {
            connectedChannel.flush();
        }
    }

    /**
     * shutdown trace logger.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(500, TimeUnit.MILLISECONDS);
            if (executor.isTerminating()) {
                executor.shutdownNow();
                executor.awaitTermination(Integer.MAX_VALUE, TimeUnit.MINUTES);
            }
        } catch (InterruptedException ignore) {
        }
        if (lastWrite != null) {
            lastWrite.cancel(false);
            lastWrite.awaitUninterruptibly();
        }
        if (lastConnect != null) {
            lastConnect.cancel(false);
            lastConnect.awaitUninterruptibly();
            Channel channel = lastConnect.channel();
            if (channel != null) {
                channel.close().awaitUninterruptibly();
            }
        }
        if (connectedChannel != null) {
            connectedChannel.close().awaitUninterruptibly();
        }
        EventLoopGroup group = bootstrap.config().group();
        if (group != null) {
            group.shutdownGracefully().awaitUninterruptibly();
        }
    }

    private void sendMsg(Message message) {
        if (outstandingMessages.getAndIncrement() >= Math.min(messageBufferSize, maxOutstanding)) {
            outstandingMessages.decrementAndGet();
            ServerMetrics.getMetrics().TRACE_EVENTS_DROPPED.add(1);
            return;
        }
        if (maybeSubmit(new SendMsg(message))) {
            ServerMetrics.getMetrics().TRACE_EVENTS_QUEUED.add(1);
        }
    }

    private void sendAck(Channel channel, int count) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Ack: {}", count);
        }

        if (channel != connectedChannel) {
            return;
        }
        sentMessagesNoAck.addAndGet(0 - count);
        ServerMetrics.getMetrics().TRACE_EVENTS_ACKED.add(count);

        maybeSubmit(new Ack());
    }

    private void reconnect() {
        if (executor.isShutdown()) {
            return;
        }
        executor.schedule(new Reconnect(), 1000, TimeUnit.MILLISECONDS);
    }

    private void notifyConnected() {
        maybeSubmit(new Connected());
    }

    private void startSending() {
        maybeSubmit(new StartSending());
    }

    private void notifyDisconnect() {
        maybeSubmit(new Disconnected());
    }

    // Events
    private class SendMsg implements Runnable {
        Message message;

        SendMsg(Message message) {
            this.message = message;
        }

        @Override
        public void run() {
            messages.add(message);
            sendMessages();
        }
    }

    private class Disconnected implements Runnable {
        @Override
        public void run() {
            LOG.debug("Disconnected");
            state.set(State.DISCONNECTED);
            reconnect();
        }
    }

    private class Reconnect implements Runnable {
        @Override
        public void run() {
            LOG.debug("Reconnecting");
            state.set(State.CONNECTING);
            connect();
        }
    }

    private class Connected implements Runnable {
        @Override
        public void run() {
            LOG.info("Connected and sending handshake");
            state.set(State.HANDSHAKE);
            connectedChannel = lastConnect.channel();

            Message header = msg();
            header.add("header", "zookeeper-trace");
            // Protocol version in-case we ever want to change things
            header.add("version", "1");
            header.add("destination", destination);
            header.add("ensemble", ensembleName);
            try {
                header.add("hostname", InetAddress.getLocalHost().getHostName());
            } catch (UnknownHostException ignore) {
                header.add("hostname", "unknown");
            }
            header.add("window", windowSize);
            header.add("schema", TraceField.getSchemaAsString());

            sendMessage(header);
            sentMessagesNoAck.set(0);
            lastWrite.addListener(channelFuture -> {
                if (channelFuture.isSuccess()) {
                    startSending();
                }
            });

            // flush header to log server
            flush();
        }
    }

    private class StartSending implements Runnable {
        @Override
        public void run() {
            LOG.info("In steady-state, sending trace events");
            state.set(State.SENDING);
            sendMessages();
        }
    }

    private class Ack implements Runnable {
        @Override
        public void run() {
            // send messages after acknowledgement
            sendMessages();
        }
    }

    private void sendMessages() {
        if (state.get() != State.SENDING) {
            return;
        }
        // Send messages
        int count = 0;
        while (sentMessagesNoAck.get() < windowSize && connectedChannel.isWritable()) {
            Message message = messages.poll();
            if (message == null) {
                break;
            } else {
                sentMessagesNoAck.incrementAndGet();
                sendMessage(message);
                outstandingMessages.decrementAndGet();
            }

            count++;
            if (count >= minMessagesToFlush) {
                flush();
                count = 0;
            }
        }

        if (count > 0) {
            flush();
        }
    }

    // write message to netty channel
    private void sendMessage(Message message) {
        connectedChannel.write(messageBegin);
        ArrayList<String> values = message.values;
        for (int i = 0; i < values.size(); i += 2) {
            String key = values.get(i);
            String value = escape(values.get(i + 1));
            if (key != null && value != null) {
                connectedChannel.write(key);
                connectedChannel.write(value);
            }
        }
        lastWrite = connectedChannel.writeAndFlush(messageEnd);
        ServerMetrics.getMetrics().TRACE_EVENTS_SENT.add(1);
    }

    private String escape(String value) {
        if (messageBegin.equals(value)) {
            return messageBeginEscaped;
        } else if (messageEnd.equals(value)) {
            return messageEndEscaped;
        }
        return value;
    }

    private void connect() {
        int idx = rng.nextInt(loggerHosts.length);
        lastConnect = bootstrap.connect(new InetSocketAddress(loggerHosts[idx].trim(), loggerPort)).addListener(
            new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Connected successfully");
                        }
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Error connecting", future.cause());
                        }
                        // If the connect failed, the channel never became
                        // active so channelInactive() will not be called. We
                        // need to call notifyDisconnect() here to make sure
                        // we will try to reconnect after a short delay.
                        notifyDisconnect();
                    }
                }
            }
        );
    }

    private class TraceLoggerHandler extends SimpleChannelInboundHandler<String> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            notifyConnected();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            notifyDisconnect();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
            int acks = Integer.parseInt(msg);
            sendAck(ctx.channel(), acks);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // Close the connection when an exception is raised.
            if (LOG.isDebugEnabled()) {
                LOG.debug("Exception in handler", cause);
            }
            ctx.channel().close();
        }
    }

    private static class HeartbeatHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent idleStateEvent = (IdleStateEvent) evt;
                if (idleStateEvent.state() == IdleState.WRITER_IDLE) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Sending empty heartbeat message");
                    }
                    ctx.channel().write(messageBegin);
                    // call writeAndFlush() to make sure heart beat is sent
                    // close channel on error to trigger disconnect/reconnect
                    ctx.channel().writeAndFlush(messageEnd).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                }
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }
    }

    private class TraceChannelInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        protected void initChannel(SocketChannel ch) throws Exception {
            ChannelPipeline p = ch.pipeline();

            // decode with maxFrameLength=65535 and lengthFieldLength=2-byte
            p.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                    65535, 0, 2, 0, 2));
            p.addLast("decoder", new StringDecoder());
            // encode
            p.addLast("frameEncoder", new LengthFieldPrepender(2));
            p.addLast("encoder", new StringEncoder());
            // Detects idle connections and sends IdleStateEvents
            p.addLast("idleStateHandler", new IdleStateHandler(0, 120, 0));
            // Periodically sends empty trace messages on idle connections
            p.addLast("heartbeatHandler", new HeartbeatHandler());
            // Main handler
            p.addLast("traceLogger", new TraceLoggerHandler());

        }
    }

    /**
     * Sets the test ByteBufAllocator. This allocator will be used by all
     * future instances of this class.
     * It is not recommended to use this method outside of testing.
     * @param allocator the ByteBufAllocator to use for all netty buffer
     *                  allocations.
     */
    static void setTestAllocator(ByteBufAllocator allocator) {
        TEST_ALLOCATOR.set(allocator);
    }

    /**
     * Clears the test ByteBufAllocator. The default allocator will be used
     * by all future instances of this class.
     * It is not recommended to use this method outside of testing.
     */
    static void clearTestAllocator() {
        TEST_ALLOCATOR.set(null);
    }
}
