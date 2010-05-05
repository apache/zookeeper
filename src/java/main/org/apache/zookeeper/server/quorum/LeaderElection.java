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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;

public class LeaderElection implements Election  {
    private static final Logger LOG = Logger.getLogger(LeaderElection.class);
    protected static Random epochGen = new Random();

    protected QuorumPeer self;

    public LeaderElection(QuorumPeer self) {
        this.self = self;
    }

    public static class ElectionResult {
        public Vote vote;

        public int count;

        public Vote winner;

        public int winningCount;
    }

    protected ElectionResult countVotes(HashMap<InetSocketAddress, Vote> votes, HashSet<Long> heardFrom) {
        ElectionResult result = new ElectionResult();
        // Initialize with null vote
        result.vote = new Vote(Long.MIN_VALUE, Long.MIN_VALUE);
        result.winner = new Vote(Long.MIN_VALUE, Long.MIN_VALUE);
        Collection<Vote> votesCast = votes.values();
        // First make the views consistent. Sometimes peers will have
        // different zxids for a server depending on timing.
        for (Iterator<Vote> i = votesCast.iterator(); i.hasNext();) {
            Vote v = i.next();
            if (!heardFrom.contains(v.id)) {
                // Discard votes for machines that we didn't hear from
                i.remove();
                continue;
            }
            for (Vote w : votesCast) {
                if (v.id == w.id) {
                    if (v.zxid < w.zxid) {
                        v.zxid = w.zxid;
                    }
                }
            }
        }

        HashMap<Vote, Integer> countTable = new HashMap<Vote, Integer>();
        // Now do the tally
        for (Vote v : votesCast) {
            Integer count = countTable.get(v);
            if (count == null) {
                count = Integer.valueOf(0);
            }
            countTable.put(v, count + 1);
            if (v.id == result.vote.id) {
                result.count++;
            } else if (v.zxid > result.vote.zxid
                    || (v.zxid == result.vote.zxid && v.id > result.vote.id)) {
                result.vote = v;
                result.count = 1;
            }
        }
        result.winningCount = 0;
        LOG.info("Election tally: ");
        for (Entry<Vote, Integer> entry : countTable.entrySet()) {
            if (entry.getValue() > result.winningCount) {
                result.winningCount = entry.getValue();
                result.winner = entry.getKey();
            }
            LOG.info(entry.getKey().id + "\t-> " + entry.getValue());
        }
        return result;
    }

    /**
     * There is nothing to shutdown in this implementation of
     * leader election, so we simply have an empty method.
     */
    public void shutdown(){}
    
