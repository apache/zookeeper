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
 *uuuuu
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "/RequuuAS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.ByteBuffer;
import java.util.List;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.metrics.Summary;
import org.apache.zookeeper.metrics.SummarySet;
import org.apache.zookeeper.server.quorum.LearnerHandler;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.util.AuthUtil;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * This is the structure that represents a request moving through a chain of
 * RequestProcessors. There are various pieces of information that is tacked
 * onto the request as it is processed.
 */
public class Request {

    public static final Request requestOfDeath = new Request(null, 0, 0, 0, null, null);

    // Considers a request stale if the request's connection has closed. Enabled
    // by default.
    private static volatile boolean staleConnectionCheck = Boolean.parseBoolean(System.getProperty("zookeeper.request_stale_connection_check", "true"));

    // Considers a request stale if the request latency is higher than its
    // associated session timeout. Disabled by default.
    private static volatile boolean staleLatencyCheck = Boolean.parseBoolean(System.getProperty("zookeeper.request_stale_latency_check", "false"));

    public Request(ServerCnxn cnxn, long sessionId, int xid, int type, ByteBuffer bb, List<Id> authInfo) {
        this.cnxn = cnxn;
        this.sessionId = sessionId;
        this.cxid = xid;
        this.type = type;
        this.request = bb;
        this.authInfo = authInfo;
    }

    public Request(long sessionId, int xid, int type, TxnHeader hdr, Record txn, long zxid) {
        this.sessionId = sessionId;
        this.cxid = xid;
        this.type = type;
        this.hdr = hdr;
        this.txn = txn;
        this.zxid = zxid;
        this.request = null;
        this.cnxn = null;
        this.authInfo = null;
    }

    public final long sessionId;

    public final int cxid;

    public final int type;

    public final ByteBuffer request;

    public final ServerCnxn cnxn;

    private TxnHeader hdr;

    private Record txn;

    public long zxid = -1;

    public final List<Id> authInfo;

    public final long createTime = Time.currentElapsedTime();

    public long prepQueueStartTime = -1;

    public long prepStartTime = -1;

    public long commitProcQueueStartTime = -1;

    public long commitRecvTime = -1;

    public long syncQueueStartTime;

    public long requestThrottleQueueTime;

    private Object owner;

    private KeeperException e;

    public QuorumVerifier qv = null;

    private TxnDigest txnDigest;

    private boolean isThrottledFlag = false;

    public boolean isThrottled() {
      return isThrottledFlag;
    }

    public void setIsThrottled(boolean val) {
      isThrottledFlag = val;
    }

    public boolean isThrottlable() {
        return this.type != OpCode.ping
                && this.type != OpCode.closeSession
                && this.type != OpCode.createSession;
    }

    /**
     * If this is a create or close request for a local-only session.
     */
    private boolean isLocalSession = false;

    private int largeRequestSize = -1;

    public boolean isLocalSession() {
        return isLocalSession;
    }

    public void setLocalSession(boolean isLocalSession) {
        this.isLocalSession = isLocalSession;
    }

    public void setLargeRequestSize(int size) {
        largeRequestSize = size;
    }

    public int getLargeRequestSize() {
        return largeRequestSize;
    }

    public Object getOwner() {
        return owner;
    }

    public void setOwner(Object owner) {
        this.owner = owner;
    }

    public TxnHeader getHdr() {
        return hdr;
    }

    public void setHdr(TxnHeader hdr) {
        this.hdr = hdr;
    }

    public Record getTxn() {
        return txn;
    }

    public void setTxn(Record txn) {
        this.txn = txn;
    }

    public ServerCnxn getConnection() {
        return cnxn;
    }

    public static boolean getStaleLatencyCheck() {
        return staleLatencyCheck;
    }

    public static void setStaleLatencyCheck(boolean check) {
        staleLatencyCheck = check;
    }

    public static boolean getStaleConnectionCheck() {
        return staleConnectionCheck;
    }

    public static void setStaleConnectionCheck(boolean check) {
        staleConnectionCheck = check;
    }

