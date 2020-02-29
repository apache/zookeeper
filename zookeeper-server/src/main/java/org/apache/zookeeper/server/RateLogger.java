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
 * This logs the message once in the beginning and once every LOG_INTERVAL.
 */
public class RateLogger {

    private final long LOG_INTERVAL; // Duration is in ms

    public RateLogger(Logger log) {
        this(log, 100);
    }

    public RateLogger(Logger log, long interval) {
        LOG = log;
        LOG_INTERVAL = interval;
    }

    private final Logger LOG;
    private String msg = null;
    private long timestamp;
    private int count = 0;
    private String value = null;

    public void flush() {
        if (msg != null && count > 0) {
            String log = "";
            if (count > 1) {
                log = "[" + count + " times] ";
            }
            log += "Message: " + msg;
            if (value != null) {
                log += ", Last value: " + value;
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
     * Any new message is logged once to the underlying logger.
     *
     *
     * If subsequent messages remain the same as the previous
     * they are not logged unless a specified time interval has elapsed.
     * A subsequent message that is different to the previous also logs the
     * previous message.
     * At any time the current message can be logged using {@link #flush()}.
     * <p>
     * Messages are written to log with format '
     *
     *
     * @param newMsg the message to log;
     * @param value the value provided while logging the message
     *                     message value; Optional
     */
    public void rateLimitLog(String newMsg, String value) {
        long now = Time.currentElapsedTime();
        if (newMsg.equals(msg)) {
            ++count;
            this.value = value;  // should this go in an else block?
            if (now - timestamp >= LOG_INTERVAL) {
                // log previous message and value
                flush();
                msg = newMsg;
                timestamp = now;
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
                LOG.warn("Message: {}, Value: {}", msg, value);
            }
        }
    }

}
