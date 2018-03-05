/*
 * FastLeaderElection.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef FastLeaderElection_HH_
#define FastLeaderElection_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./Election.hh"
#include "./QuorumCnxManager.hh"
#include "./QuorumServer.hh"
#include "./ServerState.hh"
#include "../ZooKeeperThread.hh"
#include "../util/ZxidUtils.hh"
#include "../../common/TimeUtils.hh"

namespace efc {
namespace ezk {

/**
 * Implementation of leader election using TCP. It uses an object of the class
 * QuorumCnxManager to manage connections. Otherwise, the algorithm is push-based
 * as with the other UDP implementations.
 *
 * There are a few parameters that can be tuned to change its behavior. First,
 * finalizeWait determines the amount of time to wait until deciding upon a leader.
 * This is part of the leader election algorithm.
 */

class QuorumPeer;

class FastLeaderElection : virtual public Election {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(FastLeaderElection.class);

public:
    /**
     * Determine how much time a process has to wait
     * once it believes that it has reached the end of
     * leader election.
     */
    static const int finalizeWait = 200;


    /**
     * Upper bound on the amount of time between two consecutive
     * notification checks. This impacts the amount of time to get
     * the system up again after long partitions. Currently 60 seconds.
     */

    static const int maxNotificationInterval = 60000;

    /**
     * Notifications are messages that let other peers know that
     * a given peer has changed its vote, either because it has
     * joined leader election or because it learned of another
     * peer with higher zxid or same zxid and higher server id
     */

    class Notification : public EObject {
    public:
        /*
         * Format version, introduced in 3.4.6
         */
        
        static const int CURRENTVERSION = 0x1;

        int version;
                
        /*
         * Proposed leader
         */
        llong leader;

        /*
         * zxid of the proposed leader
         */
        llong zxid;

        /*
         * Epoch
         */
        llong electionEpoch;

        /*
         * current state of sender
         */
        ServerState state;

        /*
         * Address of sender
         */
        llong sid;

        /*
         * epoch of the proposed leader
         */
        llong peerEpoch;

        Notification() : version(0), leader(0), zxid(0),
        		electionEpoch(0), state(LOOKING), sid(0), peerEpoch(0) {
        }

        virtual EString toString() {
            return ELLong::toHexString(version) + " (message format version), "
                    + leader + " (n.leader), 0x"
                    + ELLong::toHexString(zxid) + " (n.zxid), 0x"
                    + ELLong::toHexString(electionEpoch) + " (n.round), " + getStateName(state)
                    + " (n.state), " + sid + " (n.sid), 0x"
                    + ELLong::toHexString(peerEpoch) + " (n.peerEpoch) ";
        }
    };
    
    static sp<EIOByteBuffer> buildMsg(int state,
            llong leader,
            llong zxid,
            llong electionEpoch,
            llong epoch) {
        sp<EIOByteBuffer> requestBuffer = EIOByteBuffer::allocate(40);

        /*
         * Building notification packet to send 
         */

        requestBuffer->clear();
        requestBuffer->putInt(state);
        requestBuffer->putLLong(leader);
        requestBuffer->putLLong(zxid);
        requestBuffer->putLLong(electionEpoch);
        requestBuffer->putLLong(epoch);
        requestBuffer->putInt(Notification::CURRENTVERSION);
        
        return requestBuffer;
    }

    /**
     * Messages that a peer wants to send to other peers.
     * These messages can be both Notifications and Acks
     * of reception of notification.
     */
    class ToSend : public EObject {
    public:
        enum mType {crequest, challenge, notification, ack};

        ToSend(mType type,
        		llong leader,
        		llong zxid,
        		llong electionEpoch,
                ServerState state,
                llong sid,
                llong peerEpoch) {

            this->leader = leader;
            this->zxid = zxid;
            this->electionEpoch = electionEpoch;
            this->state = state;
            this->sid = sid;
            this->peerEpoch = peerEpoch;
        }

        /*
         * Proposed leader in the case of notification
         */
        llong leader;

        /*
         * id contains the tag for acks, and zxid for notifications
         */
        llong zxid;

        /*
         * Epoch
         */
        llong electionEpoch;

        /*
         * Current state;
         */
        ServerState state;

        /*
         * Address of recipient
         */
        llong sid;
        
        /*
         * Leader epoch
         */
        llong peerEpoch;
    };

    /**
     * Multi-threaded implementation of message handler. Messenger
     * implements two sub-classes: WorkReceiver and  WorkSender. The
     * functionality of each is obvious from the name. Each of these
     * spawns a new thread.
     */

    class Messenger : public EObject {
    public:
        /**
         * Receives messages from instance of QuorumCnxManager on
         * method run(), and processes such messages.
         */

        class WorkerReceiver : public ZooKeeperThread {
        public:
        	FastLeaderElection* owner;
            volatile boolean stop;
            sp<QuorumCnxManager> manager;

            WorkerReceiver(FastLeaderElection* owner, sp<QuorumCnxManager> manager) :
            	ZooKeeperThread("WorkerReceiver"), owner(owner) {
                this->stop = false;
                this->manager = manager;
            }

            virtual void run();
        };

        /**
         * This worker simply dequeues a message to send and
         * and queues it on the manager's queue.
         */

        class WorkerSender : public ZooKeeperThread {
        public:
        	FastLeaderElection* owner;
            volatile boolean stop;
            sp<QuorumCnxManager> manager;

            WorkerSender(FastLeaderElection* owner, sp<QuorumCnxManager> manager) :
            	ZooKeeperThread("WorkerSender"), owner(owner) {
                this->stop = false;
                this->manager = manager;
            }

