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

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.quorum.QuorumCnxManager.Message;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
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

    /**
     * Determine how much time a process has to wait
     * once it believes that it has reached the end of
     * leader election.
     */
    final static int finalizeWait = 200;


	/**
	 * Upper bound on the amount of time between two consecutive
	 * notification checks. This impacts the amount of time to get
	 * the system up again after long partitions. Currently 60 seconds. 
	 */
	
    final static int maxNotificationInterval = 60000;
    
	/**
	 * Connection manager. Fast leader election uses TCP for 
	 * communication between peers, and QuorumCnxManager manages
	 * such connections. 
	 */
	
	QuorumCnxManager manager;

	
	/**
	 * Notifications are messages that let other peers know that
	 * a given peer has changed its vote, either because it has
	 * joined leader election or because it learned of another 
	 * peer with higher zxid or same zxid and higher server id
	 */
	
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
        long sid;
    }

    /**
     * Messages that a peer wants to send to other peers.
     * These messages can be both Notifications and Acks
     * of reception of notification.
     */
    static public class ToSend {
    	static enum mType {crequest, challenge, notification, ack}
        
        ToSend(mType type, 
        		long leader, 
        		long zxid, 
        		long epoch, 
        		ServerState state,
        		long sid) {
        
        	this.leader = leader;
        	this.zxid = zxid;
        	this.epoch = epoch;
        	this.state = state;
        	this.sid = sid;
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
        long sid;
    }

    LinkedBlockingQueue<ToSend> sendqueue;
    LinkedBlockingQueue<Notification> recvqueue;

    /**
     * Multi-threaded implementation of message handler. Messenger
     * implements two sub-classes: WorkReceiver and  WorkSender. The
     * functionality of each is obvious from the name. Each of these
     * spawns a new thread.
     */
    
    private class Messenger {
        
        /**
         * Receives messages from instance of QuorumCnxManager on
         * method run(), and processes such messages.
         */
        
        class WorkerReceiver implements Runnable {
            volatile boolean stop;
        	QuorumCnxManager manager;

            WorkerReceiver(QuorumCnxManager manager) {
                this.stop = false;
                this.manager = manager;
            }

            public void run() {
                
            	Message response;
            	while (!stop) {
                    // Sleeps on receive
            		try{
            			response = manager.recvQueue.poll(3000, TimeUnit.MILLISECONDS);
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
            			            current.id, 
                                    current.zxid,
            	                    logicalclock,
            	                    self.getPeerState(),
            	                    response.sid);

            	            sendqueue.offer(notmsg);
            			} else {           			
            			    // Receive new message
            			    if (LOG.isDebugEnabled()) {
            			        LOG.debug("Receive new notification message. My id = " 
            			                + self.getId());
            			    }
            			    
            			    if (response.buffer.capacity() < 28) {
            			        LOG.error("Got a short response: "
            			                + response.buffer.capacity());
            			        continue;
            			    }
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
            			    n.epoch = response.buffer.getLong();
            			    n.state = ackstate;
            			    n.sid = response.sid;

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
            			                && (n.epoch < logicalclock)){
            			            Vote v = getVote();
            			            ToSend notmsg = new ToSend(ToSend.mType.notification, 
            			                    v.id, 
            			                    v.zxid,
            			                    logicalclock,
            			                    self.getPeerState(),
            			                    response.sid);
            			            sendqueue.offer(notmsg);
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
            			                        self.getId() + ", Recipient = " +
            			                        response.sid);
            			            }
            			            ToSend notmsg = new ToSend(
            			                    ToSend.mType.notification, 
            			                    current.id, 
            			                    current.zxid,
            			                    logicalclock,
            			                    self.getPeerState(),
            			                    response.sid);
            			            sendqueue.offer(notmsg);
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

        
        /**
         * This worker simply dequeues a message to send and
         * and queues it on the manager's queue. 
         */
        
        class WorkerSender implements Runnable {
        	volatile boolean stop;
            QuorumCnxManager manager;

            WorkerSender(QuorumCnxManager manager){ 
                this.stop = false;
                this.manager = manager;
            }
            
            public void run() {
                while (!stop) {
                    try {
                        ToSend m = sendqueue.poll(3000, TimeUnit.MILLISECONDS);
                        if(m == null) continue;
                        
                        process(m);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                LOG.info("WorkerSender is down");
            }

            /**
             * Called by run() once there is a new message to send.
             * 
             * @param m     message to send
             */
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
                
                manager.toSend(m.sid, requestBuffer);
                  
            }
        }

        /**
         * Test if both send and receive queues are empty.
         */
        public boolean queueEmpty() {
            return (sendqueue.isEmpty() || recvqueue.isEmpty());
        }

        
        WorkerSender ws;
        WorkerReceiver wr;
        
        /**
         * Constructor of class Messenger.
         * 
         * @param manager   Connection manager
         */
        Messenger(QuorumCnxManager manager) {

            this.ws = new WorkerSender(manager);
            
            Thread t = new Thread(this.ws,
            		"WorkerSender Thread");
            t.setDaemon(true);
            t.start();

            this.wr = new WorkerReceiver(manager);
        
            t = new Thread(this.wr,
                    				"WorkerReceiver Thread");
            t.setDaemon(true);
            t.start();
        }
        
        /**
         * Stops instances of WorkerSender and WorkerReceiver
         */
        void halt(){
            this.ws.stop = true;
            this.wr.stop = true;
        }

    }

    QuorumPeer self;    
    Messenger messenger;
    volatile long logicalclock; /* Election instance */
    long proposedLeader;
    long proposedZxid;


    /**
     * Returns the current vlue of the logical clock counter
     */
    public long getLogicalClock(){
	return logicalclock;
    }
    
    /**
     * Constructor of FastLeaderElection. It takes two parameters, one
     * is the QuorumPeer object that instantiated this object, and the other
     * is the connection manager. Such an object should be created only once 
     * by each peer during an instance of the ZooKeeper service.
     * 
     * @param self  QuorumPeer that created this object
     * @param manager   Connection manager
     */
    public FastLeaderElection(QuorumPeer self, QuorumCnxManager manager){
    	this.stop = false;
        this.manager = manager;
    	starter(self, manager);
    }
    
    /**
     * This method is invoked by the constructor. Because it is a
     * part of the starting procedure of the object that must be on
     * any constructor of this class, it is probably best to keep as
     * a separate method. As we have a single constructor currently, 
     * it is not strictly necessary to have it separate.
     * 
     * @param self      QuorumPeer that created this object
     * @param manager   Connection manager   
     */
    private void starter(QuorumPeer self, QuorumCnxManager manager) {
        this.self = self;
        proposedLeader = -1;
        proposedZxid = -1;

        sendqueue = new LinkedBlockingQueue<ToSend>();
        recvqueue = new LinkedBlockingQueue<Notification>();
        this.messenger = new Messenger(manager);
    }

    private void leaveInstance() {
        recvqueue.clear();
    }
    
    public QuorumCnxManager getCnxManager(){
    	return manager;
    }
    
    volatile boolean stop;
    public void shutdown(){
        stop = true;
        LOG.debug("Shutting down connection manager");
        manager.halt();
        LOG.debug("Shutting down messenger");
        messenger.halt();
        LOG.debug("FLE is down");
    }

    
    /**
     * Send notifications to all peers upon a change in our vote
     */
    private void sendNotifications() {
        for (QuorumServer server : self.getVotingView().values()) {
            long sid = server.id;

            ToSend notmsg = new ToSend(ToSend.mType.notification, 
            		proposedLeader, 
            		proposedZxid,
                    logicalclock,
                    QuorumPeer.ServerState.LOOKING,
                    sid);

            sendqueue.offer(notmsg);
        }
    }

    /**
     * Check if a pair (server id, zxid) succeeds our
     * current vote.
     * 
     * @param id    Server identifier
     * @param zxid  Last zxid observed by the issuer of this vote
     */
    private boolean totalOrderPredicate(long newId, long newZxid, long curId, long curZxid) {
        LOG.debug("id: " + newId + ", proposed id: " + curId + ", zxid: " + newZxid + ", proposed zxid: " + curZxid);
        if(self.getQuorumVerifier().getWeight(newId) == 0){
            return false;
        }
        
        if ((newZxid > curZxid)
                || ((newZxid == curZxid) && (newId > curId)))
            return true;
        else
            return false;

    }

    /**
     * Termination predicate. Given a set of votes, determines if
     * have sufficient to declare the end of the election round.
     * 
     *  @param votes    Set of votes
     *  @param l        Identifier of the vote received last
     *  @param zxid     zxid of the the vote received last
     */
    private boolean termPredicate(
            HashMap<Long, Vote> votes, 
            Vote vote) {

        HashSet<Long> set = new HashSet<Long>();
        
        /*
         * First make the views consistent. Sometimes peers will have
         * different zxids for a server depending on timing.
         */
        for (Map.Entry<Long,Vote> entry : votes.entrySet()) {
            if (vote.equals(entry.getValue())){
                set.add(entry.getKey());
            }
        }
                      
        if(self.getQuorumVerifier().containsQuorum(set))
            return true;
        else
            return false;

    }

    /**
     * In the case there is a leader elected, and a quorum supporting 
     * this leader, we have to check if the leader has voted and acked
     * that it is leading. We need this check to avoid that peers keep
     * electing over and over a peer that has crashed and it is no
     * longer leading.
     * 
     * @param votes set of votes
     * @param   leader  leader id
     * @param   epoch   epoch id
     */
    private boolean checkLeader(
            HashMap<Long, Vote> votes,
            long leader,
            long epoch){
        
        boolean predicate = true;

        /*
         * If everyone else thinks I'm the leader, I must be the leader.
         * The other two checks are just for the case in which I'm not the
         * leader. If I'm not the leader and I haven't received a message 
         * from leader stating that it is leading, then predicate is false.          
         */
        
        if(leader != self.getId()){
            if(votes.get(leader) == null) predicate = false;
            else if(votes.get(leader).state != ServerState.LEADING) predicate = false;
        }
        
        //LOG.info("Leader predicate: " + predicate);
        return predicate;
    }
    
    synchronized void updateProposal(long leader, long zxid){
        proposedLeader = leader;
        proposedZxid = zxid;
    }
    
    synchronized Vote getVote(){
        return new Vote(proposedLeader, proposedZxid);
    }
    
    /**
     * A learning state can be either FOLLOWING or OBSERVING. 
     * This method simply decides which one depending on the 
     * role of the server.
     * 
     * @return ServerState
     */
    private ServerState learningState(){
        if(self.getLearnerType() == LearnerType.PARTICIPANT){
            LOG.debug("I'm a participant: " + self.getId());
            return ServerState.FOLLOWING;
        }
        else{ 
            LOG.debug("I'm an observer: " + self.getId());
            return ServerState.OBSERVING;
        }
    }
    
    /**
     * Returns the initial vote value of server identifier.
     * 
     * @return long
     */
    private long getInitId(){
        if(self.getLearnerType() == LearnerType.PARTICIPANT)
            return self.getId();
        else return Long.MIN_VALUE;
    }
    
    /**
     * Returns initial last logged zxid.
     * 
     * @return long
     */
    private long getInitLastLoggedZxid(){
        if(self.getLearnerType() == LearnerType.PARTICIPANT)
            return self.getLastLoggedZxid();
        else return Long.MIN_VALUE;
    }
    
    /**
     * Starts a new round of leader election. Whenever our QuorumPeer 
     * changes its state to LOOKING, this method is invoked, and it 
     * sends notifications to all other peers.
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
            HashMap<Long, Vote> recvset = new HashMap<Long, Vote>();
    
            HashMap<Long, Vote> outofelection = new HashMap<Long, Vote>();
    
            int notTimeout = finalizeWait;
            
            synchronized(this){
                logicalclock++;
                    updateProposal(getInitId(), getInitLastLoggedZxid());
            }
            
            LOG.info("New election. My id =  " + self.getId() +
                    ", Proposed zxid = " + proposedZxid);
            sendNotifications();
    
            /*
             * Loop in which we exchange notifications until we find a leader
             */
    
            while ((self.getPeerState() == ServerState.LOOKING) &&
                    (!stop)){
                /*
                 * Remove next notification from queue, times out after 2 times
                 * the termination time
                 */
                Notification n = recvqueue.poll(notTimeout,
                        TimeUnit.MILLISECONDS);
                
                /*
                 * Sends more notifications if haven't received enough.
                 * Otherwise processes new notification.
                 */
                if(n == null){
                	if(manager.haveDelivered()){
                		sendNotifications();
                	} else {
                	    manager.connectAll();
                	}
                	
                	/*
                	 * Exponential backoff
                	 */
                	int tmpTimeOut = notTimeout*2;
                	notTimeout = (tmpTimeOut < maxNotificationInterval?
                	        tmpTimeOut : maxNotificationInterval);
                	LOG.info("Notification time out: " + notTimeout);
                }
                else{                    
                    switch (n.state) { 
                    case LOOKING:
                        // If notification > current, replace and send messages out
                        LOG.info("Notification: " + n.leader + ", " + n.zxid
                                + ", " + n.epoch + ", " + self.getId() + ", "
                                + self.getPeerState() + ", " + n.state + ", "
                                + n.sid);
                        if (n.epoch > logicalclock) {
                            logicalclock = n.epoch;
                            recvset.clear();
                            if(totalOrderPredicate(n.leader, n.zxid,
                                    getInitId(), getInitLastLoggedZxid()))
                                updateProposal(n.leader, n.zxid);
                            else
                                updateProposal(getInitId(),
                                        getInitLastLoggedZxid());
                            sendNotifications();
                        } else if (n.epoch < logicalclock) {
                            if(LOG.isDebugEnabled()){
                                LOG.debug("Notification epoch is smaller than logicalclock. n.epoch = " + n.epoch 
                                        + ", Logical clock" + logicalclock);
                            }
                            break;
                        } else if (totalOrderPredicate(n.leader, n.zxid,
                                proposedLeader, proposedZxid)) {
                            LOG.info("Updating proposal");
                            updateProposal(n.leader, n.zxid);
                            sendNotifications();
                        }
                    
                        if(LOG.isDebugEnabled()){
                            LOG.debug("Adding vote: From = " + n.sid +
                                    ", Proposed leader = " + n.leader +
                                    ", Porposed zxid = " + n.zxid +
                                    ", Proposed epoch = " + n.epoch);
                        }
                        
                        /*
                         * Only proceed if the vote comes from a replica in the 
                         * voting view.
                         */
                        if(self.getVotingView().containsKey(n.sid)){
                            recvset.put(n.sid, new Vote(n.leader, n.zxid, n.epoch));
    
                            //If have received from all nodes, then terminate
                            if ((self.getVotingView().size() == recvset.size()) &&
                                    (self.getQuorumVerifier().getWeight(proposedLeader) != 0)){
                                self.setPeerState((proposedLeader == self.getId()) ? 
                                        ServerState.LEADING: learningState());
                                leaveInstance();
                                return new Vote(proposedLeader, proposedZxid);
                            
                            } else if (termPredicate(recvset,
                                    new Vote(proposedLeader, proposedZxid,
                                            logicalclock))) {

                                // Verify if there is any change in the proposed leader
                                while((n = recvqueue.poll(finalizeWait,
                                        TimeUnit.MILLISECONDS)) != null){
                                    if(totalOrderPredicate(n.leader, n.zxid,
                                            proposedLeader, proposedZxid)){
                                        recvqueue.put(n);
                                        break;
                                    }
                                }
                        
                                /*
                                 * This predicate is true once we don't read any new
                                 * relevant message from the reception queue
                                 */
                                if (n == null) {
                                    self.setPeerState((proposedLeader == self.getId()) ? 
                                            ServerState.LEADING: learningState());
                                    if(LOG.isDebugEnabled()){
                                        LOG.debug("About to leave FLE instance: Leader= "
                                            + proposedLeader + ", Zxid = " + 
                                            proposedZxid + ", My id = " + self.getId()
                                            + ", My state = " + self.getPeerState());
                                    }
                                    
                                    leaveInstance();
                                    return new Vote(proposedLeader,
                                            proposedZxid);
                                }
                            }
                        }
                        break;
                    case OBSERVING:
                        LOG.debug("Notification from observer: " + n.sid);
                        break;
                    default:
                        /*
                         * There is at most one leader for each epoch, so if a
                         * peer claims to be the leader for an epoch, then that
                         * peer must be the leader (no* arbitrary failures
                         * assumed). Now, if there is no quorum supporting 
                         * this leader, then processes will naturally move
                         * to a new epoch.
                         */
                        if(n.epoch == logicalclock){
                            recvset.put(n.sid, new Vote(n.leader, n.zxid, n.epoch));
                            if((n.state == ServerState.LEADING) ||
                                    (termPredicate(recvset, new Vote(n.leader,
                                            n.zxid, n.epoch, n.state))
                                            && checkLeader(outofelection, n.leader, n.epoch)) ){
                                self.setPeerState((n.leader == self.getId()) ? 
                                        ServerState.LEADING: learningState());
                       
                                leaveInstance();
                                return new Vote(n.leader, n.zxid);
                            } 
                        }
                    
                        LOG.info("Notification: " + n.leader + ", " + n.zxid + 
                                ", " + n.epoch + ", " + self.getId() + ", " + 
                                self.getPeerState() + ", " + n.state + ", "
                                + n.sid);
           
                        outofelection.put(n.sid, new Vote(n.leader, n.zxid,
                                n.epoch, n.state));
    
                        if (termPredicate(outofelection, new Vote(n.leader,
                                n.zxid, n.epoch, n.state))
                                && checkLeader(outofelection, n.leader, n.epoch)) {
                            synchronized(this){
                                logicalclock = n.epoch;
                                self.setPeerState((n.leader == self.getId()) ? 
                                        ServerState.LEADING: learningState());
                            }
                            leaveInstance();
                            return new Vote(n.leader, n.zxid);
                        }
                        
                        break;
                    }
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
