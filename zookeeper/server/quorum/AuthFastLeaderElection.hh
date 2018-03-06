/*
 * AuthFastLeaderElection.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef AuthFastLeaderElection_HH_
#define AuthFastLeaderElection_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./Vote.hh"
#include "./Election.hh"
#include "./QuorumServer.hh"
#include "./ServerState.hh"
#include "../ZooKeeperThread.hh"
#include "../../common/TimeUtils.hh"

namespace efc {
namespace ezk {

/**
 * @deprecated This class has been deprecated as of release 3.4.0. 
 */

class Messenger;
class QuorumPeer;

class AuthFastLeaderElection : virtual public Election {
public:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(AuthFastLeaderElection.class);

    /* Sequence numbers for messages */
    static int sequencer;// = 0;
    static int maxTag;// = 0;

    /*
     * Determine how much time a process has to wait once it believes that it
     * has reached the end of leader election.
     */
    static int finalizeWait;// = 100;

    /*
     * Challenge counter to avoid replay attacks
     */
    static int challengeCounter;// = 0;

    /*
     * Flag to determine whether to authenticate or not
     */
    boolean authEnabled;// = false;

public:

    class Notification : public EObject {
    public:
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
        llong epoch;

        /*
         * current state of sender
         */
        ServerState state;

        /*
         * Address of the sender
         */
        sp<EInetSocketAddress> addr;

        Notification() : leader(0), zxid(0), epoch(0), state(LOOKING) {
        }
    };

    /*
     * Messages to send, both Notifications and Acks
     */
    class ToSend : public EObject {
    public:
        enum mType {
            crequest, challenge, notification, ack
        };

        ToSend(mType type, llong tag, llong leader, llong zxid, llong epoch,
                ServerState state, sp<EInetSocketAddress> addr) {

            switch (type) {
            case crequest:
                this->type = 0;
                this->tag = tag;
                this->leader = leader;
                this->zxid = zxid;
                this->epoch = epoch;
                this->state = state;
                this->addr = addr;

                break;
            case challenge:
            	this->type = 1;
            	this->tag = tag;
            	this->leader = leader;
            	this->zxid = zxid;
            	this->epoch = epoch;
            	this->state = state;
            	this->addr = addr;

                break;
            case notification:
            	this->type = 2;
            	this->leader = leader;
            	this->zxid = zxid;
            	this->epoch = epoch;
            	this->state = ServerState::LOOKING;
            	this->tag = tag;
            	this->addr = addr;

                break;
            case ack:
            	this->type = 3;
            	this->tag = tag;
            	this->leader = leader;
            	this->zxid = zxid;
            	this->epoch = epoch;
            	this->state = state;
            	this->addr = addr;

                break;
            default:
            	ES_ASSERT(false);
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
        llong leader;

        /*
         * id contains the tag for acks, and zxid for notifications
         */
        llong zxid;

        /*
         * Epoch
         */
        llong epoch;

        /*
         * Current state;
         */
        ServerState state;

        /*
         * Message tag
         */
        llong tag;

        sp<EInetSocketAddress> addr;
    };

    sp<ELinkedBlockingQueue<ToSend> > sendqueue;

    sp<ELinkedBlockingQueue<Notification> > recvqueue;

    QuorumPeer* self;
    int port;
    volatile llong logicalclock; /* Election instance */
    sp<EDatagramSocket> mySocket;
    llong proposedLeader;
    llong proposedZxid;

    Messenger* messenger;

public:
    virtual ~AuthFastLeaderElection();

    AuthFastLeaderElection(QuorumPeer* self, boolean auth);

    AuthFastLeaderElection(QuorumPeer* self);

    /**
     * There is nothing to shutdown in this implementation of
     * leader election, so we simply have an empty method.
     */
    virtual void shutdown(){}
    
    /**
     * Invoked in QuorumPeer to find or elect a new leader.
     * 
     * @throws InterruptedException
     */
    virtual sp<Vote> lookForLeader() THROWS(EInterruptedException);

private:

    void starter(QuorumPeer* self);

    void leaveInstance() {
		logicalclock++;
	}

    void sendNotifications();

    boolean totalOrderPredicate(llong id, llong zxid) {
		if ((zxid > proposedZxid)
				|| ((zxid == proposedZxid) && (id > proposedLeader)))
			return true;
		else
			return false;

	}

    boolean termPredicate(EHashMap<sp<EInetSocketAddress>, sp<Vote> >& votes,
			llong l, llong zxid);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* AuthFastLeaderElection_HH_ */
