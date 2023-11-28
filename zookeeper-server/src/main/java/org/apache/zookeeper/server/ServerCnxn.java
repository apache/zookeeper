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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.metrics.Counter;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interface to a Server connection - represents a connection from a client
 * to the server.
 */
public abstract class ServerCnxn implements Stats, ServerWatcher {

    // This is just an arbitrary object to represent requests issued by
    // (aka owned by) this class
    public static final Object me = new Object();
    private static final Logger LOG = LoggerFactory.getLogger(ServerCnxn.class);

    private Set<Id> authInfo = Collections.newSetFromMap(new ConcurrentHashMap<Id, Boolean>());

    private static final byte[] fourBytes = new byte[4];

    /**
     * If the client is of old version, we don't send r-o mode info to it.
     * The reason is that if we would, old C client doesn't read it, which
     * results in TCP RST packet, i.e. "connection reset by peer".
     */
    boolean isOldClient = true;

    AtomicLong outstandingCount = new AtomicLong();

    /** The ZooKeeperServer for this connection. May be null if the server
     * is not currently serving requests (for example if the server is not
     * an active quorum participant.
     */
    final ZooKeeperServer zkServer;

    public enum DisconnectReason {
        UNKNOWN("unknown"),
        SERVER_SHUTDOWN("server_shutdown"),
        CLOSE_ALL_CONNECTIONS_FORCED("close_all_connections_forced"),
        CONNECTION_CLOSE_FORCED("connection_close_forced"),
        CONNECTION_EXPIRED("connection_expired"),
        CLIENT_CLOSED_CONNECTION("client_closed_connection"),
        CLIENT_CLOSED_SESSION("client_closed_session"),
        UNABLE_TO_READ_FROM_CLIENT("unable_to_read_from_client"),
        NOT_READ_ONLY_CLIENT("not_read_only_client"),
        CLIENT_ZXID_AHEAD("client_zxid_ahead"),
        INFO_PROBE("info_probe"),
        CLIENT_RECONNECT("client_reconnect"),
        CANCELLED_KEY_EXCEPTION("cancelled_key_exception"),
        IO_EXCEPTION("io_exception"),
        IO_EXCEPTION_IN_SESSION_INIT("io_exception_in_session_init"),
        BUFFER_UNDERFLOW_EXCEPTION("buffer_underflow_exception"),
        SASL_AUTH_FAILURE("sasl_auth_failure"),
        RESET_COMMAND("reset_command"),
        CLOSE_CONNECTION_COMMAND("close_connection_command"),
        CLEAN_UP("clean_up"),
        CONNECTION_MODE_CHANGED("connection_mode_changed"),
        RENEW_GLOBAL_SESSION_IN_RO_MODE("renew a global session in readonly mode"),
        // Below reasons are NettyServerCnxnFactory only
        CHANNEL_DISCONNECTED("channel disconnected"),
        CHANNEL_CLOSED_EXCEPTION("channel_closed_exception"),
        AUTH_PROVIDER_NOT_FOUND("auth provider not found"),
        FAILED_HANDSHAKE("Unsuccessful handshake"),
        CLIENT_RATE_LIMIT("Client hits rate limiting threshold"),
        CLIENT_CNX_LIMIT("Client hits connection limiting threshold");

        String disconnectReason;

        DisconnectReason(String reason) {
            this.disconnectReason = reason;
        }

        public String toDisconnectReasonString() {
            return disconnectReason;
        }
    }

    public ServerCnxn(final ZooKeeperServer zkServer) {
        this.zkServer = zkServer;
    }

    /**
     * Flag that indicates that this connection is known to be closed/closing
     * and from which we can optionally ignore outstanding requests as part
     * of request throttling. This flag may be false when a connection is
     * actually closed (false negative), but should never be true with
     * a connection is still alive (false positive).
     */
    private volatile boolean stale = false;

    /**
     * Flag that indicates that a request for this connection was previously
     * dropped as part of request throttling and therefore all future requests
     * must also be dropped to ensure ordering guarantees.
     */
    private volatile boolean invalid = false;

    abstract int getSessionTimeout();

    public void incrOutstandingAndCheckThrottle(RequestHeader h) {
        if (h.getXid() <= 0) {
            return;
        }
        if (zkServer.shouldThrottle(outstandingCount.incrementAndGet())) {
            disableRecv(false);
        }
    }

    // will be called from zkServer.processPacket
    public void decrOutstandingAndCheckThrottle(ReplyHeader h) {
        if (h.getXid() <= 0) {
            return;
        }
        if (!zkServer.shouldThrottle(outstandingCount.decrementAndGet())) {
            enableRecv();
        }
    }

