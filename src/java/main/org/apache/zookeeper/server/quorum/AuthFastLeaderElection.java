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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import java.util.concurrent.TimeUnit;
import java.util.Random;

import org.apache.log4j.Logger;

import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.quorum.Election;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;


public class AuthFastLeaderElection implements Election {
    private static final Logger LOG = Logger.getLogger(AuthFastLeaderElection.class);

    /* Sequence numbers for messages */
    static int sequencer = 0;
    static int maxTag = 0;

    /*
     * Determine how much time a process has to wait once it believes that it
     * has reached the end of leader election.
     */
    static int finalizeWait = 100;

    /*
     * Challenge counter to avoid replay attacks
     */

    static int challengeCounter = 0;

    /*
     * Flag to determine whether to authenticate or not
     */

    private boolean authEnabled = false;

    static public class Notification {
        /*
         * Proposed leader
         */
        long leader;

        /*
         * zxid of the proposed leader
         */
        long zxid;

        /*
         * Epoch
         */
        long epoch;

        /*
         * current state of sender
         */
        QuorumPeer.ServerState state;

        /*
         * Address of the sender
         */
        InetSocketAddress addr;
    }

    /*
     * Messages to send, both Notifications and Acks
     */
    static public class ToSend {
        static enum mType {
            crequest, challenge, notification, ack
        }

        ToSend(mType type, long tag, long leader, long zxid, long epoch,
                ServerState state, InetSocketAddress addr) {

            switch (type) {
            case crequest:
                this.type = 0;
                this.tag = tag;
                this.leader = leader;
                this.zxid = zxid;
                this.epoch = epoch;
                this.state = state;
                this.addr = addr;

                break;
            case challenge:
                this.type = 1;
                this.tag = tag;
                this.leader = leader;
                this.zxid = zxid;
                this.epoch = epoch;
                this.state = state;
                this.addr = addr;

                break;
            case notification:
                this.type = 2;
                this.leader = leader;
                this.zxid = zxid;
                this.epoch = epoch;
                this.state = QuorumPeer.ServerState.LOOKING;
                this.tag = tag;
                this.addr = addr;

                break;
            case ack:
                this.type = 3;
                this.tag = tag;
                this.leader = leader;
                this.zxid = zxid;
                this.epoch = epoch;
                this.state = state;
                this.addr = addr;

                break;
            default:
                break;
            }
        }

        /*
         * Message type: 0 notification, 1 acknowledgement
         */
        int type;

        /*
         * Proposed leader in the case of notification
         */
        long leader;

        /*
         * id contains the tag for acks, and zxid for notifications
         */
        long zxid;

        /*
         * Epoch
         */
        long epoch;

        /*
         * Current state;
         */
        QuorumPeer.ServerState state;

        /*
         * Message tag
         */
        long tag;

        InetSocketAddress addr;
    }

    LinkedBlockingQueue<ToSend> sendqueue;

    LinkedBlockingQueue<Notification> recvqueue;

    private class Messenger {

        final DatagramSocket mySocket;
        long lastProposedLeader;
        long lastProposedZxid;
        long lastEpoch;
        final LinkedBlockingQueue<Long> acksqueue;
        final HashMap<Long, Long> challengeMap;
        final HashMap<Long, Semaphore> challengeMutex;
        final HashMap<Long, Semaphore> ackMutex;
        final HashMap<InetSocketAddress, HashMap<Long, Long>> addrChallengeMap;

        class WorkerReceiver implements Runnable {

            DatagramSocket mySocket;
            Messenger myMsg;

            WorkerReceiver(DatagramSocket s, Messenger msg) {
                mySocket = s;
                myMsg = msg;
            }

            boolean saveChallenge(long tag, long challenge) {

                //Long l = challengeMutex.get(tag);
                Semaphore s = challengeMutex.get(tag);
                if (s != null) {
                        synchronized (challengeMap) {
                            challengeMap.put(tag, challenge);
                            challengeMutex.remove(tag);
                        }

                
                        s.release();
                } else {
                    LOG.error("No challenge mutex object");
                }
                

                return true;
            }

