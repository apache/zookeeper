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

package org.apache.zookeeper.server.quorum;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.log4j.Logger;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * This class has the control logic for the Follower.
 */
public class Follower {
    private static final Logger LOG = Logger.getLogger(Follower.class);

    static final private boolean nodelay = System.getProperty("follower.nodelay", "true").equals("true");
    static {
        LOG.info("TCP NoDelay set to: " + nodelay);
    }

    QuorumPeer self;

    FollowerZooKeeperServer zk;

    Follower(QuorumPeer self,FollowerZooKeeperServer zk) {
        this.self = self;
        this.zk=zk;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("Follower ").append(sock);
        sb.append(" lastQueuedZxid:").append(lastQueued);
        sb.append(" pendingRevalidationCount:")
            .append(pendingRevalidations.size());
        return sb.toString();
    }

    private InputArchive leaderIs;

    private OutputArchive leaderOs;

    private BufferedOutputStream bufferedOutput;

    public Socket sock;

    /**
     * write a packet to the leader
     *
     * @param pp
     *                the proposal packet to be sent to the leader
     * @throws IOException
     */
    void writePacket(QuorumPacket pp, boolean flush) throws IOException {
        synchronized (leaderOs) {
            if (pp != null) {
                leaderOs.writeRecord(pp, "packet");
            }
            if (flush) {
                bufferedOutput.flush();
            }
        }
    }

    /**
     * read a packet from the leader
     *
     * @param pp
     *                the packet to be instantiated
     * @throws IOException
     */
    void readPacket(QuorumPacket pp) throws IOException {
        synchronized (leaderIs) {
            leaderIs.readRecord(pp, "packet");
        }
        long traceMask = ZooTrace.SERVER_PACKET_TRACE_MASK;
        if (pp.getType() == Leader.PING) {
            traceMask = ZooTrace.SERVER_PING_TRACE_MASK;
        }
        if (LOG.isTraceEnabled()) {
            ZooTrace.logQuorumPacket(LOG, traceMask, 'i', pp);
        }
    }

