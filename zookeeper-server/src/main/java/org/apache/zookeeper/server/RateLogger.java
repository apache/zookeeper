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

package org.apache.zookeeper.server;

import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;

public class RateLogger {
    public RateLogger(Logger log) {
        LOG = log;
    }

    private final Logger LOG;
    private String msg = null;
    private long timestamp;
    private int count = 0;

    public void flush() {
        if (msg != null) {
            if (count > 1) {
                LOG.warn("[" + count + " times] " + msg);
            } else if (count == 1) {
                LOG.warn(msg);
            }
        }
        msg = null;
        count = 0;
    }

    public void rateLimitLog(String newMsg) {
        long now = Time.currentElapsedTime();
        if (newMsg.equals(msg)) {
            ++count;
            if (now - timestamp >= 100) {
                flush();
                msg = newMsg;
                timestamp = now;
            }
        } else {
            flush();
            msg = newMsg;
            timestamp = now;
            LOG.warn(msg);
        }
    }
}
