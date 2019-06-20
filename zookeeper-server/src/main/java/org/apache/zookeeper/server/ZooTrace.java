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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.zookeeper.common.StringUtils;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.server.instrument.InFilter;
import org.apache.zookeeper.server.instrument.TraceField;
import org.apache.zookeeper.server.instrument.TraceFieldFilter;
import org.apache.zookeeper.server.instrument.TraceLogger;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.QuorumPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class encapsulates and centralizes tracing for the ZooKeeper server.
 * Trace messages go to the TraceLogger asynchronously with TRACE level.
 * <p>
 * System property zookeeper.traceMask must be correctly configured to capture the TRACE messages.
 *
 * Trace filter can be configured via system property zookeeper.traceFilter. Filter definition consists
 * of multiple filter expressions delimited by double colon.
 *
 *   filter_expr::filter_expr::filter_expr
 *
 * Each filter expression contains trace field name and list of accepted values. The trace field name
 * must match one of field defined in TraceField and is case insensitive, while trace value is case sensitive.
 * The following filter expression will filter out all messages other than stage = FINAL or stage = SEND_WATCH.
 *
 *   stage=FINAL,SEND_WATCH
 *
 * Traces may be dropped depending on the capacity of TraceLogger.
 *
 * See {@link org.apache.zookeeper.server.instrument.TraceLogger}
 */
public class ZooTrace {

    private static final Logger LOG = LoggerFactory.getLogger(ZooTrace.class);

    public static final int CLIENT_REQUEST_TRACE_MASK = 1 << 1;

    public static final int CLIENT_DATA_PACKET_TRACE_MASK = 1 << 2;

    public static final int CLIENT_PING_TRACE_MASK = 1 << 3;

    public static final int SERVER_PACKET_TRACE_MASK = 1 << 4;

    public static final int SESSION_TRACE_MASK = 1 << 5;

    public static final int EVENT_DELIVERY_TRACE_MASK = 1 << 6;

    public static final int SERVER_PING_TRACE_MASK = 1 << 7;

    public static final int WARNING_TRACE_MASK = 1 << 8;

    public static final int JMX_TRACE_MASK = 1 << 9;

    public static final int QUORUM_TRACE_MASK = 1 << 10;

    // path prefix to distinguish different types of watches
    public static final String DATA_WATCH_PREFIX = "d";

    public static final String EXIST_WATCH_PREFIX = "e";

    public static final String CHILD_WATCH_PREFIX = "c";

    public static final String TRACE_MASK = "zookeeper.traceMask";

    private static volatile long traceMask = Long.getLong(TRACE_MASK, 0L);

    // delimiter is :: (example: field1=v1,v2,v3::field2=v4,v5)
    private static String traceFilterString = System.getProperty("zookeeper.traceFilter", "");

    private static volatile TraceLogger traceLogger = null;

    // include server id in tracing
    private static volatile long peerId = -1;

    private static final boolean disableTraceLogger = Boolean.getBoolean("zookeeper.disableTraceLogger");

    public static synchronized void start(String ensembleName, long peerId) {
        if (traceLogger == null) {
            if (disableTraceLogger) {
                LOG.info("Trace logger is disabled.");
                return;
            }

            try {
                traceLogger = new TraceLogger(ensembleName);
                ZooTrace.peerId = peerId;
                setTraceFilterString(traceFilterString);
            } catch (Exception e) {
                LOG.warn("Failed to start trace logger", e);
            }
        }
    }

    public static synchronized void shutdown() {
        shutdown(traceLogger);
        peerId = -1;
        traceLogger = null;
    }

    private static synchronized void shutdown(final TraceLogger logger) {
        if (logger != null) {
            try {
                logger.shutdown();
            } catch (Exception e) {
                LOG.warn("Error in shutting down trace logger", e);
            }
        }
    }
    public static synchronized long getTextTraceLevel() {
        return traceMask;
    }

    public static synchronized void setTextTraceLevel(long mask) {
        traceMask = mask;
        LOG.info("Set text trace mask to 0x{}", Long.toHexString(mask));
    }

    public static synchronized String getTraceFilterString() {
        return traceFilterString;
    }