            public void run() {
                byte responseBytes[] = new byte[48];
                ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);
                DatagramPacket responsePacket = new DatagramPacket(
                        responseBytes, responseBytes.length);
                while (true) {
                    // Sleeps on receive
                    try {
                        responseBuffer.clear();
                        mySocket.receive(responsePacket);
                    } catch (IOException e) {
                        LOG.warn("Ignoring exception receiving", e);
                    }
                    // Receive new message
                    if (responsePacket.getLength() != responseBytes.length) {
                        LOG.warn("Got a short response: "
                                + responsePacket.getLength() + " "
                                + responsePacket.toString());
                        continue;
                    }
                    responseBuffer.clear();
                    int type = responseBuffer.getInt();
                    if ((type > 3) || (type < 0)) {
                        LOG.warn("Got bad Msg type: " + type);
                        continue;
                    }
                    long tag = responseBuffer.getLong();

                    QuorumPeer.ServerState ackstate = QuorumPeer.ServerState.LOOKING;
                    switch (responseBuffer.getInt()) {
                    case 0:
                        ackstate = QuorumPeer.ServerState.LOOKING;
                        break;
                    case 1:
                        ackstate = QuorumPeer.ServerState.LEADING;
                        break;
                    case 2:
                        ackstate = QuorumPeer.ServerState.FOLLOWING;
                        break;
                    }

                    Vote current = self.getCurrentVote();

                    switch (type) {
                    case 0:
                        // Receive challenge request
                        ToSend c = new ToSend(ToSend.mType.challenge, tag,
                                current.id, current.zxid,
                                logicalclock, self.getPeerState(),
                                (InetSocketAddress) responsePacket
                                        .getSocketAddress());
                        sendqueue.offer(c);
                        break;
                    case 1:
                        // Receive challenge and store somewhere else
                        long challenge = responseBuffer.getLong();
                        saveChallenge(tag, challenge);

                        break;
                    case 2:
                        Notification n = new Notification();
                        n.leader = responseBuffer.getLong();
                        n.zxid = responseBuffer.getLong();
                        n.epoch = responseBuffer.getLong();
                        n.state = ackstate;
                        n.addr = (InetSocketAddress) responsePacket
                                .getSocketAddress();

                        if ((myMsg.lastEpoch <= n.epoch)
                                && ((n.zxid > myMsg.lastProposedZxid) 
                                || ((n.zxid == myMsg.lastProposedZxid) 
                                && (n.leader > myMsg.lastProposedLeader)))) {
                            myMsg.lastProposedZxid = n.zxid;
                            myMsg.lastProposedLeader = n.leader;
                            myMsg.lastEpoch = n.epoch;
                        }

                        long recChallenge;
                        InetSocketAddress addr = (InetSocketAddress) responsePacket
                                .getSocketAddress();
                        if (authEnabled) {
                            if (addrChallengeMap.get(addr).get(tag) != null) {
                                recChallenge = responseBuffer.getLong();

                                if (addrChallengeMap.get(addr).get(tag) == recChallenge) {
                                    recvqueue.offer(n);

                                    ToSend a = new ToSend(ToSend.mType.ack,
                                            tag, current.id,
                                            current.zxid,
                                            logicalclock, self.getPeerState(),
                                            addr);

                                    sendqueue.offer(a);
                                } else {
                                    LOG.warn("Incorrect challenge: "
                                            + recChallenge + ", "
                                            + addrChallengeMap.toString());
                                }
                            } else {
                                LOG.warn("No challenge for host: " + addr
                                        + " " + tag);
                            }
                        } else {
                            recvqueue.offer(n);

                            ToSend a = new ToSend(ToSend.mType.ack, tag,
                                    current.id, current.zxid,
                                    logicalclock, self.getPeerState(),
                                    (InetSocketAddress) responsePacket
                                            .getSocketAddress());

                            sendqueue.offer(a);
                        }
                        break;

                    // Upon reception of an ack message, remove it from the
                    // queue
                    case 3:
                        Semaphore s = ackMutex.get(tag);
                        
                        if(s != null)
                            s.release();
                        else LOG.error("Empty ack semaphore");
                        
                        acksqueue.offer(tag);

                        if (authEnabled) {
                            addrChallengeMap.get(responsePacket
                                            .getSocketAddress()).remove(tag);
                        }

                        if (ackstate != QuorumPeer.ServerState.LOOKING) {
                            Notification outofsync = new Notification();
                            outofsync.leader = responseBuffer.getLong();
                            outofsync.zxid = responseBuffer.getLong();
                            outofsync.epoch = responseBuffer.getLong();
                            outofsync.state = ackstate;
                            outofsync.addr = (InetSocketAddress) responsePacket
                                    .getSocketAddress();

                            recvqueue.offer(outofsync);
                        }

                        break;
                    // Default case
                    default:
                        LOG.warn("Received message of incorrect type " + type);
                        break;
                    }
                }
            }
        }

