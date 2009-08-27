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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.StringBuffer;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.apache.jute.BinaryOutputArchive;
import org.apache.log4j.Logger;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;

/**
 * This class has the control logic for the Leader.
 */
public class Leader {
    private static final Logger LOG = Logger.getLogger(Leader.class);
    
    static final private boolean nodelay = System.getProperty("leader.nodelay", "true").equals("true");
    static {
        LOG.info("TCP NoDelay set to: " + nodelay);
    }

    static public class Proposal {
        public QuorumPacket packet;

        public HashSet<Long> ackSet = new HashSet<Long>();

        public Request request;

        @Override
        public String toString() {
            return packet.getType() + ", " + packet.getZxid() + ", " + request;
        }
    }

    LeaderZooKeeperServer zk;

    QuorumPeer self;

    // the follower acceptor thread
    FollowerCnxAcceptor cnxAcceptor;
    
    // list of all the followers
    public HashSet<FollowerHandler> followers = new HashSet<FollowerHandler>();

    // list of followers that are ready to follow (i.e synced with the leader)
    public HashSet<FollowerHandler> forwardingFollowers = new HashSet<FollowerHandler>();
    
    //Pending sync requests
    public HashMap<Long,List<FollowerSyncRequest>> pendingSyncs = new HashMap<Long,List<FollowerSyncRequest>>();
    
    //Follower counter
    AtomicLong followerCounter = new AtomicLong(-1);
    /**
     * Adds follower to the leader.
     * 
     * @param follower
     *                instance of follower handle
     */
    void addFollowerHandler(FollowerHandler follower) {
        synchronized (followers) {
            followers.add(follower);
        }
    }

    /**
     * Remove the follower from the followers list
     * 
     * @param follower
     */
    void removeFollowerHandler(FollowerHandler follower) {
        synchronized (forwardingFollowers) {
            forwardingFollowers.remove(follower);
        }
        synchronized (followers) {
            followers.remove(follower);
        }
    }

    boolean isFollowerSynced(FollowerHandler follower){
        synchronized (forwardingFollowers) {
            return forwardingFollowers.contains(follower);
        }        
    }
    
    ServerSocket ss;

    Leader(QuorumPeer self,LeaderZooKeeperServer zk) throws IOException {
        this.self = self;
        try {
            ss = new ServerSocket(self.getQuorumAddress().getPort());
        } catch (BindException e) {
            LOG.error("Couldn't bind to port "
                    + self.getQuorumAddress().getPort(), e);
            throw e;
        }
        this.zk=zk;
    }

    /**
     * This message is for follower to expect diff
     */
    final static int DIFF = 13;
    
    /**
     * This is for follower to truncate its logs 
     */
    final static int TRUNC = 14;
    
    /**
     * This is for follower to download the snapshots
     */
    final static int SNAP = 15;
    
    /**
     * This message type is sent by the leader to indicate it's zxid and if
     * needed, its database.
     */
    final static int NEWLEADER = 10;

    /**
     * This message type is sent by a follower to pass the last zxid. This is here
     * for backward compatibility purposes.
     */
    final static int FOLLOWERINFO = 11;

    /**
     * This message type is sent by the leader to indicate that the follower is
     * now uptodate andt can start responding to clients.
     */
    final static int UPTODATE = 12;

    /**
     * This message type is sent to a leader to request and mutation operation.
     * The payload will consist of a request header followed by a request.
     */
    final static int REQUEST = 1;

    /**
     * This message type is sent by a leader to propose a mutation.
     */
    public final static int PROPOSAL = 2;

    /**
     * This message type is sent by a follower after it has synced a proposal.
     */
    final static int ACK = 3;

    /**
     * This message type is sent by a leader to commit a proposal and cause
     * followers to start serving the corresponding data.
     */
    final static int COMMIT = 4;

    /**
     * This message type is enchanged between follower and leader (initiated by
     * follower) to determine liveliness.
     */
    final static int PING = 5;

    /**
     * This message type is to validate a session that should be active.
     */
    final static int REVALIDATE = 6;

    /**
     * This message is a reply to a synchronize command flushing the pipe
     * between the leader and the follower.
     */
    final static int SYNC = 7;
    
    private ConcurrentMap<Long, Proposal> outstandingProposals = new ConcurrentHashMap<Long, Proposal>();

    ConcurrentLinkedQueue<Proposal> toBeApplied = new ConcurrentLinkedQueue<Proposal>();