    public static synchronized void setTraceFilterString(String filter) {
        traceFilterString = filter;
        LOG.info("Set trace filter string to {}", traceFilterString);

        Map<String, TraceFieldFilter> filterMap = new HashMap<>();
        if (org.apache.commons.lang.StringUtils.isNotEmpty(filter)) {
            List<String> result = StringUtils.split(traceFilterString, "::");
            for (String filterExpr: result) {
                try {
                    int equalIndex = filterExpr.indexOf("=");
                    if (equalIndex > 0) {
                        String traceFieldName = filterExpr.substring(0, equalIndex).trim().toUpperCase();
                        String traceFieldValues = filterExpr.substring(equalIndex + 1).trim();
                        filterMap.put(traceFieldName, createTraceFilter(traceFieldName, traceFieldValues));
                    }
                } catch (Exception e) {
                    LOG.warn("Trace filter expression {} is invalid", filterExpr, e);
                }
            }
        }

        for (TraceField field: TraceField.values()) {
            field.setFilter(filterMap.getOrDefault(field.name(), TraceFieldFilter.NO_FILTER));
        }
    }

    private static TraceFieldFilter createTraceFilter(String traceFieldName, String traceFieldValues) {
        TraceField traceField = TraceField.valueOf(traceFieldName);
        List<String> values = StringUtils.split(traceFieldValues, ",");
        return new InFilter(traceField, values);
    }

    private static long getCurrentTimeSecs() {
        return System.currentTimeMillis() / 1000L;
    }

    public static String getTraceType(long mask) {
        int traceMask = (int) (mask);
        switch (traceMask) {
            case CLIENT_REQUEST_TRACE_MASK:
                return "client_req";
            case CLIENT_DATA_PACKET_TRACE_MASK:
                return "client_packet";
            case CLIENT_PING_TRACE_MASK:
                return "client_ping";
            case SERVER_PACKET_TRACE_MASK:
                return "server_packet";
            case SESSION_TRACE_MASK:
                return "session";
            case EVENT_DELIVERY_TRACE_MASK:
                return "event";
            case SERVER_PING_TRACE_MASK:
                return "server_ping";
            case WARNING_TRACE_MASK:
                return "warning";
            case JMX_TRACE_MASK:
                return "jmx";
            case QUORUM_TRACE_MASK:
                return "quorum";
            default:
                return "unknown";
        }
    }

    private static synchronized boolean isTraceEnabled(TraceLogger logger, long mask) {
        return logger != null && (mask & traceMask) != 0;
    }

    public static void logEvent(long mask, RequestStage stage, String clientId, long sessionId, String path) {
        log(mask, stage, clientId, sessionId, path);
    }

    public static void logPath(long mask, RequestStage stage, String path) {
        final TraceLogger logger = traceLogger;
        if (isTraceEnabled(logger, mask) && logger.isReady()) {
            logger.msg()
                    .add(TraceField.TIME, getCurrentTimeSecs())
                    .add(TraceField.PEER_ID, peerId)
                    .add(TraceField.STAGE, stage == null ? "" : stage.name())
                    .add(TraceField.PATH, Arrays.asList(path))
                    .add(TraceField.TRACE_TYPE, getTraceType(mask))
                    .send();
        }
    }

    public static void logQuorumPacket(long mask, String direction, QuorumPacket qp) {
        final TraceLogger logger = traceLogger;
        if (isTraceEnabled(logger, mask) && logger.isReady() && qp != null) {
            logger.msg()
                    .add(TraceField.TIME, getCurrentTimeSecs())
                    .add(TraceField.ZXID, qp.getZxid())
                    .add(TraceField.PEER_ID, peerId)
                    .add(TraceField.PACKET_TYPE, Leader.getPacketType(qp.getType()))
                    .add(TraceField.PACKET_DIRECTION, direction)
                    .add(TraceField.PEER_ID, peerId)
                    .add(TraceField.TRACE_TYPE, getTraceType(mask))
                    .send();
        }
    }

    public static void logRequest(long mask, RequestStage stage, long zxid) {
        logRequest(mask, stage, zxid, null);
    }