        class WorkerSender implements Runnable {

            Random rand;
            int maxAttempts;
            int ackWait = finalizeWait;

            /*
             * Receives a socket and max number of attempts as input
             */

            WorkerSender(int attempts) {
                maxAttempts = attempts;
                rand = new Random(java.lang.Thread.currentThread().getId()
                        + System.currentTimeMillis());
            }

            long genChallenge() {
                byte buf[] = new byte[8];

                buf[0] = (byte) ((challengeCounter & 0xff000000) >>> 24);
                buf[1] = (byte) ((challengeCounter & 0x00ff0000) >>> 16);
                buf[2] = (byte) ((challengeCounter & 0x0000ff00) >>> 8);
                buf[3] = (byte) ((challengeCounter & 0x000000ff));

                challengeCounter++;
                int secret = rand.nextInt(java.lang.Integer.MAX_VALUE);

                buf[4] = (byte) ((secret & 0xff000000) >>> 24);
                buf[5] = (byte) ((secret & 0x00ff0000) >>> 16);
                buf[6] = (byte) ((secret & 0x0000ff00) >>> 8);
                buf[7] = (byte) ((secret & 0x000000ff));

                return (((long)(buf[0] & 0xFF)) << 56)  
                        + (((long)(buf[1] & 0xFF)) << 48)
                        + (((long)(buf[2] & 0xFF)) << 40) 
                        + (((long)(buf[3] & 0xFF)) << 32)
                        + (((long)(buf[4] & 0xFF)) << 24) 
                        + (((long)(buf[5] & 0xFF)) << 16)
                        + (((long)(buf[6] & 0xFF)) << 8) 
                        + ((long)(buf[7] & 0xFF));
            }

            public void run() {
                while (true) {
                    try {
                        ToSend m = sendqueue.take();
                        process(m);
                    } catch (InterruptedException e) {
                        break;
                    }

                }
            }

            private void process(ToSend m) {
                int attempts = 0;
                byte zeroes[];
                byte requestBytes[] = new byte[48];
                DatagramPacket requestPacket = new DatagramPacket(requestBytes,
                        requestBytes.length);
                ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);

