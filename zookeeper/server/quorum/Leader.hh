/*
 * Leader.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef Leader_HH_
#define Leader_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./PacketType.hh"
#include "./Proposal.hh"
#include "./StateSummary.hh"
#include "./LearnerHandler.hh"
#include "./LearnerSyncRequest.hh"
#include "./QuorumVerifier.hh"
#include "./LeaderZooKeeperServer.hh"
#include "../FinalRequestProcessor.hh"
#include "../Request.hh"
#include "../RequestProcessor.hh"
#include "../ZooKeeperThread.hh"
#include "../RequestProcessor.hh"
#include "../RequestProcessor.hh"
#include "../util/ZxidUtils.hh"
#include "../../../jute/inc/ERecord.hh"
#include "../../../jute/inc/EBinaryInputArchive.hh"
#include "../../../jute/inc/EBinaryOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 * This class has the control logic for the Leader.
 */

class QuorumPeer;

class Leader : public ESynchronizeable, public enable_shared_from_this<Leader> {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(Leader.class);
    static boolean nodelay;// = System.getProperty("leader.nodelay", "true").equals("true");

    boolean quorumFormed;// = false;


    // list of all the followers
    EHashSet<sp<LearnerHandler> > learners;// = new HashSet<LearnerHandler>();
    EReentrantLock learnersLock;

    // list of followers that are ready to follow (i.e synced with the leader)
	EHashSet<sp<LearnerHandler> > forwardingFollowers;// = new HashSet<LearnerHandler>();
	EReentrantLock forwardingFollowersLock;

	EHashSet<sp<LearnerHandler> > observingLearners;// = new HashSet<LearnerHandler>();
	EReentrantLock observingLearnersLock;

	// Pending sync requests. Must access under 'this' lock.
	EHashMap<llong, EList<sp<LearnerSyncRequest> >*> pendingSyncs;// = new HashMap<Long,List<LearnerSyncRequest>>();

	EHashSet<llong> connectingFollowers;// = new HashSet<Long>();
	EReentrantLock connectingFollowersLock;
	sp<ECondition> connectingFollowersCond;

	EHashSet<llong> electingFollowers;// = new HashSet<Long>();
	EReentrantLock electingFollowersLock;
	sp<ECondition> electingFollowersCond;
	boolean electionFinished;// = false;

	void addForwardingFollower(sp<LearnerHandler> lh);

	void addObserverLearnerHandler(sp<LearnerHandler> lh);

	/**
	 * Return a list of sid in set as string
	 */
	EString getSidSetString(ESet<llong>* sidSet);

	/**
	 * Start up Leader ZooKeeper server and initialize zxid to the new epoch
	 */
	synchronized
	void startZkServer();

    boolean isRunning();

    class LearnerCnxAcceptor : public ZooKeeperThread {
    private:
    	sp<Leader> owner;
    	volatile boolean stop;// = false;

    public:
    	LearnerCnxAcceptor(sp<Leader> owner);

		virtual void run();

		virtual void halt();
	};

public:
    DECLARE_STATIC_INITZZ;

    sp<LeaderZooKeeperServer> zk;

    QuorumPeer* self;

    // the follower acceptor thread
    wp<LearnerCnxAcceptor> cnxAcceptor_;
    
    sp<EServerSocket> ss;

    //Follower counter
	EAtomicLLong followerCounter;// = new AtomicLong(-1);

	EConcurrentHashMap<llong, Proposal> outstandingProposals;// = new ConcurrentHashMap<Long, Proposal>();

	sp<EConcurrentLinkedQueue<Proposal> > toBeApplied;// = new ConcurrentLinkedQueue<Proposal>();

	sp<Proposal> newLeaderProposal;// = new Proposal();

	sp<StateSummary> leaderStateSummary;

	llong epoch;// = -1;
	boolean waitingForNewEpoch;// = true;
	volatile boolean readyToStart;// = false;

	llong lastCommitted;// = -1;

	llong lastProposed;

	boolean isShutdown;

public:
	virtual ~Leader();

	Leader(QuorumPeer* self, sp<LeaderZooKeeperServer> zk) THROWS(EIOException);

    /**
     * Returns a copy of the current learner snapshot
     */
    sp<EList<sp<LearnerHandler> > > getLearners();

    /**
     * Returns a copy of the current forwarding follower snapshot
     */
	sp<EList<sp<LearnerHandler> > > getForwardingFollowers();

    /**
     * Returns a copy of the current observer snapshot
     */
    sp<EList<sp<LearnerHandler> > > getObservingLearners();
    
    synchronized
    int getNumPendingSyncs();

    /**
     * Adds peer to the leader.
     * 
     * @param learner
     *                instance of learner handle
     */
    void addLearnerHandler(sp<LearnerHandler> learner);

    /**
     * Remove the learner from the learner list
     * 
     * @param peer
     */
    void removeLearnerHandler(LearnerHandler* peer);

    boolean isLearnerSynced(LearnerHandler* peer);
    
    /**
     * This method is main function that is called to lead
     * 
     * @throws IOException
     * @throws InterruptedException
     */
    void lead() THROWS2(EIOException, EInterruptedException);

    /**
     * Close down all the LearnerHandlers
     */
    void shutdown(EString reason);

    /**
     * Keep a count of acks that are received by the leader for a particular
     * proposal
     * 
     * @param zxid
     *                the zxid of the proposal sent out
     * @param followerAddr
     */
    synchronized
    void processAck(llong sid, llong zxid, EInetSocketAddress* followerAddr);

    /**
     * send a packet to all the followers ready to follow
     * 
     * @param qp
     *                the packet to be sent
     */
    void sendPacket(sp<QuorumPacket> qp);
    
    /**
     * send a packet to all observers     
     */
    void sendObserverPacket(sp<QuorumPacket> qp);

    /**
     * Create a commit packet and send it to all the members of the quorum
     * 
     * @param zxid
     */
    void commit(llong zxid);
    
    /**
     * Create an inform packet and send it to all observers.
     * @param zxid
     * @param proposal
     */
    void inform(sp<Proposal> proposal);
    
    /**
     * Returns the current epoch of the leader.
     * 
     * @return
     */
    llong getEpoch();
    
    /**
     * create a proposal and send it out to all the members
     * 
     * @param request
     * @return the proposal that is queued to send to all the members
     */
    sp<Proposal> propose(sp<Request> request) THROWS(XidRolloverException);
            
    /**
     * Process sync requests
     * 
     * @param r the request
     */
    synchronized
    void processSync(sp<LearnerSyncRequest> r);

    /**
     * Sends a sync message to the appropriate server
     * 
     * @param f
     * @param r
     */
    void sendSync(sp<LearnerSyncRequest> r);
                
    /**
     * lets the leader know that a follower is capable of following and is done
     * syncing
     * 
     * @param handler handler of the follower
     * @return last proposed zxid
     */
    synchronized
    llong startForwarding(sp<LearnerHandler> handler, llong lastSeenZxid);

    llong getEpochToPropose(llong sid, llong lastAcceptedEpoch) THROWS2(EInterruptedException, EIOException);

    void waitForEpochAck(llong id, sp<StateSummary> ss) THROWS2(EIOException, EInterruptedException);

    /**
     * Process NEWLEADER ack of a given sid and wait until the leader receives
     * sufficient acks.
     *
     * @param sid
     * @param learnerType
     * @throws InterruptedException
     */
    void waitForNewLeaderAck(llong sid, llong zxid, QuorumServer::LearnerType learnerType)
            THROWS(EInterruptedException);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Leader_HH_ */