    Proposal newLeaderProposal = new Proposal();
    
    class FollowerCnxAcceptor extends Thread{
        private volatile boolean stop = false;
        
        @Override
        public void run() {
            try {
                while (!stop) {
                    try{
                        Socket s = ss.accept();
                        s.setSoTimeout(self.tickTime * self.syncLimit);
                        s.setTcpNoDelay(nodelay);
                        FollowerHandler fh = new FollowerHandler(s, Leader.this);
                        fh.start();
                    } catch (SocketException e) {
                        if (stop) {
                            LOG.info("exception while shutting down acceptor: "
                                    + e);

                            // When Leader.shutdown() calls ss.close(),
                            // the call to accept throws an exception.
                            // We catch and set stop to true.
                            stop = true;
                        } else {
                            throw e;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("Exception while accepting follower", e);
            }
        }
        
        public void halt() {
            stop = true;
        }
    }

    /**
     * This method is main function that is called to lead
     * 
     * @throws IOException
     * @throws InterruptedException
     */
    void lead() throws IOException, InterruptedException {
        zk.registerJMX(new LeaderBean(this, zk), self.jmxLocalPeerBean);

        try {
            self.tick = 0;
            zk.loadData();
            zk.startup();
            long epoch = self.getLastLoggedZxid() >> 32L;
            epoch++;
            zk.setZxid(epoch << 32L);
            zk.dataTree.lastProcessedZxid = zk.getZxid();
            
            synchronized(this){
                lastProposed = zk.getZxid();
            }
            
            newLeaderProposal.packet = new QuorumPacket(NEWLEADER, zk.getZxid(),
                    null, null);
            if ((newLeaderProposal.packet.getZxid() & 0xffffffffL) != 0) {
                LOG.info("NEWLEADER proposal has Zxid of "
                        + Long.toHexString(newLeaderProposal.packet.getZxid()));
            }
            outstandingProposals.put(newLeaderProposal.packet.getZxid(), newLeaderProposal);
            
            // Start thread that waits for connection requests from 
            // new followers.
            cnxAcceptor = new FollowerCnxAcceptor();
            cnxAcceptor.start();
            
            // We have to get at least a majority of servers in sync with
            // us. We do this by waiting for the NEWLEADER packet to get
            // acknowledged
            newLeaderProposal.ackSet.add(self.getId());
            while (!self.getQuorumVerifier().containsQuorum(newLeaderProposal.ackSet)){
            //while (newLeaderProposal.ackCount <= self.quorumPeers.size() / 2) {
                if (self.tick > self.initLimit) {
                    // Followers aren't syncing fast enough,
                    // renounce leadership!
                    StringBuffer ackToString = new StringBuffer();
                    for(Long id : newLeaderProposal.ackSet)
                        ackToString.append(id + ": ");
                    
                    shutdown("Waiting for a quorum of followers, only synced with: " + ackToString);
                    HashSet<Long> followerSet = new HashSet<Long>();
                    for(FollowerHandler f : followers)
                        followerSet.add(f.getSid());
                    
                    if (self.getQuorumVerifier().containsQuorum(followerSet)) {
                    //if (followers.size() >= self.quorumPeers.size() / 2) {
                        LOG.warn("Enough followers present. "+
                                "Perhaps the initTicks need to be increased.");
                    }
                    return;
                }
                Thread.sleep(self.tickTime);
                self.tick++;
            }
            if (!System.getProperty("zookeeper.leaderServes", "yes").equals("no")) {
                self.cnxnFactory.setZooKeeperServer(zk);
            }
            // Everything is a go, simply start counting the ticks
            // WARNING: I couldn't find any wait statement on a synchronized
            // block that would be notified by this notifyAll() call, so
            // I commented it out
            //synchronized (this) {
            //    notifyAll();
            //}
            // We ping twice a tick, so we only update the tick every other
            // iteration
            boolean tickSkip = true;
    
            while (true) {
                Thread.sleep(self.tickTime / 2);
                if (!tickSkip) {
                    self.tick++;
                }
                int syncedCount = 0;
                HashSet<Long> syncedSet = new HashSet<Long>();
                
                // lock on the followers when we use it.
                syncedSet.add(self.getId());
                synchronized (followers) {
                    for (FollowerHandler f : followers) {
                        if (f.synced()) {
                            syncedCount++;
                            syncedSet.add(f.getSid());
                        }
                        f.ping();
                    }
                }
              if (!tickSkip && !self.getQuorumVerifier().containsQuorum(syncedSet)) {
                //if (!tickSkip && syncedCount < self.quorumPeers.size() / 2) {
                    // Lost quorum, shutdown
                    shutdown("Only " + syncedCount + " followers, need "
                            + (self.quorumPeers.size() / 2));
                    // make sure the order is the same!
                    // the leader goes to looking
                    return;
              } 
              tickSkip = !tickSkip;
            }
        } finally {
            zk.unregisterJMX(this);
        }
    }

    boolean isShutdown;

    /**
     * Close down all the FollowerHandlers
     */
    void shutdown(String reason) {
        if (isShutdown) {
            return;
        }
        
        LOG.info("Shutdown called",
                new Exception("shutdown Leader! reason: " + reason));

        if (cnxAcceptor != null) {
            cnxAcceptor.halt();
        }
        
        // NIO should not accept conenctions
        self.cnxnFactory.setZooKeeperServer(null);
        // clear all the connections
        self.cnxnFactory.clear();
        // shutdown the previous zk
        if (zk != null) {
            zk.shutdown();
        }
        try {
            ss.close();
        } catch (IOException e) {
            LOG.warn("Ignoring unexpected exception during close",e);
        }
        synchronized (followers) {
            for (Iterator<FollowerHandler> it = followers.iterator(); it
                    .hasNext();) {
                FollowerHandler f = it.next();
                it.remove();
                f.shutdown();
            }
        }
        isShutdown = true;
    }

    /**
     * Keep a count of acks that are received by the leader for a particular
     * proposal
     * 
     * @param zxid
     *                the zxid of the proposal sent out
     * @param followerAddr
     */
    synchronized public void processAck(long sid, long zxid, SocketAddress followerAddr) {
        boolean first = true;
        
        if (LOG.isTraceEnabled()) {
            LOG.trace("Ack zxid: 0x" + Long.toHexString(zxid));
            for (Proposal p : outstandingProposals.values()) {
                long packetZxid = p.packet.getZxid();
                LOG.trace("outstanding proposal: 0x"
                        + Long.toHexString(packetZxid));
            }
            LOG.trace("outstanding proposals all");
        }
        
        if (outstandingProposals.size() == 0) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("outstanding is 0");
            }
            return;
        }
        if (lastCommitted >= zxid) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("proposal has already been committed, pzxid:"
                        + lastCommitted
                        + " zxid: 0x" + Long.toHexString(zxid));
            }
            // The proposal has already been committed
            return;
        }
        Proposal p = outstandingProposals.get(zxid);
        if (p == null) {
            LOG.warn("Trying to commit future proposal: zxid 0x"
                    + Long.toHexString(zxid) + " from " + followerAddr);
            return;
        }
        