    /**
     * Invoked in QuorumPeer to find or elect a new leader.
     * 
     * @throws InterruptedException
     */
    public Vote lookForLeader() throws InterruptedException {
        try {
            self.jmxLeaderElectionBean = new LeaderElectionBean();
            MBeanRegistry.getInstance().register(
                    self.jmxLeaderElectionBean, self.jmxLocalPeerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            self.jmxLeaderElectionBean = null;
        }

        try {
            self.setCurrentVote(new Vote(self.getId(),
                    self.getLastLoggedZxid()));
            // We are going to look for a leader by casting a vote for ourself
            byte requestBytes[] = new byte[4];
            ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);
            byte responseBytes[] = new byte[28];
            ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);
            /* The current vote for the leader. Initially me! */
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                s.setSoTimeout(200);
            } catch (SocketException e1) {
                LOG.error("Socket exception when creating socket for leader election", e1);
                System.exit(4);
            }
            DatagramPacket requestPacket = new DatagramPacket(requestBytes,
                    requestBytes.length);
            DatagramPacket responsePacket = new DatagramPacket(responseBytes,
                    responseBytes.length);
            HashMap<InetSocketAddress, Vote> votes =
                new HashMap<InetSocketAddress, Vote>(self.getVotingView().size());
            int xid = epochGen.nextInt();
            while (self.isRunning()) {
                votes.clear();
                requestBuffer.clear();
                requestBuffer.putInt(xid);
                requestPacket.setLength(4);
                HashSet<Long> heardFrom = new HashSet<Long>();
                for (QuorumServer server : self.getVotingView().values()) {
                    LOG.info("Server address: " + server.addr);
                    try {
                        requestPacket.setSocketAddress(server.addr);
                    } catch (IllegalArgumentException e) {
                        // Sun doesn't include the address that causes this
                        // exception to be thrown, so we wrap the exception
                        // in order to capture this critical detail.
                        throw new IllegalArgumentException(
                                "Unable to set socket address on packet, msg:"
                                + e.getMessage() + " with addr:" + server.addr,
                                e);
                    }

                    try {
                        s.send(requestPacket);
                        responsePacket.setLength(responseBytes.length);
                        s.receive(responsePacket);
                        if (responsePacket.getLength() != responseBytes.length) {
                            LOG.error("Got a short response: "
                                    + responsePacket.getLength());
                            continue;
                        }
                        responseBuffer.clear();
                        int recvedXid = responseBuffer.getInt();
                        if (recvedXid != xid) {
                            LOG.error("Got bad xid: expected " + xid
                                    + " got " + recvedXid);
                            continue;
                        }
                        long peerId = responseBuffer.getLong();
                        heardFrom.add(peerId);
                        //if(server.id != peerId){
                            Vote vote = new Vote(responseBuffer.getLong(),
                                responseBuffer.getLong());
                            InetSocketAddress addr =
                                (InetSocketAddress) responsePacket
                                .getSocketAddress();
                            votes.put(addr, vote);
                        //}
                    } catch (IOException e) {
                        LOG.warn("Ignoring exception while looking for leader",
                                e);
                        // Errors are okay, since hosts may be
                        // down
                    }
                }

                ElectionResult result = countVotes(votes, heardFrom);
                // ZOOKEEPER-569:
                // If no votes are received for live peers, reset to voting 
                // for ourselves as otherwise we may hang on to a vote 
                // for a dead peer                 
                if (votes.size() == 0) {                    
                    self.setCurrentVote(new Vote(self.getId(),
                            self.getLastLoggedZxid()));
                } else {
                    if (result.winner.id >= 0) {
                        self.setCurrentVote(result.vote);
                        // To do: this doesn't use a quorum verifier
                        if (result.winningCount > (self.getVotingView().size() / 2)) {
                            self.setCurrentVote(result.winner);
                            s.close();
                            Vote current = self.getCurrentVote();
                            LOG.info("Found leader: my type is: " + self.getLearnerType());
                            /*
                             * We want to make sure we implement the state machine
                             * correctly. If we are a PARTICIPANT, once a leader
                             * is elected we can move either to LEADING or 
                             * FOLLOWING. However if we are an OBSERVER, it is an
                             * error to be elected as a Leader.
                             */
                            if (self.getLearnerType() == LearnerType.OBSERVER) {
                                if (current.id == self.getId()) {
                                    // This should never happen!
                                    LOG.error("OBSERVER elected as leader!");
                                    Thread.sleep(100);
                                }
                                else {
                                    self.setPeerState(ServerState.OBSERVING);
                                    Thread.sleep(100);
                                    return current;
                                }
                            } else {
                                self.setPeerState((current.id == self.getId())
                                        ? ServerState.LEADING: ServerState.FOLLOWING);
                                if (self.getPeerState() == ServerState.FOLLOWING) {
                                    Thread.sleep(100);
                                }                            
                                return current;
                            }
                        }
                    }
                }
                Thread.sleep(1000);
            }
            return null;
        } finally {
            try {
                if(self.jmxLeaderElectionBean != null){
                    MBeanRegistry.getInstance().unregister(
                            self.jmxLeaderElectionBean);
                }
            } catch (Exception e) {
                LOG.warn("Failed to unregister with JMX", e);
            }
            self.jmxLeaderElectionBean = null;
        }
    }
}
