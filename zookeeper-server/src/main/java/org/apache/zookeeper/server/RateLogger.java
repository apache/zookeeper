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

package org.apache.zookeeper.server;

import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;

/**
 * Any new message is logged once to the underlying logger. Subsequent messages
 * that are the same as the previous are not logged unless explicitly flushed
 * or unless the specified time interval has elapsed.
 *
 * <p> All messages are logged at the WARN level.</p>
 *
 * <p> Single Logged messages have format:
 * {@literal 'Message: <provided-message> Value: <provided-value>'}</p>
 *
 * <p> Multiple instances of the same message are logged with format:
 * {@literal
 * '[<n> times] Message: <provided-message> Last value: <last-provided-value>'}
 * </p>
 * <p>Value is optional and is omitted from the logged message when
 * not provided.</p>
 */
public class RateLogger {

    private final long LOG_INTERVAL; // Duration is in ms

    /**
     * Log any received messages at a default interval of 100 milliseconds.
     *
     * @param log the {@link Logger} to write messages to
     */
    public RateLogger(Logger log) {
        this(log, 100);
    }

    /**
     * Log any received messages according to a specified interval.
     *
     * @param log the {@link Logger} to write messages to
     * @param interval the minimal interval at which messages are written
     */
    public RateLogger(Logger log, long interval) {
        LOG = log;
        LOG_INTERVAL = interval;
    }

    private final Logger LOG;
    private String msg = null;
    private long timestamp;
    private int count = 0;
    private String value = null;

    /**
     * Write any held message to the underlying log.
     */
    public void flush() {
        if (msg != null && count > 0) {
            String log = "";
            if (count > 1) {
                log = "[" + count + " times] ";
            }
            log += "Message: " + msg;
            if (value != null) {
                log += " Last value: " + value;
            }
            LOG.warn(log);
        }
        msg = null;
        value = null;
        count = 0;
    }

    public void rateLimitLog(String newMsg) {
        rateLimitLog(newMsg, null);
    }

    /**
     * Writes a message to the underlying log with an
     * optional value.
     * @see RateLogger
     * @param newMsg the message to log;
     * @param value the value to log with the message; Optional
     */
    public void rateLimitLog(String newMsg, String value) {
        long now = getCurrentElapsedTime();
        if (newMsg.equals(msg)) {
            if (now - timestamp >= LOG_INTERVAL) {
                // log previous message and value
                flush();
                msg = newMsg;
                timestamp = now;
                this.value = value;
                count = 1;
            } else {
                ++count;
                this.value = value;
            }
        } else {
            flush();
            msg = newMsg;
            this.value = value;
            timestamp = now;
            // initially log message once, optionally with value.
            if (null == value) {
                LOG.warn("Message: {}", msg);
            } else {
                LOG.warn("Message: {} Value: {}", msg, value);
            }
        }
    }

    /**
     * Gets the current system time.
     */
    // package access for unit testing
    long getCurrentElapsedTime() {
        return Time.currentElapsedTime();
    }
}