        p.ackSet.add(sid);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Count for zxid: 0x" + Long.toHexString(zxid)
                    + " is " + p.ackSet.size());
        }
        if (self.getQuorumVerifier().containsQuorum(p.ackSet)){        
            if (zxid != lastCommitted+1) {
                LOG.warn("Commiting zxid 0x" + Long.toHexString(zxid)
                        + " from " + followerAddr + " not first!");
                LOG.warn("First is "
                        + (lastCommitted+1));
            }
            outstandingProposals.remove(zxid);
            if (p.request != null) {
                toBeApplied.add(p);
            }
            // We don't commit the new leader proposal
            if ((zxid & 0xffffffffL) != 0) {
                if (p.request == null) {
                    LOG.warn("Going to commmit null: " + p);
                }
                commit(zxid);
                zk.commitProcessor.commit(p.request);
                if(pendingSyncs.containsKey(zxid)){
                    for(FollowerSyncRequest r: pendingSyncs.remove(zxid)) {
                        sendSync(r);
                    }
                }
                return;
            } else {
                lastCommitted = zxid;
            }
        }
    }

    static class ToBeAppliedRequestProcessor implements RequestProcessor {
        private RequestProcessor next;

        private ConcurrentLinkedQueue<Proposal> toBeApplied;

        /**
         * This request processor simply maintains the toBeApplied list. For
         * this to work next must be a FinalRequestProcessor and
         * FinalRequestProcessor.processRequest MUST process the request
         * synchronously!
         * 
         * @param next
         *                a reference to the FinalRequestProcessor
         */
        ToBeAppliedRequestProcessor(RequestProcessor next,
                ConcurrentLinkedQueue<Proposal> toBeApplied) {
            if (!(next instanceof FinalRequestProcessor)) {
                throw new RuntimeException(ToBeAppliedRequestProcessor.class
                        .getName()
                        + " must be connected to "
                        + FinalRequestProcessor.class.getName()
                        + " not "
                        + next.getClass().getName());
            }
            this.toBeApplied = toBeApplied;
            this.next = next;
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.apache.zookeeper.server.RequestProcessor#processRequest(org.apache.zookeeper.server.Request)
         */
        public void processRequest(Request request) {
            // request.addRQRec(">tobe");
            next.processRequest(request);
            Proposal p = toBeApplied.peek();
            if (p != null && p.request != null
                    && p.request.zxid == request.zxid) {
                toBeApplied.remove();
            }
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.apache.zookeeper.server.RequestProcessor#shutdown()
         */
        public void shutdown() {
            next.shutdown();
        }
    }

    /**
     * send a packet to all the followers ready to follow
     * 
     * @param qp
     *                the packet to be sent
     */
    void sendPacket(QuorumPacket qp) {
        synchronized (forwardingFollowers) {
            for (FollowerHandler f : forwardingFollowers) {
                f.queuePacket(qp);
            }
        }
    }

    long lastCommitted = -1;

    /**
     * Create a commit packet and send it to all the members of the quorum
     * 
     * @param zxid
     */
    public void commit(long zxid) {
        synchronized(this){
            lastCommitted = zxid;
        }
        QuorumPacket qp = new QuorumPacket(Leader.COMMIT, zxid, null, null);
        sendPacket(qp);
    }

    long lastProposed;

    /**
     * create a proposal and send it out to all the members
     * 
     * @param request
     * @return the proposal that is queued to send to all the members
     */
    public Proposal propose(Request request) {
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        try {
            request.hdr.serialize(boa, "hdr");
            if (request.txn != null) {
                request.txn.serialize(boa, "txn");
            }
            baos.close();
        } catch (IOException e) {
            LOG.warn("This really should be impossible", e);
        }
        QuorumPacket pp = new QuorumPacket(Leader.PROPOSAL, request.zxid, 
                baos.toByteArray(), null);
        
        Proposal p = new Proposal();
        p.packet = pp;
        p.request = request;
        synchronized (this) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Proposing:: " + request);
            }

            lastProposed = p.packet.getZxid();
            outstandingProposals.put(lastProposed, p);
            sendPacket(pp);
        }
        return p;
    }
            
    /**
     * Process sync requests
     * 
     * @param r the request
     */
    
    synchronized public void processSync(FollowerSyncRequest r){
        if(outstandingProposals.isEmpty()){
            sendSync(r);
        } else {
            List<FollowerSyncRequest> l = pendingSyncs.get(lastProposed);
            if (l == null) {
                l = new ArrayList<FollowerSyncRequest>();
            }
            l.add(r);
            pendingSyncs.put(lastProposed, l);
        }
    }
        
    /**
     * Sends a sync message to the appropriate server
     * 
     * @param f
     * @param r
     */
            
    public void sendSync(FollowerSyncRequest r){
        QuorumPacket qp = new QuorumPacket(Leader.SYNC, 0, null, null);
        r.fh.queuePacket(qp);
    }
                
    /**
     * lets the leader know that a follower is capable of following and is done
     * syncing
     * 
     * @param handler handler of the follower
     * @return last proposed zxid
     */
    synchronized public long startForwarding(FollowerHandler handler,
            long lastSeenZxid) {
        // Queue up any outstanding requests enabling the receipt of
        // new requests
        if (lastProposed > lastSeenZxid) {
            for (Proposal p : toBeApplied) {
                if (p.packet.getZxid() <= lastSeenZxid) {
                    continue;
                }
                handler.queuePacket(p.packet);
                // Since the proposal has been committed we need to send the
                // commit message also
                QuorumPacket qp = new QuorumPacket(Leader.COMMIT, p.packet
                        .getZxid(), null, null);
                handler.queuePacket(qp);
            }
            List<Long>zxids = new ArrayList<Long>(outstandingProposals.keySet());
            Collections.sort(zxids);
            for (Long zxid: zxids) {
                if (zxid <= lastSeenZxid) {
                    continue;
                }
                handler.queuePacket(outstandingProposals.get(zxid).packet);
            }
        }
        synchronized (forwardingFollowers) {
            forwardingFollowers.add(handler);
        }
        
        return lastProposed;
    }

}
