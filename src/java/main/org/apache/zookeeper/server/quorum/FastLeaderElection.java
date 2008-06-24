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


package org.apache.zookeeper.server.quorum;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import org.apache.zookeeper.server.quorum.QuorumCnxManager.Message;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;


/**
 * Implementation of leader election using TCP. It uses an object of the class 
 * QuorumCnxManager to manage connections. Otherwise, the algorithm is push-based
 * as with the other UDP implementations. 
 * 
 * There are a few parameters that can be tuned to change its behavior. First, 
 * finalizeWait determines the amount of time to wait until deciding upon a leader.
 * This is part of the leader election algorithm.
 */


public class FastLeaderElection implements Election {
    private static final Logger LOG = Logger.getLogger(FastLeaderElection.class);

	/* Sequence numbers for messages */
    static int sequencer = 0;

    /*
     * Determine how much time a process has to wait
     * once it believes that it has reached the end of
     * leader election.
     */
    static int finalizeWait = 100;

    /*
	 * Challenge counter to avoid replay attacks
	 */
	
	static int challengeCounter = 0;
	
    
	/*
	 * Connection manager
	 */
	
	QuorumCnxManager manager;

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
         * Address of sender
         */
        InetAddress addr;
    }

    /*
     * Messages to send, both Notifications and Acks
     */
    static public class ToSend {
    	static enum mType {crequest, challenge, notification, ack};
        
        ToSend(mType type, 
        		long leader, 
        		long zxid, 
        		long epoch, 
        		ServerState state,
        		InetAddress addr) {
        
        	this.leader = leader;
        	this.zxid = zxid;
        	this.epoch = epoch;
        	this.state = state;
        	this.addr = addr;
        }
        
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
         * Address of recipient
         */
        InetAddress addr;
    }

    LinkedBlockingQueue<ToSend> sendqueue;
    LinkedBlockingQueue<Notification> recvqueue;

    private class Messenger {
    	
        long lastProposedLeader;
        long lastProposedZxid;
        long lastEpoch;
        
        class WorkerReceiver implements Runnable {

        	QuorumCnxManager manager;

            WorkerReceiver(QuorumCnxManager manager) {
                this.manager = manager;
            }

            public void run() {
                
            	Message response;
            	while (true) {
                    // Sleeps on receive
            		try{
            			response = manager.recvQueue.take();
            			
            			// Receive new message
            			if (response.buffer.capacity() < 28) {
            				LOG.error("Got a short response: "
            						+ response.buffer.capacity());
            				continue;
            			}
            			response.buffer.clear();
               

            			QuorumPeer.ServerState ackstate = QuorumPeer.ServerState.LOOKING;
            			switch (response.buffer.getInt()) {
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
                    	
            			Notification n = new Notification();
            			n.leader = response.buffer.getLong();
            			n.zxid = response.buffer.getLong();
            			n.epoch = response.buffer.getLong();
            			n.state = ackstate;
            			n.addr = response.addr;

            			if ((messenger.lastEpoch <= n.epoch)
            					&& ((n.zxid > messenger.lastProposedZxid) 
            					|| ((n.zxid == messenger.lastProposedZxid) 
            					&& (n.leader > messenger.lastProposedLeader)))) {
            				messenger.lastProposedZxid = n.zxid;
            				messenger.lastProposedLeader = n.leader;
            				messenger.lastEpoch = n.epoch;
            			}

            			//InetAddress addr = (InetAddress) responsePacket.getSocketAddress();
            			if(self.getPeerState() == QuorumPeer.ServerState.LOOKING){
            				recvqueue.offer(n);
            				if(recvqueue.size() == 0) LOG.warn("Message: " + n.addr);
            				if((ackstate == QuorumPeer.ServerState.LOOKING)
            						&& (n.epoch < logicalclock)){
            					ToSend notmsg = new ToSend(ToSend.mType.notification, 
                						proposedLeader, 
                						proposedZxid,
                						logicalclock,
                						self.getPeerState(),
                						response.addr);
                				sendqueue.offer(notmsg);
            				}
            			} else { 	           				
            				if((ackstate == QuorumPeer.ServerState.LOOKING) &&
            						(self.getPeerState() != QuorumPeer.ServerState.LOOKING)){
            					ToSend notmsg = new ToSend(ToSend.mType.notification, 
            						self.currentVote.id, 
            						self.currentVote.zxid,
            						logicalclock,
            						self.getPeerState(),
            						response.addr);
            					sendqueue.offer(notmsg);
            				}
            			}
            			
            		} catch (InterruptedException e) {
            			System.out.println("Interrupted Exception while waiting for new message" +
            					e.toString());
            		}
            	}
            }
        }

        class WorkerSender implements Runnable {
        	
            QuorumCnxManager manager;

            WorkerSender(QuorumCnxManager manager){ 
                this.manager = manager;
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
                byte requestBytes[] = new byte[28];
                ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);  
                
                /*
                 * Building notification packet to send
                 */
                    
                requestBuffer.clear();
                requestBuffer.putInt(m.state.ordinal());
                requestBuffer.putLong(m.leader);
                requestBuffer.putLong(m.zxid);
                requestBuffer.putLong(m.epoch);
                
                manager.toSend(m.addr, requestBuffer);
                  
            }
        }

        public boolean queueEmpty() {
            return (sendqueue.isEmpty() || recvqueue.isEmpty());
        }

        Messenger(QuorumCnxManager manager) {
            lastProposedLeader = 0;
            lastProposedZxid = 0;
            lastEpoch = 0;

            Thread t = new Thread(new WorkerSender(manager),
            		"WorkerSender Thread");
            t.setDaemon(true);
            t.start();

            t = new Thread(new WorkerReceiver(manager),
                    				"WorkerReceiver Thread");
            t.setDaemon(true);
            t.start();
        }

    }

    QuorumPeer self;
    int port;
    long logicalclock; /* Election instance */
    Messenger messenger;
    long proposedLeader;
    long proposedZxid;

        
    public FastLeaderElection(QuorumPeer self, QuorumCnxManager manager){
    	this.manager = manager;
    	starter(self, manager);
    }
    
    private void starter(QuorumPeer self, QuorumCnxManager manager) {
        this.self = self;
        proposedLeader = -1;
        proposedZxid = -1;

        sendqueue = new LinkedBlockingQueue<ToSend>();
        recvqueue = new LinkedBlockingQueue<Notification>();
        messenger = new Messenger(manager);
    }

    private void leaveInstance() {
        recvqueue.clear();
    }

    public static class ElectionResult {
        public Vote vote;

        public int count;

        public Vote winner;

        public int winningCount;
    }

    private void sendNotifications() {
        for (QuorumServer server : self.quorumPeers) {
            InetAddress saddr = server.addr.getAddress();

            ToSend notmsg = new ToSend(ToSend.mType.notification, 
            		proposedLeader, 
            		proposedZxid,
                    logicalclock,
                    QuorumPeer.ServerState.LOOKING,
                    saddr);

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

    private boolean termPredicate(
            HashMap<InetAddress, Vote> votes, long l,
            long zxid) {

        int count = 0;
        Collection<Vote> votesCast = votes.values();
        /*
         * First make the views consistent. Sometimes peers will have
         * different zxids for a server depending on timing.
         */
        for (Vote v : votesCast) {
            if ((v.id == l) && (v.zxid == zxid))
                count++;
        }
        
        if (count > (self.quorumPeers.size() / 2))
            return true;
        else
            return false;

    }

    public Vote lookForLeader() throws InterruptedException {
        HashMap<InetAddress, Vote> recvset = new HashMap<InetAddress, Vote>();

        HashMap<InetAddress, Vote> outofelection = new HashMap<InetAddress, Vote>();

        logicalclock++;

        proposedLeader = self.getId();
        proposedZxid = self.getLastLoggedZxid();

        LOG.warn("Election tally: " + proposedZxid);
        sendNotifications();

        /*
         * Loop in which we exchange notifications until we find a leader
         */

        while (self.getPeerState() == ServerState.LOOKING) {
            /*
             * Remove next notification from queue, times out after 2 times
             * the termination time
             */
            Notification n = recvqueue.poll(2*finalizeWait, TimeUnit.MILLISECONDS);
            
            /*
             * Sends more notifications if haven't received enough.
             * Otherwise processes new notification.
             */
            if(n == null){
            	if(manager.haveDelivered()){
            		sendNotifications();
            	}
            }
            else switch (n.state) {
            case LOOKING:
            	// If notification > current, replace and send messages out
            	if (n.epoch > logicalclock) {
                    logicalclock = n.epoch;
                    recvset.clear();
                    if(totalOrderPredicate(n.leader, n.zxid)){
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

                recvset.put(n.addr, new Vote(n.leader,
                        n.zxid));

                //If have received from all nodes, then terminate
                if (self.quorumPeers.size() == recvset.size()) {
                    self.setPeerState((proposedLeader == self.getId()) ? 
                    		ServerState.LEADING: ServerState.FOLLOWING);
                    leaveInstance();
                    return new Vote(proposedLeader, proposedZxid);

                } else if (termPredicate(recvset, proposedLeader, proposedZxid)) {
                    //Otherwise, wait for a fixed amount of time
                    LOG.warn("Passed predicate");
                    Thread.sleep(finalizeWait);

                    // Verify if there is any change in the proposed leader
                    while ((!recvqueue.isEmpty())
                            && !totalOrderPredicate(recvqueue.peek().leader,
                                    recvqueue.peek().zxid)) {
                        recvqueue.poll();
                    }
                    if (recvqueue.isEmpty()) {
                        self.setPeerState((proposedLeader == self.getId()) ? 
                        		ServerState.LEADING: ServerState.FOLLOWING);
                        leaveInstance();
                        return new Vote(proposedLeader,
                                proposedZxid);
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
    }
}