                switch (m.type) {
                case 0:
                    /*
                     * Building challenge request packet to send
                     */
                    requestBuffer.clear();
                    requestBuffer.putInt(ToSend.mType.crequest.ordinal());
                    requestBuffer.putLong(m.tag);
                    requestBuffer.putInt(m.state.ordinal());
                    zeroes = new byte[32];
                    requestBuffer.put(zeroes);

                    requestPacket.setLength(48);
                    try {
                        requestPacket.setSocketAddress(m.addr);
                    } catch (IllegalArgumentException e) {
                        // Sun doesn't include the address that causes this
                        // exception to be thrown, so we wrap the exception
                        // in order to capture this critical detail.
                        throw new IllegalArgumentException(
                                "Unable to set socket address on packet, msg:"
                                + e.getMessage() + " with addr:" + m.addr,
                                e);
                    }

                    try {
                        if (challengeMap.get(m.tag) == null) {
                            mySocket.send(requestPacket);
                        }
                    } catch (IOException e) {
                        LOG.warn("Exception while sending challenge: ", e);
                    }

                    break;
                case 1:
                    /*
                     * Building challenge packet to send
                     */

                    long newChallenge;
                    if (addrChallengeMap.get(m.addr).containsKey(m.tag)) {
                        newChallenge = addrChallengeMap.get(m.addr).get(m.tag);
                    } else {
                        newChallenge = genChallenge();
                    }

                    addrChallengeMap.get(m.addr).put(m.tag, newChallenge);

                    requestBuffer.clear();
                    requestBuffer.putInt(ToSend.mType.challenge.ordinal());
                    requestBuffer.putLong(m.tag);
                    requestBuffer.putInt(m.state.ordinal());
                    requestBuffer.putLong(newChallenge);
                    zeroes = new byte[24];
                    requestBuffer.put(zeroes);

                    requestPacket.setLength(48);
                    try {
                        requestPacket.setSocketAddress(m.addr);
                    } catch (IllegalArgumentException e) {
                        // Sun doesn't include the address that causes this
                        // exception to be thrown, so we wrap the exception
                        // in order to capture this critical detail.
                        throw new IllegalArgumentException(
                                "Unable to set socket address on packet, msg:"
                                + e.getMessage() + " with addr:" + m.addr,
                                e);
                    }


                    try {
                        mySocket.send(requestPacket);
                    } catch (IOException e) {
                        LOG.warn("Exception while sending challenge: ", e);
                    }

                    break;
                case 2:

                    /*
                     * Building notification packet to send
                     */

                    requestBuffer.clear();
                    requestBuffer.putInt(m.type);
                    requestBuffer.putLong(m.tag);
                    requestBuffer.putInt(m.state.ordinal());
                    requestBuffer.putLong(m.leader);
                    requestBuffer.putLong(m.zxid);
                    requestBuffer.putLong(m.epoch);
                    zeroes = new byte[8];
                    requestBuffer.put(zeroes);

                    requestPacket.setLength(48);
                    try {
                        requestPacket.setSocketAddress(m.addr);
                    } catch (IllegalArgumentException e) {
                        // Sun doesn't include the address that causes this
                        // exception to be thrown, so we wrap the exception
                        // in order to capture this critical detail.
                        throw new IllegalArgumentException(
                                "Unable to set socket address on packet, msg:"
                                + e.getMessage() + " with addr:" + m.addr,
                                e);
                    }


                    boolean myChallenge = false;
                    boolean myAck = false;

                    while (attempts < maxAttempts) {
                        try {
                            /*
                             * Try to obtain a challenge only if does not have
                             * one yet
                             */

                            if (!myChallenge && authEnabled) {
                                ToSend crequest = new ToSend(
                                        ToSend.mType.crequest, m.tag, m.leader,
                                        m.zxid, m.epoch,
                                        QuorumPeer.ServerState.LOOKING, m.addr);
                                sendqueue.offer(crequest);

                                try {
                                    double timeout = ackWait
                                            * java.lang.Math.pow(2, attempts);

                                    //Long l = new Long(m.tag);
                                    Semaphore s = new Semaphore(0);
                                    synchronized (s) {
                                        challengeMutex.put(m.tag, s);
                                        s.tryAcquire((long) timeout, TimeUnit.MILLISECONDS);
                                        myChallenge = challengeMap
                                                .containsKey(m.tag);
                                    }
                                } catch (InterruptedException e) {
                                    LOG.warn("Challenge request exception: ", e);
                                } 
                            }

                            /*
                             * If don't have challenge yet, skip sending
                             * notification
                             */

                            if (authEnabled && !myChallenge) {
                                attempts++;
                                continue;
                            }

                            if (authEnabled) {
                                requestBuffer.position(40);
                                requestBuffer.putLong(challengeMap.get(m.tag));
                            }
                            mySocket.send(requestPacket);
                            try {
                                Semaphore s = new Semaphore(0);
                                double timeout = ackWait
                                        * java.lang.Math.pow(10, attempts);
                                ackMutex.put(m.tag, s);
                                s.tryAcquire((int) timeout, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException e) {
                                LOG.warn("Ack exception: ", e);
                            }
                            synchronized (acksqueue) {
                                for (int i = 0; i < acksqueue.size(); ++i) {
                                    Long newack = acksqueue.poll();

                                    /*
                                     * Under highly concurrent load, a thread
                                     * may get into this loop but by the time it
                                     * tries to read from the queue, the queue
                                     * is empty. There are two alternatives:
                                     * synchronize this block, or test if newack
                                     * is null.
                                     *
                                     */

                                    if (newack == m.tag) {
                                        myAck = true;
                                    } else
                                        acksqueue.offer(newack);
                                }
                            }
                        } catch (IOException e) {
                            LOG.warn("Sending exception: ", e);
                            /*
                             * Do nothing, just try again
                             */
                        }
                        if (myAck) {
                            /*
                             * Received ack successfully, so return
                             */
                            if (challengeMap.get(m.tag) != null)
                                challengeMap.remove(m.tag);
                            return;
                        } else
                            attempts++;
                    }
                    /*
                     * Return message to queue for another attempt later if
                     * epoch hasn't changed.
                     */
                    if (m.epoch == logicalclock) {
                        challengeMap.remove(m.tag);
                        sendqueue.offer(m);
                    }
                    break;
                case 3:

                    requestBuffer.clear();
                    requestBuffer.putInt(m.type);
                    requestBuffer.putLong(m.tag);
                    requestBuffer.putInt(m.state.ordinal());
                    requestBuffer.putLong(m.leader);
                    requestBuffer.putLong(m.zxid);
                    requestBuffer.putLong(m.epoch);

                    requestPacket.setLength(48);
                    try {
                        requestPacket.setSocketAddress(m.addr);
                    } catch (IllegalArgumentException e) {
                        // Sun doesn't include the address that causes this
                        // exception to be thrown, so we wrap the exception
                        // in order to capture this critical detail.
                        throw new IllegalArgumentException(
                                "Unable to set socket address on packet, msg:"
                                + e.getMessage() + " with addr:" + m.addr,
                                e);
                    }


                    try {
                        mySocket.send(requestPacket);
                    } catch (IOException e) {
                        LOG.warn("Exception while sending ack: ", e);
                    }
                    break;
                }
            }
        }