            virtual  void run();

            /**
             * Called by run() once there is a new message to send.
             *
             * @param m     message to send
             */
            void process(sp<ToSend> m) {
            	sp<EIOByteBuffer> requestBuffer = buildMsg(m->state,
                                                        m->leader,
                                                        m->zxid,
                                                        m->electionEpoch,
                                                        m->peerEpoch);
                manager->toSend(m->sid, requestBuffer);
            }
        };

        /**
         * Test if both send and receive queues are empty.
         */
        boolean queueEmpty() {
            return (owner->sendqueue.isEmpty() || owner->recvqueue.isEmpty());
        }

    public:

        FastLeaderElection* owner;
        
        sp<WorkerSender> ws;
        sp<WorkerReceiver> wr;

        EThread* wsThread;
        EThread* wrThread;

        /**
         * Constructor of class Messenger.
         *
         * @param manager   Connection manager
         */
        Messenger(FastLeaderElection* owner, sp<QuorumCnxManager> manager);

        /**
         * Stops instances of WorkerSender and WorkerReceiver
         */
        void halt(){
        	this->ws->stop = true;
        	this->wr->stop = true;
        }

    };

    /**
     * Connection manager. Fast leader election uses TCP for
     * communication between peers, and QuorumCnxManager manages
     * such connections.
     */

    sp<QuorumCnxManager> manager;

    QuorumPeer* self;
    sp<Messenger> messenger;
    EAtomicLLong logicalclock;// = new AtomicLong(); /* Election instance */
    llong proposedLeader;
    llong proposedZxid;
    llong proposedEpoch;

    ELinkedBlockingQueue<ToSend> sendqueue;
    ELinkedBlockingQueue<Notification> recvqueue;

    volatile boolean stop;

public:
    
    virtual ~FastLeaderElection();

    /**
     * Constructor of FastLeaderElection. It takes two parameters, one
     * is the QuorumPeer object that instantiated this object, and the other
     * is the connection manager. Such an object should be created only once
     * by each peer during an instance of the ZooKeeper service.
     *
     * @param self  QuorumPeer that created this object
     * @param manager   Connection manager
     */
    FastLeaderElection(QuorumPeer* self, sp<QuorumCnxManager> manager);

    /**
     * Returns the current vlue of the logical clock counter
     */
    llong getLogicalClock(){
        return logicalclock.get();
    }

    sp<QuorumCnxManager> getCnxManager(){
        return manager;
    }

    virtual void shutdown();

    /**
     * Check if a pair (server id, zxid) succeeds our
     * current vote.
     *
     * @param id    Server identifier
     * @param zxid  Last zxid observed by the issuer of this vote
     */
    boolean totalOrderPredicate(llong newId, llong newZxid, llong newEpoch, llong curId, llong curZxid, llong curEpoch);

    /**
     * Termination predicate. Given a set of votes, determines if
     * have sufficient to declare the end of the election round.
     *
     *  @param votes    Set of votes
     *  @param l        Identifier of the vote received last
     *  @param zxid     zxid of the the vote received last
     */
    boolean termPredicate(
            EHashMap<llong, sp<Vote> >& votes,
            sp<Vote> vote);

    /**
     * In the case there is a leader elected, and a quorum supporting
     * this leader, we have to check if the leader has voted and acked
     * that it is leading. We need this check to avoid that peers keep
     * electing over and over a peer that has crashed and it is no
     * longer leading.
     *
     * @param votes set of votes
     * @param   leader  leader id
     * @param   electionEpoch   epoch id
     */
    boolean checkLeader(
    		EHashMap<llong, sp<Vote> >& votes,
            llong leader,
            llong electionEpoch);
    
    /**
     * This predicate checks that a leader has been elected. It doesn't
     * make a lot of sense without context (check lookForLeader) and it
     * has been separated for testing purposes.
     * 
     * @param recv  map of received votes 
     * @param ooe   map containing out of election votes (LEADING or FOLLOWING)
     * @param n     Notification
     * @return          
     */
	boolean ooePredicate(EHashMap<llong, sp<Vote> >& recv,
			EHashMap<llong, sp<Vote> >& ooe, sp<Notification> n);

    synchronized
    void updateProposal(llong leader, llong zxid, llong epoch);

    synchronized
    sp<Vote> getVote();

    /**
     * Starts a new round of leader election. Whenever our QuorumPeer
     * changes its state to LOOKING, this method is invoked, and it
     * sends notifications to all other peers.
     */
    sp<Vote> lookForLeader() THROWS(EInterruptedException);

private:

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
    void starter(QuorumPeer* self, sp<QuorumCnxManager> manager);

    void leaveInstance(sp<Vote> v);

    /**
     * Send notifications to all peers upon a change in our vote
     */
    void sendNotifications();


    void printNotification(sp<Notification> n);

    /**
     * A learning state can be either FOLLOWING or OBSERVING.
     * This method simply decides which one depending on the
     * role of the server.
     *
     * @return ServerState
     */
    ServerState learningState();

    /**
     * Returns the initial vote value of server identifier.
     *
     * @return long
     */
    llong getInitId();

    /**
     * Returns initial last logged zxid.
     *
     * @return long
     */
    llong getInitLastLoggedZxid();

    /**
     * Returns the initial vote value of the peer epoch.
     *
     * @return long
     */
    llong getPeerEpoch();
};

} /* namespace ezk */
} /* namespace efc */
#endif /* FastLeaderElection_HH_ */
