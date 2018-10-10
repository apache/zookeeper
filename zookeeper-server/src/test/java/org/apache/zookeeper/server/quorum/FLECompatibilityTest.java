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

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.FastLeaderElection.Notification;
import org.apache.zookeeper.server.quorum.FastLeaderElection.ToSend;
import org.apache.zookeeper.server.quorum.FastLeaderElection.Messenger.WorkerReceiver;
import org.apache.zookeeper.server.quorum.QuorumCnxManager.Message;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.FLETest;
import org.apache.zookeeper.test.QuorumBase;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FLECompatibilityTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(FLECompatibilityTest.class);

    int count;
    HashMap<Long,QuorumServer> peers;
    File tmpdir[];
    int port[];
    
    @Before
    public void setUp() throws Exception {
        count = 3;
        peers = new HashMap<Long,QuorumServer>(count);
        tmpdir = new File[count];
        port = new int[count];
    }
    
    @After
    public void tearDown() throws Exception {
        
    }
    
    class MockFLEMessengerBackward {   
        QuorumCnxManager manager;
        QuorumPeer self;
        long logicalclock = 1L;
        LinkedBlockingQueue<ToSend> sendqueue = new LinkedBlockingQueue<ToSend>();
        LinkedBlockingQueue<ToSend> internalqueue = new LinkedBlockingQueue<ToSend>();
        LinkedBlockingQueue<Notification> recvqueue = new LinkedBlockingQueue<Notification>();
        WorkerReceiver wr;
        
        MockFLEMessengerBackward(QuorumPeer self, QuorumCnxManager manager){
            this.manager = manager;
            this.self = self;
            
            this.wr = new WorkerReceiver(manager);

            Thread t = new Thread(this.wr,
                    "WorkerReceiver[myid=" + self.getId() + "]");
            t.setDaemon(true);
            t.start();
        }
        
        void halt() {
            wr.stop = true;
        }
        
        /*
         * This class has been copied from before adding versions to notifications.
         * 
         * {@see https://issues.apache.org/jira/browse/ZOOKEEPER-1808}
         */
        class WorkerReceiver implements Runnable {
            volatile boolean stop;
            QuorumCnxManager manager;
            final long proposedLeader = 2;
            final long proposedZxid = 0x1;
            final long proposedEpoch = 1;

            WorkerReceiver(QuorumCnxManager manager) {
                this.stop = false;
                this.manager = manager;
            }

            /*
             * The vote we return here is fixed for test purposes.
             */
            Vote getVote(){
                return new Vote(proposedLeader, proposedZxid, proposedEpoch);
            }
            
            public void run() {

                Message response;
                while (!stop) {
                    // Sleeps on receive
                    try{
                        response = manager.pollRecvQueue(3000, TimeUnit.MILLISECONDS);
                        if(response == null) continue;

                        /*
                         * If it is from an observer, respond right away.
                         * Note that the following predicate assumes that
                         * if a server is not a follower, then it must be
                         * an observer. If we ever have any other type of
                         * learner in the future, we'll have to change the
                         * way we check for observers.
                         */
                        if(!self.getVotingView().containsKey(response.sid)){
                            Vote current = self.getCurrentVote();
                            ToSend notmsg = new ToSend(ToSend.mType.notification,
                                    current.getId(),
                                    current.getZxid(),
                                    logicalclock,
                                    self.getPeerState(),
                                    response.sid,
                                    current.getPeerEpoch());

                            internalqueue.offer(notmsg);
                        } else {
                            // Receive new message
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Receive new notification message. My id = "
                                        + self.getId());
                            }

                            /*
                             * We check for 28 bytes for backward compatibility
                             */
                            if (response.buffer.capacity() < 28) {
                                LOG.error("Got a short response: "
                                        + response.buffer.capacity());
                                continue;
                            }
                            boolean backCompatibility = (response.buffer.capacity() == 28);
                            response.buffer.clear();

                            // State of peer that sent this message
                            QuorumPeer.ServerState ackstate = QuorumPeer.ServerState.LOOKING;
                            switch (response.buffer.getInt()) {
                            case 0:
                                ackstate = QuorumPeer.ServerState.LOOKING;
                                break;
                            case 1:
                                ackstate = QuorumPeer.ServerState.FOLLOWING;
                                break;
                            case 2:
                                ackstate = QuorumPeer.ServerState.LEADING;
                                break;
                            case 3:
                                ackstate = QuorumPeer.ServerState.OBSERVING;
                                break;
                            }

                            // Instantiate Notification and set its attributes
                            Notification n = new Notification();
                            n.leader = response.buffer.getLong();
                            n.zxid = response.buffer.getLong();
                            n.electionEpoch = response.buffer.getLong();
                            n.state = ackstate;
                            n.sid = response.sid;
                            if(!backCompatibility){
                                n.peerEpoch = response.buffer.getLong();
                            } else {
                                if(LOG.isInfoEnabled()){
                                    LOG.info("Backward compatibility mode, server id=" + n.sid);
                                }
                                n.peerEpoch = ZxidUtils.getEpochFromZxid(n.zxid);
                            }

                            /*
                             * If this server is looking, then send proposed leader
                             */

                            if(self.getPeerState() == QuorumPeer.ServerState.LOOKING){
                                recvqueue.offer(n);

                                /*
                                 * Send a notification back if the peer that sent this
                                 * message is also looking and its logical clock is
                                 * lagging behind.
                                 */
                                if((ackstate == QuorumPeer.ServerState.LOOKING)
                                        && (n.electionEpoch < logicalclock)){
                                    Vote v = getVote();
                                    ToSend notmsg = new ToSend(ToSend.mType.notification,
                                            v.getId(),
                                            v.getZxid(),
                                            logicalclock,
                                            self.getPeerState(),
                                            response.sid,
                                            v.getPeerEpoch());
                                    internalqueue.offer(notmsg);
                                }
                            } else {
                                /*
                                 * If this server is not looking, but the one that sent the ack
                                 * is looking, then send back what it believes to be the leader.
                                 */
                                Vote current = self.getCurrentVote();
                                if(ackstate == QuorumPeer.ServerState.LOOKING){
                                    if(LOG.isDebugEnabled()){
                                        LOG.debug("Sending new notification. My id =  " +
                                                self.getId() + " recipient=" +
                                                response.sid + " zxid=0x" +
                                                Long.toHexString(current.getZxid()) +
                                                " leader=" + current.getId());
                                    }
                                    ToSend notmsg = new ToSend(
                                            ToSend.mType.notification,
                                            current.getId(),
                                            current.getZxid(),
                                            current.getElectionEpoch(),
                                            self.getPeerState(),
                                            response.sid,
                                            current.getPeerEpoch());
                                    internalqueue.offer(notmsg);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        System.out.println("Interrupted Exception while waiting for new message" +
                                e.toString());
                    }
                }
                LOG.info("WorkerReceiver is down");
            }
        }
    }
    
    class MockFLEMessengerForward extends FastLeaderElection {
        
        MockFLEMessengerForward(QuorumPeer self, QuorumCnxManager manager){
            super( self, manager );
        }
        
        void halt() {
            super.shutdown();
        }
    }
    
    void populate()
    throws Exception {
        for (int i = 0; i < count; i++) {
            peers.put(Long.valueOf(i),
                      new QuorumServer(i, "0.0.0.0",
                                       PortAssignment.unique(),
                                       PortAssignment.unique(), null));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = PortAssignment.unique();
        }
    }
    
    @Test(timeout=20000)
    public void testBackwardCompatibility() 
    throws Exception {
        populate();
        
        QuorumPeer peer = new QuorumPeer(peers, tmpdir[0], tmpdir[0], port[0], 3, 0, 1000, 2, 2);
        peer.setPeerState(ServerState.LOOKING);
        QuorumCnxManager mng = peer.createCnxnManager();
        
        /*
         * Check that it generates an internal notification correctly
         */
        MockFLEMessengerBackward fle = new MockFLEMessengerBackward(peer, mng);
        ByteBuffer buffer = FastLeaderElection.buildMsg(ServerState.LOOKING.ordinal(), 2, 0x1, 1, 1);
        fle.manager.recvQueue.add(new Message(buffer, 2));
        Notification n = fle.recvqueue.take();
        Assert.assertTrue("Wrong state", n.state == ServerState.LOOKING);
        Assert.assertTrue("Wrong leader", n.leader == 2);
        Assert.assertTrue("Wrong zxid", n.zxid == 0x1);
        Assert.assertTrue("Wrong epoch", n.electionEpoch == 1);
        Assert.assertTrue("Wrong epoch", n.peerEpoch == 1);
        
        /*
         * Check that it sends a notification back to the sender
         */
        peer.setPeerState(ServerState.FOLLOWING);
        peer.setCurrentVote( new Vote(2, 0x1, 1, 1, ServerState.LOOKING) );
        buffer = FastLeaderElection.buildMsg(ServerState.LOOKING.ordinal(), 1, 0x1, 1, 1);
        fle.manager.recvQueue.add(new Message(buffer, 1));
        ToSend m = fle.internalqueue.take();
        Assert.assertTrue("Wrong state", m.state == ServerState.FOLLOWING);
        Assert.assertTrue("Wrong sid", m.sid == 1);
        Assert.assertTrue("Wrong leader", m.leader == 2);
        Assert.assertTrue("Wrong epoch", m.electionEpoch == 1);
        Assert.assertTrue("Wrong epoch", m.peerEpoch == 1);
    }
    
    @Test(timeout=20000)
    public void testForwardCompatibility() 
    throws Exception {
        populate();

        QuorumPeer peer = new QuorumPeer(peers, tmpdir[0], tmpdir[0], port[0], 3, 0, 1000, 2, 2);
        peer.setPeerState(ServerState.LOOKING);
        QuorumCnxManager mng = peer.createCnxnManager();
        
        /*
         * Check that it generates an internal notification correctly
         */
        MockFLEMessengerForward fle = new MockFLEMessengerForward(peer, mng);
        ByteBuffer notBuffer = FastLeaderElection.buildMsg(ServerState.LOOKING.ordinal(), 2, 0x1, 1, 1);
        ByteBuffer buffer = ByteBuffer.allocate( notBuffer.capacity() + 8 );
        notBuffer.flip();
        buffer.put(notBuffer);
        buffer.putLong( Long.MAX_VALUE );
        buffer.flip();
        
        fle.manager.recvQueue.add(new Message(buffer, 2));
        Notification n = fle.recvqueue.take();
        Assert.assertTrue("Wrong state", n.state == ServerState.LOOKING);
        Assert.assertTrue("Wrong leader", n.leader == 2);
        Assert.assertTrue("Wrong zxid", n.zxid == 0x1);
        Assert.assertTrue("Wrong epoch", n.electionEpoch == 1);
        Assert.assertTrue("Wrong epoch", n.peerEpoch == 1);
        Assert.assertTrue("Wrong version", n.version == FastLeaderElection.Notification.CURRENTVERSION);
    }
}