    public static void logRequest(long mask, RequestStage stage, long zxid, long cxid,
                                  long sessionId, long requestCreateTime) {
        final TraceLogger logger = traceLogger;
        if (isTraceEnabled(logger, mask) && logger.isReady()) {
            logger.msg()
                    .add(TraceField.TIME, getCurrentTimeSecs())
                    .add(TraceField.ZXID, zxid)
                    .add(TraceField.CXID, cxid)
                    .add(TraceField.SESSION_ID, sessionId)
                    .add(TraceField.PEER_ID, peerId)
                    .add(TraceField.STAGE, stage == null ? "" : stage.name())
                    .add(TraceField.TRACE_TYPE, getTraceType(mask))
                    .add(TraceField.LATENCY, Time.currentElapsedTime() - requestCreateTime)
                    .send();
        }
    }

    public static void logRequest(long mask, RequestStage stage, long zxid, String path) {
        final TraceLogger logger = traceLogger;
        if (isTraceEnabled(logger, mask) && logger.isReady()) {
            TraceLogger.Message msg = logger.msg()
                    .add(TraceField.TIME, getCurrentTimeSecs())
                    .add(TraceField.ZXID, zxid)
                    .add(TraceField.PEER_ID, peerId)
                    .add(TraceField.STAGE, stage == null ? "" : stage.name())
                    .add(TraceField.TRACE_TYPE, getTraceType(mask));

            if (path != null) {
                msg.add(TraceField.PATH, Arrays.asList(path));
            }

            msg.send();
        }
    }

    public static void logRequest(long mask, RequestStage stage, Request request) {
        logRequest(mask, stage, request, 0, false);
    }

    public static void logRequest(long mask, RequestStage stage, Request request, int err, boolean withLatency) {
        final TraceLogger logger = traceLogger;

        if (!isTraceEnabled(logger, mask) || !logger.isReady() || request == null) {
            return;
        }

        TraceLogger.Message msg = logger.msg()
                .add(TraceField.STAGE, stage == null ? "" : stage.name())
                .add(TraceField.TIME, getCurrentTimeSecs())
                .add(TraceField.ZXID, request.zxid)
                .add(TraceField.CXID, request.cxid)
                .add(TraceField.SESSION_ID, request.sessionId)
                .add(TraceField.PEER_ID, peerId)
                .add(TraceField.REQUEST_TYPE, Request.op2String(request.type))
                .add(TraceField.ERROR, err)
                .add(TraceField.HAS_WATCH, request.hasWatch())
                .add(TraceField.TRACE_TYPE, getTraceType(mask));

        if (request.clientInfo != null) {
            msg.add(TraceField.CLIENT_ID, request.clientInfo);
        }

        if (request.clientIp != null) {
            msg.add(TraceField.CLIENT_IP, request.clientIp);
            // There's no point in adding the client port if the IP isn't added too
            msg.add(TraceField.CLIENT_PORT, request.clientPort);
        }

        if (request.request != null) {
            int requestSize = request.request.position() + request.request.remaining();
            msg.add(TraceField.REQUEST_SIZE, requestSize);
        }

        if (request.hasEphemeral()) {
            msg.add(TraceField.IS_EPHEMERAL, request.isEphemeral());
        }
        if (request.hasPath()) {
            msg.add(TraceField.PATH, request.getPathList());
        }
        if (withLatency) {
            msg.add(TraceField.LATENCY, Time.currentElapsedTime() - request.createTime);
        }
        msg.send();
    }

    public static void logSession(long mask, RequestStage stage, long sessionId) {
        log(mask, stage, null, sessionId, null);
    }

    private static void log(long mask, RequestStage stage, String clientId, long sessionId, String path) {
        final TraceLogger logger = traceLogger;
        if (isTraceEnabled(logger, mask) && logger.isReady()) {
            TraceLogger.Message msg = logger.msg()
                    .add(TraceField.TIME, getCurrentTimeSecs())
                    .add(TraceField.PEER_ID, peerId)
                    .add(TraceField.SESSION_ID, sessionId)
                    .add(TraceField.STAGE, stage == null ? "" : stage.name())
                    .add(TraceField.TRACE_TYPE, getTraceType(mask));

            if (clientId != null) {
                msg.add(TraceField.CLIENT_ID, clientId);
            }

            if (path != null) {
                msg.add(TraceField.PATH, Arrays.asList(path));
            }

            msg.send();
        }

    }

}