    /**
     * the main method called by the follower to follow the leader
     *
     * @throws InterruptedException
     */
    void followLeader() throws InterruptedException {
        zk.registerJMX(new FollowerBean(this, zk), self.jmxLocalPeerBean);

        try {
            InetSocketAddress addr = null;
            // Find the leader by id
            Vote current = self.getCurrentVote();
            for (QuorumServer s : self.quorumPeers.values()) {
                if (s.id == current.id) {
                    addr = s.addr;
                    break;
                }
            }
            if (addr == null) {
                LOG.warn("Couldn't find the leader with id = "
                        + current.id);
            }
            LOG.info("Following " + addr);
            sock = new Socket();
            try {
                QuorumPacket ack = new QuorumPacket(Leader.ACK, 0, null, null);
                sock.setSoTimeout(self.tickTime * self.initLimit);
                for (int tries = 0; tries < 5; tries++) {
                    try {
                        //sock = new Socket();
                        //sock.setSoTimeout(self.tickTime * self.initLimit);
                        sock.connect(addr, self.tickTime * self.syncLimit);
                        sock.setTcpNoDelay(nodelay);
                        break;
                    } catch (IOException e) {
                        if (tries == 4) {
                            LOG.error("Unexpected exception",e);
                            throw e;
                        } else {
                            LOG.warn("Unexpected exception, tries="+tries,e);
                            sock = new Socket();
                            sock.setSoTimeout(self.tickTime * self.initLimit);
                        }
                    }
                    Thread.sleep(1000);
                }
                leaderIs = BinaryInputArchive.getArchive(new BufferedInputStream(
                        sock.getInputStream()));
                bufferedOutput = new BufferedOutputStream(sock.getOutputStream());
                leaderOs = BinaryOutputArchive.getArchive(bufferedOutput);
                
                /*
                 * Send follower info, including last zxid and sid
                 */
                QuorumPacket qp = new QuorumPacket();
                qp.setType(Leader.FOLLOWERINFO);
                long sentLastZxid = self.getLastLoggedZxid();
                qp.setZxid(sentLastZxid);
                
                /*
                 * Add sid to payload
                 */
                ByteArrayOutputStream bsid = new ByteArrayOutputStream();
                DataOutputStream dsid = new DataOutputStream(bsid);
                dsid.writeLong(self.getId());
                qp.setData(bsid.toByteArray());
                
                writePacket(qp, true);
                readPacket(qp);
                long newLeaderZxid = qp.getZxid();
                
                //check to see if the leader zxid is lower than ours
                //this should never happen but is just a safety check
                long lastLoggedZxid = sentLastZxid;
                if ((newLeaderZxid >> 32L) < (lastLoggedZxid >> 32L)) {
                    LOG.fatal("Leader epoch " + Long.toHexString(newLeaderZxid >> 32L)
                            + " is less than our zxid " + Long.toHexString(lastLoggedZxid >> 32L));
                    throw new IOException("Error: Epoch of leader is lower");
                }

                if (qp.getType() != Leader.NEWLEADER) {
                    LOG.error("First packet should have been NEWLEADER");
                    throw new IOException("First packet should have been NEWLEADER");
                }
                readPacket(qp);
                synchronized (zk) {
                    if (qp.getType() == Leader.DIFF) {
                        LOG.info("Getting a diff from the leader 0x" + Long.toHexString(qp.getZxid()));
                        zk.loadData();
                    }
                    else if (qp.getType() == Leader.SNAP) {
                        LOG.info("Getting a snapshot from leader");
                        // The leader is going to dump the database
                        zk.deserializeSnapshot(leaderIs);
                        String signature = leaderIs.readString("signature");
                        if (!signature.equals("BenWasHere")) {
                            LOG.error("Missing signature. Got " + signature);
                            throw new IOException("Missing signature");
                        }
                    } else if (qp.getType() == Leader.TRUNC) {
                        //we need to truncate the log to the lastzxid of the leader
                        LOG.warn("Truncating log to get in sync with the leader 0x"
                                + Long.toHexString(qp.getZxid()));
                        boolean truncated=zk.getLogWriter().truncateLog(qp.getZxid());
                        if (!truncated) {
                            // not able to truncate the log
                            LOG.fatal("Not able to truncate the log "
                                    + Long.toHexString(qp.getZxid()));
                            System.exit(13);
                        }
    
                        zk.loadData();
                    }
                    else {
                        LOG.fatal("Got unexpected packet from leader "
                                + qp.getType() + " exiting ... " );
                        System.exit(13);

                    }
                    zk.dataTree.lastProcessedZxid = newLeaderZxid;
                }
                ack.setZxid(newLeaderZxid & ~0xffffffffL);
                writePacket(ack, true);
                sock.setSoTimeout(self.tickTime * self.syncLimit);
                zk.startup();
                
                while (self.running) {
                    readPacket(qp);
                    switch (qp.getType()) {
                    case Leader.PING:
                        // Send back the ping with our session data
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        DataOutputStream dos = new DataOutputStream(bos);
                        HashMap<Long, Integer> touchTable = zk
                                .getTouchSnapshot();
                        for (Entry<Long, Integer> entry : touchTable.entrySet()) {
                            dos.writeLong(entry.getKey());
                            dos.writeInt(entry.getValue());
                        }
                        qp.setData(bos.toByteArray());
                        writePacket(qp, true);
                        break;
                    case Leader.PROPOSAL:
                        TxnHeader hdr = new TxnHeader();
                        BinaryInputArchive ia = BinaryInputArchive
                                .getArchive(new ByteArrayInputStream(qp.getData()));
                        Record txn = SerializeUtils.deserializeTxn(ia, hdr);
                        if (hdr.getZxid() != lastQueued + 1) {
                            LOG.warn("Got zxid 0x"
                                    + Long.toHexString(hdr.getZxid())
                                    + " expected 0x"
                                    + Long.toHexString(lastQueued + 1));
                        }
                        lastQueued = hdr.getZxid();
                        zk.logRequest(hdr, txn);
                        break;
                    case Leader.COMMIT:
                        zk.commit(qp.getZxid());
                        break;
                    case Leader.UPTODATE:
                        zk.takeSnapshot();
                        self.cnxnFactory.setZooKeeperServer(zk);
                        break;
                    case Leader.REVALIDATE:
                        ByteArrayInputStream bis = new ByteArrayInputStream(qp
                                .getData());
                        DataInputStream dis = new DataInputStream(bis);
                        long sessionId = dis.readLong();
                        boolean valid = dis.readBoolean();
                        synchronized (pendingRevalidations) {
                            ServerCnxn cnxn = pendingRevalidations
                                    .remove(sessionId);
                            if (cnxn == null) {
                                LOG.warn("Missing session 0x"
                                        + Long.toHexString(sessionId)
                                        + " for validation");
                            } else {
                                cnxn.finishSessionInit(valid);
                            }
                        }
                        if (LOG.isTraceEnabled()) {
                            ZooTrace.logTraceMessage(LOG,
                                    ZooTrace.SESSION_TRACE_MASK,
                                    "Session 0x" + Long.toHexString(sessionId)
                                    + " is valid: " + valid);
                        }
                        break;
                    case Leader.SYNC:
                        zk.sync();
                        break;
                    }
                }
            } catch (IOException e) {
                LOG.warn("Exception when following the leader", e);
                try {
                    sock.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
    
                synchronized (pendingRevalidations) {
                    // clear pending revalitions
                    pendingRevalidations.clear();
                    pendingRevalidations.notifyAll();
                }
            }
        } finally {
            zk.unregisterJMX(this);
        }
    }

    private long lastQueued;

    final ConcurrentHashMap<Long, ServerCnxn> pendingRevalidations =
        new ConcurrentHashMap<Long, ServerCnxn>();
    
    public int getPendingRevalidationsCount() {
        return pendingRevalidations.size();
    }

    /**
     * validate a seesion for a client
     *
     * @param clientId
     *                the client to be revailidated
     * @param timeout
     *                the timeout for which the session is valid
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    void validateSession(ServerCnxn cnxn, long clientId, int timeout)
            throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(clientId);
        dos.writeInt(timeout);
        dos.close();
        QuorumPacket qp = new QuorumPacket(Leader.REVALIDATE, -1, baos
                .toByteArray(), null);
        pendingRevalidations.put(clientId, cnxn);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG,
                                     ZooTrace.SESSION_TRACE_MASK,
                                     "To validate session 0x"
                                     + Long.toHexString(clientId));
        }
        writePacket(qp, true);
    }

    /**
     * send a request packet to the leader
     *
     * @param request
     *                the request from the client
     * @throws IOException
     */
    void request(Request request) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream oa = new DataOutputStream(baos);
        oa.writeLong(request.sessionId);
        oa.writeInt(request.cxid);
        oa.writeInt(request.type);
        if (request.request != null) {
            request.request.rewind();
            int len = request.request.remaining();
            byte b[] = new byte[len];
            request.request.get(b);
            request.request.rewind();
            oa.write(b);
        }
        oa.close();
        QuorumPacket qp = new QuorumPacket(Leader.REQUEST, -1, baos
                .toByteArray(), request.authInfo);
//        QuorumPacket qp;
//        if(request.type == OpCode.sync){
//            qp = new QuorumPacket(Leader.SYNC, -1, baos
//                    .toByteArray(), request.authInfo);
//        }
//        else{
//        qp = new QuorumPacket(Leader.REQUEST, -1, baos
//                .toByteArray(), request.authInfo);
//        }
        writePacket(qp, true);
    }

    public long getZxid() {
        try {
            synchronized (zk) {
                return zk.getZxid();
            }
        } catch (NullPointerException e) {
            LOG.warn("error getting zxid", e);
        }
        return -1;
    }
    
    public long getLastQueued() {
        return lastQueued;
    }

    public void shutdown() {
        // set the zookeeper server to null
        self.cnxnFactory.setZooKeeperServer(null);
        // clear all the connections
        self.cnxnFactory.clear();
        // shutdown previous zookeeper
        if (zk != null) {
            zk.shutdown();

        }
        LOG.info("shutdown called", new Exception("shutdown Follower"));
    }
}
