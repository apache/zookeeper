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
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class has the control logic for the Leader.
 */
public class Leader {
    private static final Logger LOG = LoggerFactory.getLogger(Leader.class);

    static final private boolean nodelay = System.getProperty("leader.nodelay", "true").equals("true");
    static {
        LOG.info("TCP NoDelay set to: " + nodelay);
    }

    static public class Proposal {
        public QuorumPacket packet;

        private ArrayList<QuorumVerifierAcksetPair> qvAcksetPairs = new ArrayList<QuorumVerifierAcksetPair>();

        public Request request;

        @Override
        public String toString() {
            return packet.getType() + ", " + packet.getZxid() + ", " + request;
        }

        public void addQuorumVerifier(QuorumVerifier qv) {
            qvAcksetPairs.add(new QuorumVerifierAcksetPair(qv,
                    new HashSet<Long>(qv.getVotingMembers().size())));
        }

        public boolean addAck(Long sid) {
            boolean change = false;
            for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
                if (qvAckset.getQuorumVerifier().getVotingMembers().containsKey(sid)) {
                    qvAckset.getAckset().add(sid);
                    change = true;
                }
            }
            return change;
        }

        public boolean hasAllQuorums() {
            for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
                if (!qvAckset.getQuorumVerifier().containsQuorum(qvAckset.getAckset()))
                    return false;
            }
            return true;
        }
        
        public String ackSetsToString(){
            StringBuilder sb = new StringBuilder();
            
            for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
                sb.append(qvAckset.getAckset().toString()).append(",");
            }
            
            return sb.substring(0, sb.length()-1);
        }

        public static class QuorumVerifierAcksetPair {
            private final QuorumVerifier _qv;
            private final HashSet<Long> _ackset;

            public QuorumVerifierAcksetPair(QuorumVerifier qv, HashSet<Long> ackset) {                
                _qv = qv;
                _ackset = ackset;
            }

            public QuorumVerifier getQuorumVerifier() {
                return _qv;
            }

            public HashSet<Long> getAckset() {
                return _ackset;
            }
        }
    }

    final LeaderZooKeeperServer zk;

    final QuorumPeer self;

    private boolean quorumFormed = false;

    // the follower acceptor thread
    volatile LearnerCnxAcceptor cnxAcceptor = null;

    // list of all the followers
    private final HashSet<LearnerHandler> learners =
        new HashSet<LearnerHandler>();

    /**
     * Returns a copy of the current learner snapshot
     */
    public List<LearnerHandler> getLearners() {
        synchronized (learners) {
            return new ArrayList<LearnerHandler>(learners);
        }
    }

    // list of followers that are ready to follow (i.e synced with the leader)
    private final HashSet<LearnerHandler> forwardingFollowers =
        new HashSet<LearnerHandler>();

    /**
     * Returns a copy of the current forwarding follower snapshot
     */
    public List<LearnerHandler> getForwardingFollowers() {
        synchronized (forwardingFollowers) {
            return new ArrayList<LearnerHandler>(forwardingFollowers);
        }
    }

    private void addForwardingFollower(LearnerHandler lh) {
        synchronized (forwardingFollowers) {
            forwardingFollowers.add(lh);
        }
    }

    private final HashSet<LearnerHandler> observingLearners =
        new HashSet<LearnerHandler>();

    /**
     * Returns a copy of the current observer snapshot
     */
    public List<LearnerHandler> getObservingLearners() {
        synchronized (observingLearners) {
            return new ArrayList<LearnerHandler>(observingLearners);
        }
    }

    private void addObserverLearnerHandler(LearnerHandler lh) {
        synchronized (observingLearners) {
            observingLearners.add(lh);
        }
    }

    // Pending sync requests. Must access under 'this' lock.
    private final HashMap<Long,List<LearnerSyncRequest>> pendingSyncs =
        new HashMap<Long,List<LearnerSyncRequest>>();

    synchronized public int getNumPendingSyncs() {
        return pendingSyncs.size();
    }

    //Follower counter
    final AtomicLong followerCounter = new AtomicLong(-1);

    /**
     * Adds peer to the leader.
     *
     * @param learner
     *                instance of learner handle
     */
    void addLearnerHandler(LearnerHandler learner) {
        synchronized (learners) {
            learners.add(learner);
        }
    }

    /**
     * Remove the learner from the learner list
     *
     * @param peer
     */
    void removeLearnerHandler(LearnerHandler peer) {
        synchronized (forwardingFollowers) {
            forwardingFollowers.remove(peer);
        }
        synchronized (learners) {
            learners.remove(peer);
        }
        synchronized (observingLearners) {
            observingLearners.remove(peer);
        }
    }

    boolean isLearnerSynced(LearnerHandler peer){
        synchronized (forwardingFollowers) {
            return forwardingFollowers.contains(peer);
        }
    }


    /**
     * Returns true if a quorum in qv is connected and synced with the leader
     * and false otherwise
     *  
     * @param qv, a QuorumVerifier
     */
    public boolean isQuorumSynced(QuorumVerifier qv) {
       HashSet<Long> ids = new HashSet<Long>();
       if (qv.getVotingMembers().containsKey(self.getId()))
           ids.add(self.getId());
       synchronized (forwardingFollowers) {
           for (LearnerHandler learnerHandler: forwardingFollowers){
               if (learnerHandler.synced() && qv.getVotingMembers().containsKey(learnerHandler.getSid())){
                   ids.add(learnerHandler.getSid());
               }
           }
       }
       return qv.containsQuorum(ids);
    }
    
    private final ServerSocket ss;

    Leader(QuorumPeer self,LeaderZooKeeperServer zk) throws IOException {
        this.self = self;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(self.getQuorumAddress().getPort()));
        } catch (BindException e) {
            LOG.error("Couldn't bind to port "
                    + self.getQuorumAddress().getPort(), e);
            throw e;
        }
        this.zk = zk;
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
     * This tells the leader that the connecting peer is actually an observer
     */
    final static int OBSERVERINFO = 16;

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
     * This message is the first that a follower receives from the leader.
     * It has the protocol version and the epoch of the leader.
     */
    public static final int LEADERINFO = 17;

    /**
     * This message is used by the follow to ack a proposed epoch.
     */
    public static final int ACKEPOCH = 18;

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

    /**
     * This message type informs observers of a committed proposal.
     */
    final static int INFORM = 8;
    
    /**
     * Similar to COMMIT, only for a reconfig operation.
     */
    final static int COMMITANDACTIVATE = 9;
    
    /**
     * Similar to INFORM, only for a reconfig operation.
     */
    final static int INFORMANDACTIVATE = 19;
    
    final ConcurrentMap<Long, Proposal> outstandingProposals = new ConcurrentHashMap<Long, Proposal>();

    private final ConcurrentLinkedQueue<Proposal> toBeApplied = new ConcurrentLinkedQueue<Proposal>();

    private final Proposal newLeaderProposal = new Proposal();

    class LearnerCnxAcceptor extends Thread{
        private volatile boolean stop = false;

        @Override
        public void run() {
            try {
                while (!stop) {
                    try{
                        Socket s = ss.accept();
                        // start with the initLimit, once the ack is processed
                        // in LearnerHandler switch to the syncLimit
                        s.setSoTimeout(self.tickTime * self.initLimit);
                        s.setTcpNoDelay(nodelay);
                        LearnerHandler fh = new LearnerHandler(s, Leader.this);
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

    StateSummary leaderStateSummary;

    long epoch = -1;
    boolean waitingForNewEpoch = true;

    // when a reconfig occurs where the leader is removed or becomes an observer, 
   // it does not commit ops after committing the reconfig
    boolean allowedToCommit = true;     
    /**
     * This method is main function that is called to lead
     *
     * @throws IOException
     * @throws InterruptedException
     */
    void lead() throws IOException, InterruptedException {
        self.end_fle = System.currentTimeMillis();
        LOG.info("LEADING - LEADER ELECTION TOOK - " +
              (self.end_fle - self.start_fle));
        self.start_fle = 0;
        self.end_fle = 0;

        zk.registerJMX(new LeaderBean(this, zk), self.jmxLocalPeerBean);

        try {
            self.tick = 0;
            zk.loadData();

            leaderStateSummary = new StateSummary(self.getCurrentEpoch(), zk.getLastProcessedZxid());

            // Start thread that waits for connection requests from
            // new followers.
            cnxAcceptor = new LearnerCnxAcceptor();
            cnxAcceptor.setName("LearnerCnxAcceptor-" + ss.getLocalSocketAddress());
            cnxAcceptor.start();

            long epoch = getEpochToPropose(self.getId(), self.getAcceptedEpoch());

            zk.setZxid(ZxidUtils.makeZxid(epoch, 0));

            synchronized(this){
                lastProposed = zk.getZxid();
            }

            newLeaderProposal.packet = new QuorumPacket(NEWLEADER, zk.getZxid(),
                   null, null);


            if ((newLeaderProposal.packet.getZxid() & 0xffffffffL) != 0) {
                LOG.info("NEWLEADER proposal has Zxid of "
                        + Long.toHexString(newLeaderProposal.packet.getZxid()));
            }

            newLeaderProposal.addQuorumVerifier(self.getQuorumVerifier());
            if (self.getLastSeenQuorumVerifier().getVersion() > self.getQuorumVerifier().getVersion()){
               newLeaderProposal.addQuorumVerifier(self.getLastSeenQuorumVerifier());
            }
            
            // We have to get at least a majority of servers in sync with
            // us. We do this by waiting for the NEWLEADER packet to get
            // acknowledged
                       
             waitForEpochAck(self.getId(), leaderStateSummary);
             self.setCurrentEpoch(epoch);    
            
             try {
                 waitForNewLeaderAck(self.getId(), zk.getZxid(), LearnerType.PARTICIPANT);
             } catch (InterruptedException e) {
                 shutdown("Waiting for a quorum of followers, only synced with sids: [ "
                         + newLeaderProposal.ackSetsToString() + " ]");
                 HashSet<Long> followerSet = new HashSet<Long>();

                 for(LearnerHandler f : getLearners()) {
                     if (self.getQuorumVerifier().getVotingMembers().containsKey(f.getSid())){
                         followerSet.add(f.getSid());
                     }
                 }    
                 boolean initTicksShouldBeIncreased = true;
                 for (Proposal.QuorumVerifierAcksetPair qvAckset:newLeaderProposal.qvAcksetPairs) {
                     if (!qvAckset.getQuorumVerifier().containsQuorum(followerSet)) {
                         initTicksShouldBeIncreased = false;
                         break;
                     }
                 }                  
                 if (initTicksShouldBeIncreased) {
                     LOG.warn("Enough followers present. "+
                             "Perhaps the initTicks need to be increased.");
                 }
                 return;
             }

             startZkServer();
             
            /**
             * WARNING: do not use this for anything other than QA testing
             * on a real cluster. Specifically to enable verification that quorum
             * can handle the lower 32bit roll-over issue identified in
             * ZOOKEEPER-1277. Without this option it would take a very long
             * time (on order of a month say) to see the 4 billion writes
             * necessary to cause the roll-over to occur.
             *
             * This field allows you to override the zxid of the server. Typically
             * you'll want to set it to something like 0xfffffff0 and then
             * start the quorum, run some operations and see the re-election.
             */
            String initialZxid = System.getProperty("zookeeper.testingonly.initialZxid");
            if (initialZxid != null) {
                long zxid = Long.parseLong(initialZxid);
                zk.setZxid((zk.getZxid() & 0xffffffff00000000L) | zxid);
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
                HashSet<Long> syncedSet = new HashSet<Long>();

                // lock on the followers when we use it.
                syncedSet.add(self.getId());

                for (LearnerHandler f : getLearners()) {
                    // Synced set is used to check we have a supporting quorum, so only
                    // PARTICIPANT, not OBSERVER, learners should be used
                    if (f.synced() && self.getQuorumVerifier().getVotingMembers().containsKey(f.getSid())) {
                        syncedSet.add(f.getSid());
                    }
                    f.ping();
                }

              if (!tickSkip && !self.getQuorumVerifier().containsQuorum(syncedSet)) {
                  //if (!tickSkip && syncedCount < self.quorumPeers.size() / 2) {
                  // Lost quorum, shutdown
                  shutdown("Not sufficient followers synced, only synced with sids: [ "
                          + getSidSetString(syncedSet) + " ]");
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
     * Close down all the LearnerHandlers
     */
    void shutdown(String reason) {
        LOG.info("Shutting down");

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
        try {
            ss.close();
        } catch (IOException e) {
            LOG.warn("Ignoring unexpected exception during close",e);
        }
        // clear all the connections
        self.cnxnFactory.closeAll();
        // shutdown the previous zk
        if (zk != null) {
            zk.shutdown();
        }
        synchronized (learners) {
            for (Iterator<LearnerHandler> it = learners.iterator(); it
                    .hasNext();) {
                LearnerHandler f = it.next();
                it.remove();
                f.shutdown();
            }
        }
        isShutdown = true;
    }

    /** In a reconfig operation, this method attempts to find the best leader for next configuration.
     *  If the current leader is a voter in the next configuartion, then it remains the leader.
     *  Otherwise, choose one of the new voters that acked the reconfiguartion, such that it is as   
     * up-to-date as possible, i.e., acked as many outstanding proposals as possible.
     *  
     * @param reconfigProposal
     * @param zxid of the reconfigProposal
     * @return server if of the designated leader
     */
    
    private long getDesignatedLeader(Proposal reconfigProposal, long zxid) {
       //new configuration
       Proposal.QuorumVerifierAcksetPair newQVAcksetPair = reconfigProposal.qvAcksetPairs.get(reconfigProposal.qvAcksetPairs.size()-1);        
       
       //check if I'm in the new configuration with the same quorum address - 
       // if so, I'll remain the leader    
       if (newQVAcksetPair.getQuorumVerifier().getVotingMembers().containsKey(self.getId()) && 
               newQVAcksetPair.getQuorumVerifier().getVotingMembers().get(self.getId()).addr.equals(self.getQuorumAddress())){  
           return self.getId();
       }
       // start with an initial set of candidates that are voters from new config that 
       // acknowledged the reconfig op (there must be a quorum). Choose one of them as 
       // current leader candidate
       HashSet<Long> candidates = new HashSet<Long>(newQVAcksetPair.getAckset());
       candidates.remove(self.getId()); // if we're here, I shouldn't be the leader
       long curCandidate = candidates.iterator().next();
       
       //go over outstanding ops in order, and try to find a candidate that acked the most ops.
       //this way it will be the most up-to-date and we'll minimize the number of ops that get dropped
       
       long curZxid = zxid + 1;
       Proposal p = outstandingProposals.get(curZxid);
               
       while (p!=null && !candidates.isEmpty()) {                              
           for (Proposal.QuorumVerifierAcksetPair qvAckset: p.qvAcksetPairs){ 
               //reduce the set of candidates to those that acknowledged p
               candidates.retainAll(qvAckset.getAckset());
               //no candidate acked p, return the best candidate found so far
               if (candidates.isEmpty()) return curCandidate;
               //update the current candidate, and if it is the only one remaining, return it
               curCandidate = candidates.iterator().next();
               if (candidates.size() == 1) return curCandidate;
           }      
           curZxid++;
           p = outstandingProposals.get(curZxid);
       }
       
       return curCandidate;
    }

    /**
     * @return True if committed, otherwise false.
     * @param a proposal p
     **/
    synchronized public boolean tryToCommit(Proposal p, long zxid, SocketAddress followerAddr) {       
       // make sure that ops are committed in order. With reconfigurations it is now possible
       // that different operations wait for different sets of acks, and we still want to enforce
       // that they are committed in order. Currently we only permit one outstanding reconfiguration
       // such that the reconfiguration and subsequent outstanding ops proposed while the reconfig is
       // pending all wait for a quorum of old and new config, so its not possible to get enough acks
       // for an operation without getting enough acks for preceding ops. But in the future if multiple
       // concurrent reconfigs are allowed, this can happen.
       if (outstandingProposals.containsKey(zxid - 1)) return false;
       
       // getting a quorum from all necessary configurations
        if (!p.hasAllQuorums()) {
           return false;                 
        }
        
        // commit proposals in order
        if (zxid != lastCommitted+1) {    
           LOG.warn("Commiting zxid 0x" + Long.toHexString(zxid)
                    + " from " + followerAddr + " not first!");
            LOG.warn("First is "
                    + (lastCommitted+1));
        }     
        
        // in order to be committed, a proposal must be accepted by a quorum              
        
        outstandingProposals.remove(zxid);
        
        if (p.request != null) {
             toBeApplied.add(p);
        }

        if (p.request == null) {
            LOG.warn("Going to commmit null: " + p);
        } else if (p.request.getHdr().getType() == OpCode.reconfig) {                                   
            LOG.debug("Committing a reconfiguration! " + outstandingProposals.size()); 
                 
            //if this server is voter in new config with the same quorum address, 
            //then it will remain the leader
            //otherwise an up-to-date follower will be designated as leader. This saves
            //leader election time, unless the designated leader fails                             
            Long designatedLeader = getDesignatedLeader(p, zxid);
            //LOG.warn("designated leader is: " + designatedLeader);

            QuorumVerifier newQV = p.qvAcksetPairs.get(p.qvAcksetPairs.size()-1).getQuorumVerifier();
       
            self.processReconfig(newQV, designatedLeader, zk.getZxid(), true);
       
            if (designatedLeader != self.getId()) {
                allowedToCommit = false;
            }
                   
            // we're sending the designated leader, and if the leader is changing the followers are 
            // responsible for closing the connection - this way we are sure that at least a majority of them 
            // receive the commit message.
            commitAndActivate(zxid, designatedLeader);
            informAndActivate(p, designatedLeader);
            //turnOffFollowers();
        } else {
            commit(zxid);
            inform(p);
        }
        zk.commitProcessor.commit(p.request);
        if(pendingSyncs.containsKey(zxid)){
            for(LearnerSyncRequest r: pendingSyncs.remove(zxid)) {
                sendSync(r);
            }               
        } 
        
        return  true;   
    }
    
    /**
     * Keep a count of acks that are received by the leader for a particular
     * proposal
     *
     * @param zxid, the zxid of the proposal sent out
     * @param sid, the id of the server that sent the ack
     * @param followerAddr
     */
    synchronized public void processAck(long sid, long zxid, SocketAddress followerAddr) {        
        if (!allowedToCommit) return; // last op committed was a leader change - from now on 
                                     // the new leader should commit        
        if (LOG.isTraceEnabled()) {
            LOG.trace("Ack zxid: 0x{}", Long.toHexString(zxid));
            for (Proposal p : outstandingProposals.values()) {
                long packetZxid = p.packet.getZxid();
                LOG.trace("outstanding proposal: 0x{}",
                        Long.toHexString(packetZxid));
            }
            LOG.trace("outstanding proposals all");
        }
        
        if ((zxid & 0xffffffffL) == 0) {
            /*
             * We no longer process NEWLEADER ack with this method. However,
             * the learner sends an ack back to the leader after it gets
             * UPTODATE, so we just ignore the message.
             */
            return;
        }
            
            
        if (outstandingProposals.size() == 0) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("outstanding is 0");
            }
            return;
        }
        if (lastCommitted >= zxid) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("proposal has already been committed, pzxid: 0x{} zxid: 0x{}",
                        Long.toHexString(lastCommitted), Long.toHexString(zxid));
            }
            // The proposal has already been committed
            return;
        }
        Proposal p = outstandingProposals.get(zxid);
        if (p == null) {
            LOG.warn("Trying to commit future proposal: zxid 0x{} from {}",
                    Long.toHexString(zxid), followerAddr);
            return;
        }
        
        p.addAck(sid);        
        /*if (LOG.isDebugEnabled()) {
            LOG.debug("Count for zxid: 0x{} is {}",
                    Long.toHexString(zxid), p.ackSet.size());
        }*/
        
        boolean hasCommitted = tryToCommit(p, zxid, followerAddr);

        // If p is a reconfiguration, multiple other operations may be ready to be committed,
        // since operations wait for different sets of acks.
       // Currently we only permit one outstanding reconfiguration at a time
       // such that the reconfiguration and subsequent outstanding ops proposed while the reconfig is
       // pending all wait for a quorum of old and new config, so its not possible to get enough acks
       // for an operation without getting enough acks for preceding ops. But in the future if multiple
       // concurrent reconfigs are allowed, this can happen and then we need to check whether some pending
        // ops may already have enough acks and can be committed, which is what this code does.

        if (hasCommitted && p.request!=null && p.request.getHdr().getType() == OpCode.reconfig){
               long curZxid = zxid;
           while (allowedToCommit && hasCommitted && p!=null){
               curZxid++;
               p = outstandingProposals.get(curZxid);
               if (p !=null) hasCommitted = tryToCommit(p, curZxid, null);             
           }
        }
    }
    
    static class ToBeAppliedRequestProcessor implements RequestProcessor {
        private final RequestProcessor next;

        private final Leader leader;

        /**
         * This request processor simply maintains the toBeApplied list. For
         * this to work next must be a FinalRequestProcessor and
         * FinalRequestProcessor.processRequest MUST process the request
         * synchronously!
         *
         * @param next
         *                a reference to the FinalRequestProcessor
         */
        ToBeAppliedRequestProcessor(RequestProcessor next, Leader leader) {
            if (!(next instanceof FinalRequestProcessor)) {
                throw new RuntimeException(ToBeAppliedRequestProcessor.class
                        .getName()
                        + " must be connected to "
                        + FinalRequestProcessor.class.getName()
                        + " not "
                        + next.getClass().getName());
            }
            this.leader = leader;
            this.next = next;
        }

        /*
         * (non-Javadoc)
         *
         * @see org.apache.zookeeper.server.RequestProcessor#processRequest(org.apache.zookeeper.server.Request)
         */
        public void processRequest(Request request) throws RequestProcessorException {
            next.processRequest(request);

            // The only requests that should be on toBeApplied are write
            // requests, for which we will have a hdr. We can't simply use
            // request.zxid here because that is set on read requests to equal
            // the zxid of the last write op.
            if (request.getHdr() != null) {
                long zxid = request.getHdr().getZxid();
                Iterator<Proposal> iter = leader.toBeApplied.iterator();
                if (iter.hasNext()) {
                    Proposal p = iter.next();
                    if (p.request != null && p.request.zxid == zxid) {
                        iter.remove();
                        return;
                    }
                }
                LOG.error("Committed request not found on toBeApplied: "
                          + request);
            }
        }

        /*
         * (non-Javadoc)
         *
         * @see org.apache.zookeeper.server.RequestProcessor#shutdown()
         */
        public void shutdown() {
            LOG.info("Shutting down");
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
            for (LearnerHandler f : forwardingFollowers) {
                f.queuePacket(qp);
            }
        }
    }

    /**
     * send a packet to all observers
     */
    void sendObserverPacket(QuorumPacket qp) {
        for (LearnerHandler f : getObservingLearners()) {
            f.queuePacket(qp);
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

    //commit and send some info
    public void commitAndActivate(long zxid, long designatedLeader) {
        synchronized(this){
            lastCommitted = zxid;
        }
        
        byte data[] = new byte[8];
        ByteBuffer buffer = ByteBuffer.wrap(data);                            
       buffer.putLong(designatedLeader);
       
        QuorumPacket qp = new QuorumPacket(Leader.COMMITANDACTIVATE, zxid, data, null);
        sendPacket(qp);
    }

    /**
     * Create an inform packet and send it to all observers.
     * @param zxid
     * @param proposal
     */
    public void inform(Proposal proposal) {
        QuorumPacket qp = new QuorumPacket(Leader.INFORM, proposal.request.zxid,
                                            proposal.packet.getData(), null);
        sendObserverPacket(qp);
    }

    
    /**
     * Create an inform&activate packet and send it to all observers.
     * @param zxid
     * @param proposal
     */
    public void informAndActivate(Proposal proposal, long designatedLeader) {
       byte[] proposalData = proposal.packet.getData();
        byte[] data = new byte[proposalData.length + 8];
        ByteBuffer buffer = ByteBuffer.wrap(data);                            
       buffer.putLong(designatedLeader);
       buffer.put(proposalData);
       
        QuorumPacket qp = new QuorumPacket(Leader.INFORMANDACTIVATE, proposal.request.zxid, data, null);
        sendObserverPacket(qp);
    }

    long lastProposed;


    /**
     * Returns the current epoch of the leader.
     *
     * @return
     */
    public long getEpoch(){
        return ZxidUtils.getEpochFromZxid(lastProposed);
    }

    @SuppressWarnings("serial")
    public static class XidRolloverException extends Exception {
        public XidRolloverException(String message) {
            super(message);
        }
    }

    /**
     * create a proposal and send it out to all the members
     *
     * @param request
     * @return the proposal that is queued to send to all the members
     */
    public Proposal propose(Request request) throws XidRolloverException {
        /**
         * Address the rollover issue. All lower 32bits set indicate a new leader
         * election. Force a re-election instead. See ZOOKEEPER-1277
         */
        if ((request.zxid & 0xffffffffL) == 0xffffffffL) {
            String msg =
                    "zxid lower 32 bits have rolled over, forcing re-election, and therefore new epoch start";
            shutdown(msg);
            throw new XidRolloverException(msg);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        try {
            request.getHdr().serialize(boa, "hdr");
            if (request.getTxn() != null) {
                request.getTxn().serialize(boa, "txn");
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
        
        synchronized(this) {
           p.addQuorumVerifier(self.getQuorumVerifier());
                   
           if (request.getHdr().getType() == OpCode.reconfig){
               self.setLastSeenQuorumVerifier(request.qv, true);                       
           }
           
           if (self.getQuorumVerifier().getVersion()<self.getLastSeenQuorumVerifier().getVersion()) {
               p.addQuorumVerifier(self.getLastSeenQuorumVerifier());
           }
                   
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

    synchronized public void processSync(LearnerSyncRequest r){
        if(outstandingProposals.isEmpty()){
            sendSync(r);
        } else {
            List<LearnerSyncRequest> l = pendingSyncs.get(lastProposed);
            if (l == null) {
                l = new ArrayList<LearnerSyncRequest>();
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

    public void sendSync(LearnerSyncRequest r){
        QuorumPacket qp = new QuorumPacket(Leader.SYNC, 0, null, null);
        r.fh.queuePacket(qp);
    }

    /**
     * lets the leader know that a follower is capable of following and is done
     * syncing
     *
     * @param handler handler of the follower
     * @return last proposed zxid
     * @throws InterruptedException 
     */
    synchronized public long startForwarding(LearnerHandler handler,
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
        if (handler.getLearnerType() == LearnerType.PARTICIPANT) {
            addForwardingFollower(handler);
        } else {
            addObserverLearnerHandler(handler);
        }

        return lastProposed;
    }
    private final HashSet<Long> connectingFollowers = new HashSet<Long>();
    public long getEpochToPropose(long sid, long lastAcceptedEpoch) throws InterruptedException, IOException {
        synchronized(connectingFollowers) {
            if (!waitingForNewEpoch) {
                return epoch;
            }
            if (lastAcceptedEpoch >= epoch) {
                epoch = lastAcceptedEpoch+1;
            }
            connectingFollowers.add(sid);
            QuorumVerifier verifier = self.getQuorumVerifier();
            if (connectingFollowers.contains(self.getId()) &&
                                            verifier.containsQuorum(connectingFollowers)) {
                waitingForNewEpoch = false;
                self.setAcceptedEpoch(epoch);
                connectingFollowers.notifyAll();
            } else {
                long start = System.currentTimeMillis();
                long cur = start;
                long end = start + self.getInitLimit()*self.getTickTime();
                while(waitingForNewEpoch && cur < end) {
                    connectingFollowers.wait(end - cur);
                    cur = System.currentTimeMillis();
                }
                if (waitingForNewEpoch) {
                    throw new InterruptedException("Timeout while waiting for epoch from quorum");
                }
            }
            return epoch;
        }
    }

    private final HashSet<Long> electingFollowers = new HashSet<Long>();
    private boolean electionFinished = false;
    public void waitForEpochAck(long id, StateSummary ss) throws IOException, InterruptedException {
        synchronized(electingFollowers) {
            if (electionFinished) {
                return;
            }
            if (ss.getCurrentEpoch() != -1) {
                if (ss.isMoreRecentThan(leaderStateSummary)) {
                    throw new IOException("Follower is ahead of the leader, leader summary: " 
                                                    + leaderStateSummary.getCurrentEpoch()
                                                    + " (current epoch), "
                                                    + leaderStateSummary.getLastZxid()
                                                    + " (last zxid)");
                }
                electingFollowers.add(id);
            }
            QuorumVerifier verifier = self.getQuorumVerifier();
            if (electingFollowers.contains(self.getId()) && verifier.containsQuorum(electingFollowers)) {
                electionFinished = true;
                electingFollowers.notifyAll();
            } else {
                long start = System.currentTimeMillis();
                long cur = start;
                long end = start + self.getInitLimit()*self.getTickTime();
                while(!electionFinished && cur < end) {
                    electingFollowers.wait(end - cur);
                    cur = System.currentTimeMillis();
                }
                if (!electionFinished) {
                    throw new InterruptedException("Timeout while waiting for epoch to be acked by quorum");
                }
            }
        }
    }
    
    /**
     * Return a list of sid in set as string  
     */
    private String getSidSetString(Set<Long> sidSet) {
        StringBuilder sids = new StringBuilder();
        Iterator<Long> iter = sidSet.iterator();
        while (iter.hasNext()) {
            sids.append(iter.next());
            if (!iter.hasNext()) {
              break;
            }
            sids.append(",");
        }
        return sids.toString();
    }

    /**
     * Start up Leader ZooKeeper server and initialize zxid to the new epoch
     */
    private synchronized void startZkServer() {
        // Update lastCommitted and Db's zxid to a value representing the new epoch
        lastCommitted = zk.getZxid();
        LOG.info("Have quorum of supporters, sids: [ "
                + newLeaderProposal.ackSetsToString()
                + " ]; starting up and setting last processed zxid: 0x{}",
                Long.toHexString(zk.getZxid()));
        
        /*
         * ZOOKEEPER-1324. the leader sends the new config it must complete
         *  to others inside a NEWLEADER message (see LearnerHandler where
         *  the NEWLEADER message is constructed), and once it has enough
         *  acks we must execute the following code so that it applies the
         *  config to itself.
         */
        QuorumVerifier newQV = self.getLastSeenQuorumVerifier();
        
        Long designatedLeader = getDesignatedLeader(newLeaderProposal, zk.getZxid());                                         
        
        self.processReconfig(newQV, designatedLeader, zk.getZxid(), true);
        if (designatedLeader != self.getId()) {
            allowedToCommit = false;
        }
        
        zk.startup();
        zk.getZKDatabase().setlastProcessedZxid(zk.getZxid());
    }

    /**
     * Process NEWLEADER ack of a given sid and wait until the leader receives
     * sufficient acks.
     *
     * @param sid
     * @param learnerType
     * @throws InterruptedException
     */
    public void waitForNewLeaderAck(long sid, long zxid, LearnerType learnerType)
            throws InterruptedException {

        synchronized (newLeaderProposal.qvAcksetPairs) {

            if (quorumFormed) {
                return;
            }

            long currentZxid = newLeaderProposal.packet.getZxid();
            if (zxid != currentZxid) {
                LOG.error("NEWLEADER ACK from sid: " + sid
                        + " is from a different epoch - current 0x"
                        + Long.toHexString(currentZxid) + " receieved 0x"
                        + Long.toHexString(zxid));
                return;
            }

            /*
             * Note that addAck already checks that the learner
             * is a PARTICIPANT.
             */
            newLeaderProposal.addAck(sid);

            if (newLeaderProposal.hasAllQuorums()) {
                quorumFormed = true;
                newLeaderProposal.qvAcksetPairs.notifyAll();
            } else {
                long start = System.currentTimeMillis();
                long cur = start;
                long end = start + self.getInitLimit() * self.getTickTime();
                while (!quorumFormed && cur < end) {
                    newLeaderProposal.qvAcksetPairs.wait(end - cur);
                    cur = System.currentTimeMillis();
                }
                if (!quorumFormed) {
                    throw new InterruptedException(
                            "Timeout while waiting for NEWLEADER to be acked by quorum");
                }
            }
        }
    }

    /**
     * Get string representation of a given packet type
     * @param packetType
     * @return string representing the packet type
     */
    public static String getPacketType(int packetType) {
        switch (packetType) {
        case DIFF:
            return "DIFF";
        case TRUNC:
            return "TRUNC";
        case SNAP:
            return "SNAP";
        case OBSERVERINFO:
            return "OBSERVERINFO";
        case NEWLEADER:
            return "NEWLEADER";
        case FOLLOWERINFO:
            return "FOLLOWERINFO";
        case UPTODATE:
            return "UPTODATE";
        case LEADERINFO:
            return "LEADERINFO";
        case ACKEPOCH:
            return "ACKEPOCH";
        case REQUEST:
            return "REQUEST";
        case PROPOSAL:
            return "PROPOSAL";
        case ACK:
            return "ACK";
        case COMMIT:
            return "COMMIT";
        case COMMITANDACTIVATE:
            return "COMMITANDACTIVATE";           
        case PING:
            return "PING";
        case REVALIDATE:
            return "REVALIDATE";
        case SYNC:
            return "SYNC";
        case INFORM:
            return "INFORM";
        case INFORMANDACTIVATE:
            return "INFORMANDACTIVATE";
        default:
            return "UNKNOWN";
        }
    }
}
