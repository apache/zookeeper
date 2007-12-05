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

package com.yahoo.zookeeper.server.quorum;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

import com.yahoo.jute.BinaryInputArchive;
import com.yahoo.jute.BinaryOutputArchive;
import com.yahoo.jute.Record;
import com.yahoo.zookeeper.ZooDefs.OpCode;
import com.yahoo.zookeeper.server.ZooKeeperServer;
import com.yahoo.zookeeper.server.ZooLog;
import com.yahoo.zookeeper.txn.TxnHeader;

/**
 * There will be an instance of this class created by the Leader for each
 * follower.All communication for a given Follower will be handled by this
 * class.
 */
public class FollowerHandler extends Thread {
    public Socket s;

    Leader leader;

    long tickOfLastAck;

    /**
     * The packets to be sent to the follower
     */
    LinkedBlockingQueue<QuorumPacket> queuedPackets = new LinkedBlockingQueue<QuorumPacket>();

    private BinaryInputArchive ia;

    private BinaryOutputArchive oa;

    private BufferedOutputStream bufferedOutput;

    FollowerHandler(Socket s, Leader leader) throws IOException {
        super("FollowerHandler-" + s.getRemoteSocketAddress());
        this.s = s;
        this.leader = leader;
        leader.addFollowerHandler(this);
        start();
    }

    /**
     * If this packet is queued, the sender thread will exit
     */
    QuorumPacket proposalOfDeath = new QuorumPacket();

    /**
     * This method will use the thread to send packets added to the
     * queuedPackets list
     * 
     * @throws InterruptedException
     */
    private void sendPackets() throws InterruptedException {
        long traceMask = ZooLog.SERVER_PACKET_TRACE_MASK;
        while (true) {
            QuorumPacket p;
            p = queuedPackets.take();

            if (p == proposalOfDeath) {
                // Packet of death!
                break;
            }
            if (p.getType() == Leader.PING) {
                traceMask = ZooLog.SERVER_PING_TRACE_MASK;
            }
            ZooLog.logQuorumPacket('o', p, traceMask);
            try {
                oa.writeRecord(p, "packet");
                bufferedOutput.flush();
            } catch (IOException e) {
                if (!s.isClosed()) {
                    ZooLog.logException(e);
                }
                break;
            }
        }
    }

