/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import org.apache.zookeeper.server.quorum.Leader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class provides a way of buffering sentBuffer and receivedBuffer messages in order.
 * It uses EvictingQueue of size BUFFERED_MESSAGE_SIZE to store the messages.
 * When the queue is full it overrides the oldest in a circular manner.
 * This class does doe not provide thread safety.
 */
public class MessageTracker {

    private static final Logger LOG = LoggerFactory.getLogger(MessageTracker.class);

    private final CircularBuffer<BufferedMessage> sentBuffer;
    private final CircularBuffer<BufferedMessage> receivedBuffer;

    public static final String MESSAGE_TRACKER_BUFFER_SIZE = "zookeeper.messageTracker.BufferSize";
    public static final String MESSAGE_TRACKER_ENABLED = "zookeeper.messageTracker.Enabled";
    public static final int BUFFERED_MESSAGE_SIZE;
    private static final boolean enabled;
    static {
        BUFFERED_MESSAGE_SIZE = Integer.getInteger(MESSAGE_TRACKER_BUFFER_SIZE, 10);
        enabled = Boolean.getBoolean(MESSAGE_TRACKER_ENABLED);
    }

    public MessageTracker(int buffer_size) {
        this.sentBuffer = new CircularBuffer<>(BufferedMessage.class, buffer_size);
        this.receivedBuffer = new CircularBuffer<>(BufferedMessage.class, buffer_size);
    }

    public void trackSent(long timestamp) {
        if (enabled) {
            sentBuffer.write(new BufferedMessage(timestamp));
        }
    }

    public void trackSent(int packetType) {
        if (enabled) {
            sentBuffer.write(new BufferedMessage(packetType));
        }
    }

    public void trackReceived(long timestamp) {
        if (enabled) {
            receivedBuffer.write(new BufferedMessage(timestamp));
        }
    }

    public void trackReceived(int packetType) {
        if (enabled) {
            receivedBuffer.write(new BufferedMessage(packetType));
        }
    }

    public final BufferedMessage peekSent() {
        return sentBuffer.peek();
    }

    public final BufferedMessage peekReceived() {
        return receivedBuffer.peek();
    }

    public final long peekSentTimestamp() {
        return enabled ? sentBuffer.peek().getTimestamp() : 0;
    }

    public final long peekReceivedTimestamp() {
        return enabled ? receivedBuffer.peek().getTimestamp() : 0;
    }

    public void dumpToLog(String serverAddress) {
        if (!enabled) {
            return;
        }
        logMessages(serverAddress, receivedBuffer, Direction.RECEIVED);
        logMessages(serverAddress, sentBuffer, Direction.SENT);
    }

    private static void logMessages(
        String serverAddr,
        CircularBuffer<BufferedMessage> messages,
        Direction direction) {
        String sentOrReceivedText = direction == Direction.SENT ? "sentBuffer to" : "receivedBuffer from";

        if (messages.isEmpty()) {
            LOG.info("No buffered timestamps for messages {} {}", sentOrReceivedText, serverAddr);
        } else {
            LOG.warn("Last {} timestamps for messages {} {}:", messages.size(), sentOrReceivedText, serverAddr);
            while (!messages.isEmpty()) {
                LOG.warn("{} {}  {}", sentOrReceivedText, serverAddr, messages.take().toString());
            }
        }
    }

    /**
     * Direction for message track.
     */
    private enum Direction {
        SENT, RECEIVED
    }

    private static class BufferedMessage {

        private long timestamp;
        private int messageType;

        private long getTimestamp() {
            return timestamp;
        }

        BufferedMessage(int messageType) {
            this.messageType = messageType;
            this.timestamp = System.currentTimeMillis();
        }

        BufferedMessage(long timestamp) {
            this.messageType = -1;
            this.timestamp = timestamp;
        }

        @Override
        /**
         * ToString examples are as follows:
         * TimeStamp: 2016-06-06 11:07:58,594 Type: PROPOSAL
         * TimeStamp: 2016-06-06 11:07:58,187
         */
        public String toString() {
            if (messageType == -1) {
                return "TimeStamp: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS")
                    .format(new Date(timestamp));
            } else {
                return "TimeStamp: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS")
                    .format(new Date(timestamp)) + " Type: " + Leader.getPacketType(messageType);
            }
        }
    }
}
