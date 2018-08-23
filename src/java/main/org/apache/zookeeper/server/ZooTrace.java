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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.server.quorum.LearnerHandler;
import org.apache.zookeeper.server.quorum.QuorumPacket;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * This class encapsulates and centralizes tracing for the ZooKeeper server.
 * Trace messages go to the log with TRACE level.
 * <p>
 * Log4j must be correctly configured to capture the TRACE messages.
 */
public class ZooTrace {
    final static public long CLIENT_REQUEST_TRACE_MASK = 1 << 1;

    final static public long CLIENT_DATA_PACKET_TRACE_MASK = 1 << 2;

    final static public long CLIENT_PING_TRACE_MASK = 1 << 3;

    final static public long SERVER_PACKET_TRACE_MASK = 1 << 4;

    final static public long SESSION_TRACE_MASK = 1 << 5;

    final static public long EVENT_DELIVERY_TRACE_MASK = 1 << 6;

    final static public long SERVER_PING_TRACE_MASK = 1 << 7;

    final static public long WARNING_TRACE_MASK = 1 << 8;

    final static public long JMX_TRACE_MASK = 1 << 9;

    final static public short MAX_TRACE_RATE = 1000;

    final static public int NUM_OF_BITS = 10;

    final static public short[] DEFAULT_RATES = initTraceRates(MAX_TRACE_RATE);

    private static long traceMask = CLIENT_REQUEST_TRACE_MASK
            | SERVER_PACKET_TRACE_MASK | SESSION_TRACE_MASK
            | WARNING_TRACE_MASK;

    private static short[] traceRates = DEFAULT_RATES;

    private static short[] initTraceRates(short value) {
        short[] rates = new short[NUM_OF_BITS];
        Arrays.fill(rates, value);
        return rates;
    }

    public static boolean validate() {
        if (traceRates.length != NUM_OF_BITS) {
            return  false;
        }

        for (short rate : traceRates) {
            if (!validateRate(rate)) {
                return false;
            }
        }

        return true;
    }

    private static boolean validateRate(short rate) {
        return (rate >= 0 && rate <= MAX_TRACE_RATE);
    }

    public static short getTraceRateForBit(Long mask) {
        if (mask == 0) {
            return 0;

        }

        int index = Long.numberOfTrailingZeros(mask);
        return traceRates[index];
    }

    public static synchronized void setTraceRates(short[] rates) {
        traceRates = rates;
        final Logger LOG = LoggerFactory.getLogger(ZooTrace.class);
        LOG.info("Set trace rates to " + Arrays.toString(rates));
        if (!validate()) {
            LOG.error("Unsupported trace rates value " + Arrays.toString(traceRates));
        }
    }

    public static synchronized long getTextTraceLevel() {
        return traceMask;
    }

    public static synchronized void setTextTraceLevel(long mask) {
        traceMask = mask;
        final Logger LOG = LoggerFactory.getLogger(ZooTrace.class);
        LOG.info("Set text trace mask to 0x" + Long.toHexString(mask));
    }

    public static synchronized boolean isTraceEnabled(Logger log, long mask) {
        short traceRate = getTraceRateForBit(mask);
        return log.isTraceEnabled() && (mask & traceMask) != 0 && validateRate(traceRate) && traceRate != 0;
    }

    public static boolean coinFlip(long mask) {
        short traceRate = getTraceRateForBit(mask);
        return ThreadLocalRandom.current().nextInt(0, MAX_TRACE_RATE) < traceRate;
    }

    public static void logTraceMessage(Logger log, long mask, String msg) {
        if (isTraceEnabled(log, mask) && coinFlip(mask)) {
            log.trace(msg);
        }
    }

    static public void logQuorumPacket(Logger log, long mask,
                                       char direction, QuorumPacket qp)
    {
        if (isTraceEnabled(log, mask) && coinFlip(mask)) {
            logTraceMessage(log, mask, direction +
                    " " + LearnerHandler.packetToString(qp));
        }
    }

    static public void logRequest(Logger log, long mask,
                                  char rp, Request request, String header)
    {
        if (isTraceEnabled(log, mask) && coinFlip(mask)) {
            log.trace(header + ":" + rp + request.toString() + ":" + getAddress(request));
        }
    }

    static private String getAddress(Request request) {
        try {
            return request.cnxn.getRemoteSocketAddress().toString();
        } catch (NullPointerException ex) {
            return "";
        }
    }
}