    public abstract void close(DisconnectReason reason);

    /**
     * Serializes a ZooKeeper response and enqueues it for sending.
     *
     * Serializes client response parts and enqueues them into outgoing queue.
     *
     * If both cache key and last modified zxid are provided, the serialized
     * response is caсhed under the provided key, the last modified zxid is
     * stored along with the value. A cache entry is invalidated if the
     * provided last modified zxid is more recent than the stored one.
     *
     * Attention: this function is not thread safe, due to caching not being
     * thread safe.
     *
     * @param h reply header
     * @param r reply payload, can be null
     * @param tag Jute serialization tag, can be null
     * @param cacheKey Key for caching the serialized payload. A null value prevents caching.
     * @param stat Stat information for the the reply payload, used for cache invalidation.
     *             A value of 0 prevents caching.
     * @param opCode The op code appertains to the corresponding request of the response,
     *               used to decide which cache (e.g. read response cache,
     *               list of children response cache, ...) object to look up to when applicable.
     */
    public abstract int sendResponse(ReplyHeader h, Record r, String tag,
                                      String cacheKey, Stat stat, int opCode) throws IOException;

    public int sendResponse(ReplyHeader h, Record r, String tag) throws IOException {
        return sendResponse(h, r, tag, null, null, -1);
    }

