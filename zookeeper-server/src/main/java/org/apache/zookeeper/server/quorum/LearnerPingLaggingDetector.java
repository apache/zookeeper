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

package org.apache.zookeeper.server.quorum;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class track the ack time between learner and leader, and return true if it
 * exceeds the sync timeout, mainly used to detect network issues.
 */
public class LearnerPingLaggingDetector implements PingLaggingDetector {
    private static final Logger LOG = LoggerFactory.getLogger(LearnerPingLaggingDetector.class);

    static class Ping {
        long zxid;
        long time;

        public Ping(long zxid, long time) {
                this.zxid = zxid;
                this.time = time;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Ping ping = (Ping) o;
            return zxid == ping.zxid;
        }

        @Override
        public int hashCode() {
            return Objects.hash(zxid);
        }
    }

    Queue<Ping> pendingPings = new ArrayDeque<>();
    private final int laggingThreshold;
    private boolean started = false;

    public LearnerPingLaggingDetector(int laggingThreshold) {
        this.laggingThreshold = laggingThreshold;
    }

    @Override
    public synchronized void start() {
        started = true;
    }

    @Override
    public synchronized void trackMessage(long zxid, long time) {
        if (!started) {
            return;
        }

        // On the learner side, we track every ping because we don't know when the session expiration
        // timer is reset on the leader. If the learner used the same detection algorithm as the leader,
        // it might detect lagging later than the leader due to the network latency and if the
        // network latency were high (longer than the padding time between session timeout and lagging
        // threshold), the learner would detect lagging after the leader had expired the sessions.
        // By tracking every ping, detection on the learner should be within one ping interval of that
        // on the leader regardless of the network latency.
        pendingPings.offer(new Ping(zxid, time));
    }

    @Override
    public synchronized void trackAck(long zxid) {
        Ping head = pendingPings.poll();

        while (head != null && head.zxid != zxid) {
            LOG.warn("Skipped response for 0x{} received response for {}", head.zxid, zxid);
            head = pendingPings.poll();
        }

        if (head == null) {
            LOG.error("Received response for 0x{} but there is no PING with this zxid", Long.toHexString(zxid));
        }
    }


    public synchronized boolean isLagging(long time) {
        if (pendingPings.isEmpty()) {
            return false;
        }

        long pingTime = pendingPings.peek().time;
        long msDelay = (time - pingTime) / 1000000;
        return msDelay >= laggingThreshold;
    }
}
