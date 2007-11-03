/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * This class has the control logic for the Follower.
 */
package com.yahoo.zookeeper.server.quorum;

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

import com.yahoo.jute.BinaryInputArchive;
import com.yahoo.jute.BinaryOutputArchive;
import com.yahoo.jute.InputArchive;
import com.yahoo.jute.OutputArchive;
import com.yahoo.jute.Record;
import com.yahoo.zookeeper.server.Request;
import com.yahoo.zookeeper.server.ServerCnxn;
import com.yahoo.zookeeper.server.ZooKeeperServer;
import com.yahoo.zookeeper.server.ZooLog;
import com.yahoo.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import com.yahoo.zookeeper.server.quorum.QuorumPeer.ServerState;
import com.yahoo.zookeeper.txn.TxnHeader;

public class Follower {
    QuorumPeer self;

    FollowerZooKeeperServer zk;

    Follower(QuorumPeer self) {
        this.self = self;
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
    void writePacket(QuorumPacket pp) throws IOException {
        long traceMask = ZooLog.SERVER_PACKET_TRACE_MASK;
        if (pp.getType() == Leader.PING) {
            traceMask = ZooLog.SERVER_PING_TRACE_MASK;
        }
        ZooLog.logQuorumPacket('o', pp, traceMask);
        synchronized (leaderOs) {
            leaderOs.writeRecord(pp, "packet");
            bufferedOutput.flush();
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
        long traceMask = ZooLog.SERVER_PACKET_TRACE_MASK;
        if (pp.getType() == Leader.PING) {
            traceMask = ZooLog.SERVER_PING_TRACE_MASK;
        }
        ZooLog.logQuorumPacket('i', pp, traceMask);
    }

    /**
     * the main method called by the follower to follow the leader
     * 
     * @throws InterruptedException
     */
    void followLeader() throws InterruptedException {
        InetSocketAddress addr = null;
        // Find the leader by id
        for (QuorumServer s : self.quorumPeers) {
            if (s.id == self.currentVote.id) {
                addr = s.addr;
                break;
            }
        }
        if (addr == null) {
            ZooLog.logError("Couldn't find the leader with id = "
                    + self.currentVote.id);
        }
        ZooLog.logWarn("Following " + addr);
        sock = new Socket();
        try {
            QuorumPacket ack = new QuorumPacket(Leader.ACK, 0, null, null);
            sock.setSoTimeout(self.tickTime * self.initLimit);
            for (int tries = 0; tries < 3; tries++) {
                try {
                    sock.connect(addr, self.tickTime * self.syncLimit);
                    sock.setTcpNoDelay(true);
                    break;
                } catch (ConnectException e) {
                    ZooLog.logException(e);
                    if (tries == 2) {
                        throw e;
                    }
                }
                Thread.sleep(1000);
            }
            leaderIs = BinaryInputArchive.getArchive(new BufferedInputStream(
                    sock.getInputStream()));
            bufferedOutput = new BufferedOutputStream(sock.getOutputStream());
            leaderOs = BinaryOutputArchive.getArchive(bufferedOutput);
            QuorumPacket qp = new QuorumPacket();
            qp.setType(Leader.LASTZXID);
            long sentLastZxid = self.getLastLoggedZxid();
            qp.setZxid(sentLastZxid);
            writePacket(qp);
            readPacket(qp);
            if (qp.getType() != Leader.NEWLEADER) {
                ZooLog.logError("First packet should have been NEWLEADER");
                throw new IOException("First packet should have been NEWLEADER");
            }
            zk = new FollowerZooKeeperServer(self.getId(), self.dataDir,
                    self.dataLogDir, this);
            synchronized (zk) {
                if (qp.getZxid() != sentLastZxid) {
                    // The leader is going to dump the database
                    zk.loadData(leaderIs);
                    String signature = leaderIs.readString("signature");
                    if (!signature.equals("BenWasHere")) {
                        ZooLog.logError("Missing signature. Got " + signature);
                        throw new IOException("Missing signature");
                    }
                } else {
                    zk.loadData();
                }
                zk.dataTree.lastProcessedZxid = qp.getZxid();
            }
            ack.setZxid(qp.getZxid() & ~0xffffffffL);
            writePacket(ack);
            sock.setSoTimeout(self.tickTime * self.syncLimit);
            zk.startup();
            while (self.running) {
                readPacket(qp);
                switch (qp.getType()) {
                case Leader.PING:
                    // Send back the ping with our session data
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(bos);
                    HashMap<Long, Integer> touchTable = ((FollowerZooKeeperServer) zk)
                            .getTouchSnapshot();
                    for (Entry<Long, Integer> entry : touchTable.entrySet()) {
                        dos.writeLong(entry.getKey());
                        dos.writeInt(entry.getValue());
                    }
                    qp.setData(bos.toByteArray());
                    writePacket(qp);
                    break;
                case Leader.PROPOSAL:
                    TxnHeader hdr = new TxnHeader();
                    BinaryInputArchive ia = BinaryInputArchive
                            .getArchive(new ByteArrayInputStream(qp.getData()));
                    Record txn = ZooKeeperServer.deserializeTxn(ia, hdr);
                    if (hdr.getZxid() != lastQueued + 1) {
                        ZooLog.logWarn("Got zxid "
                                + Long.toHexString(hdr.getZxid())
                                + " expected "
                                + Long.toHexString(lastQueued + 1));
                    }
                    lastQueued = hdr.getZxid();
                    zk.logRequest(hdr, txn);
                    break;
                case Leader.COMMIT:
                    zk.commit(qp.getZxid());
                    break;
                case Leader.UPTODATE:
                    zk.snapshot();
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
                            ZooLog.logWarn("Missing "
                                    + Long.toHexString(sessionId)
                                    + " for validation");
                        } else {
                            cnxn.finishSessionInit(valid);
                        }
                    }
                    ZooLog.logTextTraceMessage("Session " + sessionId
                            + " is valid: " + valid, ZooLog.SESSION_TRACE_MASK);
                }
            }
        } catch (IOException e) {
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
        } finally {
            self.state = ServerState.LOOKING;
        }
    }

    private long lastQueued;

    ConcurrentHashMap<Long, ServerCnxn> pendingRevalidations = new ConcurrentHashMap<Long, ServerCnxn>();

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
        ZooLog.logTextTraceMessage("To validate session " + clientId,
                ZooLog.SESSION_TRACE_MASK);
        writePacket(qp);
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
        writePacket(qp);
    }

    public long getZxid() {
        try {
            synchronized (zk) {
                return zk.getZxid();
            }
        } catch (NullPointerException e) {
        }
        return -1;
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
        ZooLog.logException(new Exception("shutdown Follower"));
        self.state = ServerState.LOOKING;

    }
}