        public boolean queueEmpty() {
            return (sendqueue.isEmpty() || acksqueue.isEmpty() || recvqueue
                    .isEmpty());
        }

        Messenger(int threads, DatagramSocket s) {
            mySocket = s;
            acksqueue = new LinkedBlockingQueue<Long>();
            challengeMap = new HashMap<Long, Long>();
            challengeMutex = new HashMap<Long, Semaphore>();
            ackMutex = new HashMap<Long, Semaphore>();
            addrChallengeMap = new HashMap<InetSocketAddress, HashMap<Long, Long>>();
            lastProposedLeader = 0;
            lastProposedZxid = 0;
            lastEpoch = 0;

            for (int i = 0; i < threads; ++i) {
                Thread t = new Thread(new WorkerSender(3),
                        "WorkerSender Thread: " + (i + 1));
                t.setDaemon(true);
                t.start();
            }

            for (QuorumServer server : self.getVotingView().values()) {
                InetSocketAddress saddr = new InetSocketAddress(server.addr
                        .getAddress(), port);
                addrChallengeMap.put(saddr, new HashMap<Long, Long>());
            }

            Thread t = new Thread(new WorkerReceiver(s, this),
                    "WorkerReceiver Thread");
            t.start();
        }

    }

    QuorumPeer self;
    int port;
    volatile long logicalclock; /* Election instance */
    DatagramSocket mySocket;
    long proposedLeader;
    long proposedZxid;

    public AuthFastLeaderElection(QuorumPeer self,
            boolean auth) {
        this.authEnabled = auth;
        starter(self);
    }

    public AuthFastLeaderElection(QuorumPeer self) {
        starter(self);
    }

    private void starter(QuorumPeer self) {
        this.self = self;
        port = self.getVotingView().get(self.getId()).electionAddr.getPort();
        proposedLeader = -1;
        proposedZxid = -1;

        try {
            mySocket = new DatagramSocket(port);
            // mySocket.setSoTimeout(20000);
        } catch (SocketException e1) {
            e1.printStackTrace();
            throw new RuntimeException();
        }
        sendqueue = new LinkedBlockingQueue<ToSend>(2 * self.getVotingView().size());
        recvqueue = new LinkedBlockingQueue<Notification>(2 * self.getVotingView()
                .size());
        new Messenger(self.getVotingView().size() * 2, mySocket);
    }

    private void leaveInstance() {
        logicalclock++;
    }

    private void sendNotifications() {
        for (QuorumServer server : self.getView().values()) {

            ToSend notmsg = new ToSend(ToSend.mType.notification,
                    AuthFastLeaderElection.sequencer++, proposedLeader,
                    proposedZxid, logicalclock, QuorumPeer.ServerState.LOOKING,
                    self.getView().get(server.id).electionAddr);

            sendqueue.offer(notmsg);
        }
    }

    private boolean totalOrderPredicate(long id, long zxid) {
        if ((zxid > proposedZxid)
                || ((zxid == proposedZxid) && (id > proposedLeader)))
            return true;
        else
            return false;

    }

    private boolean termPredicate(HashMap<InetSocketAddress, Vote> votes,
            long l, long zxid) {


        Collection<Vote> votesCast = votes.values();
        int count = 0;
        /*
         * First make the views consistent. Sometimes peers will have different
         * zxids for a server depending on timing.
         */
        for (Vote v : votesCast) {
            if ((v.id == l) && (v.zxid == zxid))
                count++;
        }

        if (count > (self.getVotingView().size() / 2))
            return true;
        else
            return false;

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
            HashMap<InetSocketAddress, Vote> recvset = 
                new HashMap<InetSocketAddress, Vote>();
    
            HashMap<InetSocketAddress, Vote> outofelection = 
                new HashMap<InetSocketAddress, Vote>();
    
            logicalclock++;
    
            proposedLeader = self.getId();
            proposedZxid = self.getLastLoggedZxid();
    
            LOG.info("Election tally");
            sendNotifications();
    
            /*
             * Loop in which we exchange notifications until we find a leader
             */
    
            while (self.getPeerState() == ServerState.LOOKING) {
                /*
                 * Remove next notification from queue, times out after 2 times
                 * the termination time
                 */
                Notification n = recvqueue.poll(2 * finalizeWait,
                        TimeUnit.MILLISECONDS);
    
                /*
                 * Sends more notifications if haven't received enough.
                 * Otherwise processes new notification.
                 */
                if (n == null) {
                    if (((!outofelection.isEmpty()) || (recvset.size() > 1)))
                        sendNotifications();
                } else
                    switch (n.state) {
                    case LOOKING:
                        if (n.epoch > logicalclock) {
                            logicalclock = n.epoch;
                            recvset.clear();
                            if (totalOrderPredicate(n.leader, n.zxid)) {
                                proposedLeader = n.leader;
                                proposedZxid = n.zxid;
                            }
                            sendNotifications();
                        } else if (n.epoch < logicalclock) {
                            break;
                        } else if (totalOrderPredicate(n.leader, n.zxid)) {
                            proposedLeader = n.leader;
                            proposedZxid = n.zxid;
    
                            sendNotifications();
                        }
    
                        recvset.put(n.addr, new Vote(n.leader, n.zxid));
    
                        // If have received from all nodes, then terminate
                        if (self.getVotingView().size() == recvset.size()) {
                            self.setPeerState((proposedLeader == self.getId()) ? 
                                    ServerState.LEADING: ServerState.FOLLOWING);
                            // if (self.state == ServerState.FOLLOWING) {
                            // Thread.sleep(100);
                            // }
                            leaveInstance();
                            return new Vote(proposedLeader, proposedZxid);
    
                        } else if (termPredicate(recvset, proposedLeader,
                                proposedZxid)) {
                            // Otherwise, wait for a fixed amount of time
                            LOG.info("Passed predicate");
                            Thread.sleep(finalizeWait);
    
                            // Notification probe = recvqueue.peek();
    
                            // Verify if there is any change in the proposed leader
                            while ((!recvqueue.isEmpty())
                                    && !totalOrderPredicate(
                                            recvqueue.peek().leader, recvqueue
                                                    .peek().zxid)) {
                                recvqueue.poll();
                            }
                            if (recvqueue.isEmpty()) {
                                // LOG.warn("Proposed leader: " +
                                // proposedLeader);
                                self.setPeerState(
                                        (proposedLeader == self.getId()) ? 
                                         ServerState.LEADING :
                                         ServerState.FOLLOWING);
    
                                leaveInstance();
                                return new Vote(proposedLeader, proposedZxid);
                            }
                        }
                        break;
                    case LEADING:
                        outofelection.put(n.addr, new Vote(n.leader, n.zxid));
    
                        if (termPredicate(outofelection, n.leader, n.zxid)) {
    
                            self.setPeerState((n.leader == self.getId()) ? 
                                    ServerState.LEADING: ServerState.FOLLOWING);
    
                            leaveInstance();
                            return new Vote(n.leader, n.zxid);
                        }
                        break;
                    case FOLLOWING:
                        outofelection.put(n.addr, new Vote(n.leader, n.zxid));
    
                        if (termPredicate(outofelection, n.leader, n.zxid)) {
    
                            self.setPeerState((n.leader == self.getId()) ? 
                                    ServerState.LEADING: ServerState.FOLLOWING);
    
                            leaveInstance();
                            return new Vote(n.leader, n.zxid);
                        }
                        break;
                    default:
                        break;
                    }
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