    protected byte[] serializeRecord(Record record) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(ZooKeeperServer.intBufferStartingSizeBytes);
        BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
        bos.writeRecord(record, null);
        return baos.toByteArray();
    }

    protected ByteBuffer[] serialize(ReplyHeader h, Record r, String tag,
                                     String cacheKey, Stat stat, int opCode) throws IOException {
        byte[] header = serializeRecord(h);
        byte[] data = null;
        if (r != null) {
            ResponseCache cache = null;
            Counter cacheHit = null, cacheMiss = null;
            switch (opCode) {
                case OpCode.getData : {
                    cache = zkServer.getReadResponseCache();
                    cacheHit = ServerMetrics.getMetrics().RESPONSE_PACKET_CACHE_HITS;
                    cacheMiss = ServerMetrics.getMetrics().RESPONSE_PACKET_CACHE_MISSING;
                    break;
                }
                case OpCode.getChildren2 : {
                    cache = zkServer.getGetChildrenResponseCache();
                    cacheHit = ServerMetrics.getMetrics().RESPONSE_PACKET_GET_CHILDREN_CACHE_HITS;
                    cacheMiss = ServerMetrics.getMetrics().RESPONSE_PACKET_GET_CHILDREN_CACHE_MISSING;
                    break;
                }
                default:
                    // op codes where response cache is not supported.
            }

            if (cache != null && stat != null && cacheKey != null && !cacheKey.endsWith(Quotas.statNode)) {
                // Use cache to get serialized data.
                //
                // NB: Tag is ignored both during cache lookup and serialization,
                // since is is not used in read responses, which are being cached.
                data = cache.get(cacheKey, stat);
                if (data == null) {
                    // Cache miss, serialize the response and put it in cache.
                    data = serializeRecord(r);
                    cache.put(cacheKey, data, stat);
                    cacheMiss.add(1);
                } else {
                    cacheHit.add(1);
                }
            } else {
                data = serializeRecord(r);
            }
        }
        int dataLength = data == null ? 0 : data.length;
        int packetLength = header.length + dataLength;
        ServerStats serverStats = serverStats();
        if (serverStats != null) {
            serverStats.updateClientResponseSize(packetLength);
        }
        ByteBuffer lengthBuffer = ByteBuffer.allocate(4).putInt(packetLength);
        lengthBuffer.rewind();

        int bufferLen = data != null ? 3 : 2;
        ByteBuffer[] buffers = new ByteBuffer[bufferLen];

        buffers[0] = lengthBuffer;
        buffers[1] = ByteBuffer.wrap(header);
        if (data != null) {
            buffers[2] = ByteBuffer.wrap(data);
        }
        return buffers;
    }

    /* notify the client the session is closing and close/cleanup socket */
    public abstract void sendCloseSession();

    public void process(WatchedEvent event) {
        process(event, null);
    }

    public abstract void process(WatchedEvent event, List<ACL> znodeAcl);

    public abstract long getSessionId();

    abstract void setSessionId(long sessionId);

    /** auth info for the cnxn, returns an unmodifyable list */
    public List<Id> getAuthInfo() {
        return Collections.unmodifiableList(new ArrayList<>(authInfo));
    }

    public void addAuthInfo(Id id) {
        authInfo.add(id);
    }

    public boolean removeAuthInfo(Id id) {
        return authInfo.remove(id);
    }

    abstract void sendBuffer(ByteBuffer... buffers);

    abstract void enableRecv();

    void disableRecv() {
        disableRecv(true);
    }

    abstract void disableRecv(boolean waitDisableRecv);

    abstract void setSessionTimeout(int sessionTimeout);

    protected ZooKeeperSaslServer zooKeeperSaslServer = null;

    public static class CloseRequestException extends IOException {

        private static final long serialVersionUID = -7854505709816442681L;
        private DisconnectReason reason;

        public CloseRequestException(String msg, DisconnectReason reason) {
            super(msg);
            this.reason = reason;
        }
        public DisconnectReason getReason() {
            return reason;
        }

    }

    protected static class EndOfStreamException extends IOException {

        private static final long serialVersionUID = -8255690282104294178L;
        private DisconnectReason reason;

        public EndOfStreamException(String msg, DisconnectReason reason) {
            super(msg);
            this.reason = reason;
        }

        public String toString() {
            return "EndOfStreamException: " + getMessage();
        }
        public DisconnectReason getReason() {
            return reason;
        }

    }

    public boolean isStale() {
        return stale;
    }

    public void setStale() {
        stale = true;
    }

    public boolean isInvalid() {
        return invalid;
    }

    public void setInvalid() {
        if (!invalid) {
            if (!stale) {
                sendCloseSession();
            }
            invalid = true;
        }
    }

    protected void packetReceived(long bytes) {
        incrPacketsReceived();
        ServerStats serverStats = serverStats();
        if (serverStats != null) {
            serverStats().incrementPacketsReceived();
        }
        ServerMetrics.getMetrics().BYTES_RECEIVED_COUNT.add(bytes);
    }

    protected void packetSent() {
        incrPacketsSent();
        ServerStats serverStats = serverStats();
        if (serverStats != null) {
            serverStats.incrementPacketsSent();
        }
    }

    protected abstract ServerStats serverStats();

    protected final Date established = new Date();

    protected final AtomicLong packetsReceived = new AtomicLong();
    protected final AtomicLong packetsSent = new AtomicLong();

    protected long minLatency;
    protected long maxLatency;
    protected String lastOp;
    protected long lastCxid;
    protected long lastZxid;
    protected long lastResponseTime;
    protected long lastLatency;

    protected long count;
    protected long totalLatency;
    protected long requestsProcessedCount;
    protected DisconnectReason disconnectReason = DisconnectReason.UNKNOWN;

    public synchronized void resetStats() {
        disconnectReason = DisconnectReason.RESET_COMMAND;
        packetsReceived.set(0);
        packetsSent.set(0);
        minLatency = Long.MAX_VALUE;
        maxLatency = 0;
        lastOp = "NA";
        lastCxid = -1;
        lastZxid = -1;
        lastResponseTime = 0;
        lastLatency = 0;

        count = 0;
        totalLatency = 0;
    }

    protected long incrPacketsReceived() {
        return packetsReceived.incrementAndGet();
    }

    protected long incrPacketsSent() {
        return packetsSent.incrementAndGet();
    }

    protected synchronized void updateStatsForResponse(long cxid, long zxid, String op, long start, long end) {
        // don't overwrite with "special" xids - we're interested
        // in the clients last real operation
        if (cxid >= 0) {
            lastCxid = cxid;
        }
        lastZxid = zxid;
        lastOp = op;
        lastResponseTime = end;
        long elapsed = end - start;
        lastLatency = elapsed;
        if (elapsed < minLatency) {
            minLatency = elapsed;
        }
        if (elapsed > maxLatency) {
            maxLatency = elapsed;
        }
        count++;
        totalLatency += elapsed;
    }

    public Date getEstablished() {
        return (Date) established.clone();
    }

    public long getOutstandingRequests() {
        return outstandingCount.longValue();
    }

    public long getPacketsReceived() {
        return packetsReceived.longValue();
    }

    public long getPacketsSent() {
        return packetsSent.longValue();
    }

    public synchronized long getMinLatency() {
        return minLatency == Long.MAX_VALUE ? 0 : minLatency;
    }

    public synchronized long getAvgLatency() {
        return count == 0 ? 0 : totalLatency / count;
    }

    public synchronized long getMaxLatency() {
        return maxLatency;
    }

    public synchronized String getLastOperation() {
        return lastOp;
    }

    public synchronized long getLastCxid() {
        return lastCxid;
    }

    public synchronized long getLastZxid() {
        return lastZxid;
    }

    public synchronized long getLastResponseTime() {
        return lastResponseTime;
    }

    public synchronized long getLastLatency() {
        return lastLatency;
    }

    /**
     * Prints detailed stats information for the connection.
     *
     * @see #dumpConnectionInfo(PrintWriter, boolean) for brief stats
     */
    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        PrintWriter pwriter = new PrintWriter(sw);
        dumpConnectionInfo(pwriter, false);
        pwriter.flush();
        pwriter.close();
        return sw.toString();
    }

    public abstract InetSocketAddress getRemoteSocketAddress();
    public abstract int getInterestOps();
    public abstract boolean isSecure();
    public abstract Certificate[] getClientCertificateChain();
    public abstract void setClientCertificateChain(Certificate[] chain);

    /**
     * Print information about the connection.
     * @param brief iff true prints brief details, otw full detail
     */
    public synchronized void dumpConnectionInfo(PrintWriter pwriter, boolean brief) {
        pwriter.print(" ");
        pwriter.print(getRemoteSocketAddress());
        pwriter.print("[");
        int interestOps = getInterestOps();
        pwriter.print(interestOps == 0 ? "0" : Integer.toHexString(interestOps));
        pwriter.print("](queued=");
        pwriter.print(getOutstandingRequests());
        pwriter.print(",recved=");
        pwriter.print(getPacketsReceived());
        pwriter.print(",sent=");
        pwriter.print(getPacketsSent());

        if (!brief) {
            long sessionId = getSessionId();
            if (sessionId != 0) {
                pwriter.print(",sid=0x");
                pwriter.print(Long.toHexString(sessionId));
                pwriter.print(",lop=");
                pwriter.print(getLastOperation());
                pwriter.print(",est=");
                pwriter.print(getEstablished().getTime());
                pwriter.print(",to=");
                pwriter.print(getSessionTimeout());
                long lastCxid = getLastCxid();
                if (lastCxid >= 0) {
                    pwriter.print(",lcxid=0x");
                    pwriter.print(Long.toHexString(lastCxid));
                }
                pwriter.print(",lzxid=0x");
                pwriter.print(Long.toHexString(getLastZxid()));
                pwriter.print(",lresp=");
                pwriter.print(getLastResponseTime());
                pwriter.print(",llat=");
                pwriter.print(getLastLatency());
                pwriter.print(",minlat=");
                pwriter.print(getMinLatency());
                pwriter.print(",avglat=");
                pwriter.print(getAvgLatency());
                pwriter.print(",maxlat=");
                pwriter.print(getMaxLatency());
            }
        }
        pwriter.print(")");
    }

    public synchronized Map<String, Object> getConnectionInfo(boolean brief) {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("remote_socket_address", getRemoteSocketAddress());
        info.put("interest_ops", getInterestOps());
        info.put("outstanding_requests", getOutstandingRequests());
        info.put("packets_received", getPacketsReceived());
        info.put("packets_sent", getPacketsSent());
        if (!brief) {
            info.put("session_id", getSessionId());
            info.put("last_operation", getLastOperation());
            info.put("established", getEstablished());
            info.put("session_timeout", getSessionTimeout());
            info.put("last_cxid", getLastCxid());
            info.put("last_zxid", getLastZxid());
            info.put("last_response_time", getLastResponseTime());
            info.put("last_latency", getLastLatency());
            info.put("min_latency", getMinLatency());
            info.put("avg_latency", getAvgLatency());
            info.put("max_latency", getMaxLatency());
        }
        return info;
    }

    /**
     * clean up the socket related to a command and also make sure we flush the
     * data before we do that
     *
     * @param pwriter
     *            the pwriter for a command socket
     */
    public void cleanupWriterSocket(PrintWriter pwriter) {
        try {
            if (pwriter != null) {
                pwriter.flush();
                pwriter.close();
            }
        } catch (Exception e) {
            LOG.info("Error closing PrintWriter ", e);
        } finally {
            try {
                close(DisconnectReason.CLOSE_CONNECTION_COMMAND);
            } catch (Exception e) {
                LOG.error("Error closing a command socket ", e);
            }
        }
    }

    /**
     * @return true if the server is running, false otherwise.
     */
    public boolean isZKServerRunning() {
        return zkServer != null && zkServer.isRunning();
    }

    /**
     * Returns the IP address or empty string.
     */
    public String getHostAddress() {
        InetSocketAddress remoteSocketAddress = getRemoteSocketAddress();
        if (remoteSocketAddress == null) {
            return "";
        }
        InetAddress address = remoteSocketAddress.getAddress();
        if (address == null) {
            return "";
        }
        return address.getHostAddress();
    }

    /**
     * Get session id in hexadecimal notation.
     */
    public String getSessionIdHex() {
        return "0x" + Long.toHexString(getSessionId());
    }
}