    public boolean isStale() {
        if (cnxn == null) {
            return false;
        }

        // closeSession requests should be able to outlive the session in order
        // to clean-up state.
        if (type == OpCode.closeSession) {
            return false;
        }

        if (staleConnectionCheck) {
            // If the connection is closed, consider the request stale.
            if (cnxn.isStale() || cnxn.isInvalid()) {
                return true;
            }
        }

        if (staleLatencyCheck) {
            // If the request latency is higher than session timeout, consider
            // the request stale.
            long currentTime = Time.currentElapsedTime();
            return (currentTime - createTime) > cnxn.getSessionTimeout();
        }

        return false;
    }

    /**
     * A prior request was dropped on this request's connection and
     * therefore this request must also be dropped to ensure correct
     * ordering semantics.
     */
    public boolean mustDrop() {
        return ((cnxn != null) && cnxn.isInvalid());
    }

    /**
     * is the packet type a valid packet in zookeeper
     *
     * @param type
     *                the type of the packet
     * @return true if a valid packet, false if not
     */
    static boolean isValid(int type) {
        // make sure this is always synchronized with Zoodefs!!
        switch (type) {
        case OpCode.notification:
            return false;
        case OpCode.check:
        case OpCode.closeSession:
        case OpCode.create:
        case OpCode.create2:
        case OpCode.createTTL:
        case OpCode.createContainer:
        case OpCode.createSession:
        case OpCode.delete:
        case OpCode.deleteContainer:
        case OpCode.exists:
        case OpCode.getACL:
        case OpCode.getChildren:
        case OpCode.getAllChildrenNumber:
        case OpCode.getChildren2:
        case OpCode.getData:
        case OpCode.getEphemerals:
        case OpCode.multi:
        case OpCode.multiRead:
        case OpCode.ping:
        case OpCode.reconfig:
        case OpCode.setACL:
        case OpCode.setData:
        case OpCode.setWatches:
        case OpCode.setWatches2:
        case OpCode.sync:
        case OpCode.checkWatches:
        case OpCode.removeWatches:
        case OpCode.addWatch:
        case OpCode.whoAmI:
            return true;
        default:
            return false;
        }
    }

    public boolean isQuorum() {
        switch (this.type) {
        case OpCode.exists:
        case OpCode.getACL:
        case OpCode.getChildren:
        case OpCode.getAllChildrenNumber:
        case OpCode.getChildren2:
        case OpCode.getData:
        case OpCode.getEphemerals:
        case OpCode.multiRead:
        case OpCode.whoAmI:
            return false;
        case OpCode.create:
        case OpCode.create2:
        case OpCode.createTTL:
        case OpCode.createContainer:
        case OpCode.error:
        case OpCode.delete:
        case OpCode.deleteContainer:
        case OpCode.setACL:
        case OpCode.setData:
        case OpCode.check:
        case OpCode.multi:
        case OpCode.reconfig:
            return true;
        case OpCode.closeSession:
        case OpCode.createSession:
            return !this.isLocalSession;
        default:
            return false;
        }
    }