    static public String packetToString(QuorumPacket p) {
        if (true)
            return null;
        String type = null;
        String mess = null;
        Record txn = null;
        switch (p.getType()) {
        case Leader.ACK:
            type = "ACK";
            break;
        case Leader.COMMIT:
            type = "COMMIT";
            break;
        case Leader.LASTZXID:
            type = "LASTZXID";
            break;
        case Leader.NEWLEADER:
            type = "NEWLEADER";
            break;
        case Leader.PING:
            type = "PING";
            break;
        case Leader.PROPOSAL:
            type = "PROPOSAL";
            BinaryInputArchive ia = BinaryInputArchive
                    .getArchive(new ByteArrayInputStream(p.getData()));
            TxnHeader hdr = new TxnHeader();
            try {
                txn = ZooKeeperServer.deserializeTxn(ia, hdr);
                // mess = "transaction: " + txn.toString();
            } catch (IOException e) {
                ZooLog.logException(e);
            }
            break;
        case Leader.REQUEST:
            type = "REQUEST";
            break;
        case Leader.REVALIDATE:
            type = "REVALIDATE";
            ByteArrayInputStream bis = new ByteArrayInputStream(p.getData());
            DataInputStream dis = new DataInputStream(bis);
            try {
                long id = dis.readLong();
                mess = " sessionid = " + id;
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            break;
        case Leader.UPTODATE:
            type = "UPTODATE";
            break;
        default:
            type = "UNKNOWN" + p.getType();
        }
        String entry = null;
        if (type != null) {
            entry = type + " " + Long.toHexString(p.getZxid()) + " " + mess;
        }
        return entry;
    }

    /**
     * This thread will receive packets from the follower and process them and
     * also listen to new connections from new followers.
     */
    public void run() {
        try {

            ia = BinaryInputArchive.getArchive(new BufferedInputStream(s
                    .getInputStream()));
            bufferedOutput = new BufferedOutputStream(s.getOutputStream());
            oa = BinaryOutputArchive.getArchive(bufferedOutput);

            QuorumPacket qp = new QuorumPacket();
            ia.readRecord(qp, "packet");
            if (qp.getType() != Leader.LASTZXID) {
                ZooLog.logError("First packet " + qp.toString()
                        + " is not LASTZXID!");
                return;
            }
            long peerLastZxid = qp.getZxid();
            long leaderLastZxid = leader.startForwarding(this, peerLastZxid);
            QuorumPacket newLeaderQP = new QuorumPacket(Leader.NEWLEADER,
                    leaderLastZxid, null, null);
            oa.writeRecord(newLeaderQP, "packet");
            if (leaderLastZxid != peerLastZxid) {
                ZooLog.logWarn("sending Snapshot");
                // Dump data to follower
                leader.zk.snapshot(oa);
                oa.writeString("BenWasHere", "signature");
            }
            bufferedOutput.flush();
            //
            // Mutation packets will be queued during the serialize,
            // so we need to mark when the follower can actually start
            // using the data
            //
            queuedPackets
                    .add(new QuorumPacket(Leader.UPTODATE, -1, null, null));

            // Start sending packets
            new Thread() {
                public void run() {
                    Thread.currentThread().setName(
                            "Sender-" + s.getRemoteSocketAddress());
                    try {
                        sendPackets();
                    } catch (InterruptedException e) {
                        ZooLog.logException(e);
                    }
                }
            }.start();

            while (true) {
                qp = new QuorumPacket();
                ia.readRecord(qp, "packet");

                long traceMask = ZooLog.SERVER_PACKET_TRACE_MASK;
                if (qp.getType() == Leader.PING) {
                    traceMask = ZooLog.SERVER_PING_TRACE_MASK;
                }
                ZooLog.logQuorumPacket('i', qp, traceMask);
                tickOfLastAck = leader.self.tick;
                
                                
                ByteBuffer bb;
                long sessionId;
                int cxid;
                int type;
                
                switch (qp.getType()) {
                case Leader.ACK:
                    leader.processAck(qp.getZxid(), s.getLocalSocketAddress());
                    break;
                case Leader.PING:
                    // Process the touches
                    ByteArrayInputStream bis = new ByteArrayInputStream(qp
                            .getData());
                    DataInputStream dis = new DataInputStream(bis);
                    while (dis.available() > 0) {
                        long sess = dis.readLong();
                        int to = dis.readInt();
                        leader.zk.touch(sess, to);
                    }
                    break;
                case Leader.REVALIDATE:
                    bis = new ByteArrayInputStream(qp.getData());
                    dis = new DataInputStream(bis);
                    long id = dis.readLong();
                    int to = dis.readInt();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(bos);
                    dos.writeLong(id);
                    boolean valid = leader.zk.touch(id, to);
                    ZooLog.logTextTraceMessage("Session " + id + " is valid: "
                            + valid, ZooLog.SESSION_TRACE_MASK);
                    dos.writeBoolean(valid);
                    qp.setData(bos.toByteArray());
                    queuedPackets.add(qp);
                    break;
                case Leader.REQUEST:
                    bb = ByteBuffer.wrap(qp.getData());
                    sessionId = bb.getLong();
                    cxid = bb.getInt();
                    type = bb.getInt();
                    bb = bb.slice();
                    if(type == OpCode.sync){
                    	leader.setSyncHandler(this, sessionId);
                    }
                    leader.zk.submitRequest(null, sessionId, type, cxid, bb,
                            qp.getAuthinfo());
                    break;
                default:
                }
            }
        } catch (IOException e) {
            if (s != null && !s.isClosed()) {
                ZooLog.logException(e);
            }
        } catch (InterruptedException e) {
            ZooLog.logException(e);
        } finally {
            ZooLog.logWarn("******* GOODBYE " + s.getRemoteSocketAddress()
                    + " ********");
            // Send the packet of death
            try {
                queuedPackets.put(proposalOfDeath);
            } catch (InterruptedException e) {
                ZooLog.logException(e);
            }
            shutdown();
        }
    }

    public void shutdown() {
        try {
            if (s != null && !s.isClosed()) {
                s.close();
            }
        } catch (IOException e) {
            ZooLog.logException(e);
        }
        leader.removeFollowerHandler(this);
    }

    public long tickOfLastAck() {
        return tickOfLastAck;
    }

    /**
     * ping calls from the leader to the followers
     */
    public void ping() {
        QuorumPacket ping = new QuorumPacket(Leader.PING, leader.lastProposed,
                null, null);
        queuePacket(ping);
    }

    void queuePacket(QuorumPacket p) {
        queuedPackets.add(p);
    }

    public boolean synced() {
        return isAlive()
                && tickOfLastAck >= leader.self.tick - leader.self.syncLimit;
    }
}