    public static String op2String(int op) {
        switch (op) {
            case OpCode.notification:
                return "notification";
            case OpCode.create:
                return "create";
            case OpCode.delete:
                return "delete";
            case OpCode.exists:
                return "exists";
            case OpCode.getData:
                return "getData";
            case OpCode.setData:
                return "setData";
            case OpCode.getACL:
                return "getACL";
            case OpCode.setACL:
                return "setACL";
            case OpCode.getChildren:
                return "getChildren";
            case OpCode.sync:
                return "sync";
            case OpCode.ping:
                return "ping";
            case OpCode.getChildren2:
                return "getChildren2";
            case OpCode.check:
                return "check";
            case OpCode.multi:
                return "multi";
            case OpCode.create2:
                return "create2";
            case OpCode.reconfig:
                return "reconfig";
            case OpCode.checkWatches:
                return "checkWatches";
            case OpCode.removeWatches:
                return "removeWatches";
            case OpCode.createContainer:
                return "createContainer";
            case OpCode.deleteContainer:
                return "deleteContainer";
            case OpCode.createTTL:
                return "createTtl";
            case OpCode.multiRead:
                return "multiRead";
            case OpCode.auth:
                return "auth";
            case OpCode.setWatches:
                return "setWatches";
            case OpCode.setWatches2:
                return "setWatches2";
            case OpCode.sasl:
                return "sasl";
            case OpCode.getEphemerals:
                return "getEphemerals";
            case OpCode.getAllChildrenNumber:
                return "getAllChildrenNumber";
            case OpCode.createSession:
                return "createSession";
            case OpCode.closeSession:
                return "closeSession";
            case OpCode.error:
                return "error";
            case OpCode.whoAmI:
                return "whoAmI";
            default:
                return "unknown " + op;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("sessionid:0x").append(Long.toHexString(sessionId))
          .append(" type:").append(op2String(type))
          .append(" cxid:0x").append(Long.toHexString(cxid))
          .append(" zxid:0x").append(Long.toHexString(hdr == null ? -2 : hdr.getZxid()))
          .append(" txntype:").append(hdr == null ? "unknown" : "" + hdr.getType());

        // best effort to print the path assoc with this request
        String path = "n/a";
        if (type != OpCode.createSession
            && type != OpCode.setWatches
            && type != OpCode.setWatches2
            && type != OpCode.closeSession
            && request != null
            && request.remaining() >= 4) {
            try {
                // make sure we don't mess with request itself
                ByteBuffer rbuf = request.asReadOnlyBuffer();
                rbuf.clear();
                int pathLen = rbuf.getInt();
                // sanity check
                if (pathLen >= 0 && pathLen < 4096 && rbuf.remaining() >= pathLen) {
                    byte[] b = new byte[pathLen];
                    rbuf.get(b);
                    path = new String(b, UTF_8);
                }
            } catch (Exception e) {
                // ignore - can't find the path, will output "n/a" instead
            }
        }
        sb.append(" reqpath:").append(path);

        return sb.toString();
    }

    public void setException(KeeperException e) {
        this.e = e;
    }

    public KeeperException getException() {
        return e;
    }

    public void logLatency(Summary metric) {
        logLatency(metric, Time.currentWallTime());
    }

    public void logLatency(Summary metric, long currentTime) {
        if (hdr != null) {
            /* Request header is created by leader. If there is clock drift
             * latency might be negative. Headers use wall time, not
             * CLOCK_MONOTONIC.
             */
            long latency = currentTime - hdr.getTime();
            if (latency >= 0) {
                metric.add(latency);
            }
        }
    }

    public void logLatency(SummarySet metric, String key, long currentTime) {
        if (hdr != null) {
            /* Request header is created by leader. If there is clock drift
             * latency might be negative. Headers use wall time, not
             * CLOCK_MONOTONIC.
             */
            long latency = currentTime - hdr.getTime();
            if (latency >= 0) {
                metric.add(key, latency);
            }
        }
    }

    public void logLatency(SummarySet metric, String key) {
        logLatency(metric, key, Time.currentWallTime());
    }

    /**
     * Returns comma separated list of users authenticated in the current
     * session
     */
    public String getUsers() {
        if (authInfo == null) {
            return (String) null;
        }
        if (authInfo.size() == 1) {
            return AuthUtil.getUser(authInfo.get(0));
        }
        StringBuilder users = new StringBuilder();
        boolean first = true;
        for (Id id : authInfo) {
            String user = AuthUtil.getUser(id);
            if (user != null) {
                if (first) {
                    first = false;
                } else {
                    users.append(",");
                }
                users.append(user);
            }
        }
        return users.toString();
    }

    public TxnDigest getTxnDigest() {
        return txnDigest;
    }

    public void setTxnDigest(TxnDigest txnDigest) {
        this.txnDigest = txnDigest;
    }

    public boolean isFromLearner() {
        return owner instanceof LearnerHandler;
    }
}
