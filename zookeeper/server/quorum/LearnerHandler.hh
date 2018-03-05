/*
 * LearnerHandler.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef LearnerHandler_HH_
#define LearnerHandler_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./Proposal.hh"
#include "./QuorumServer.hh"
#include "./LeaderZooKeeperServer.hh"
#include "../ZooTrace.hh"
#include "../Request.hh"
#include "../ZooKeeperThread.hh"
#include "../ByteBufferInputStream.hh"
#include "../util/ZxidUtils.hh"
#include "../util/SerializeUtils.hh"
#include "../../ZooDefs.hh"
#include "../../KeeperException.hh"
#include "../../txn/TxnHeader.hh"
#include "../../../jute/inc/ERecord.hh"
#include "../../../jute/inc/EBinaryInputArchive.hh"
#include "../../../jute/inc/EBinaryOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 * There will be an instance of this class created by the Leader for each
 * learner. All communication with a learner is handled by this
 * class.
 */

class Leader;

class LearnerHandler : public ZooKeeperThread, public enable_shared_from_this<LearnerHandler> {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(LearnerHandler.class);

    /**
     * This class controls the time that the Leader has been
     * waiting for acknowledgement of a proposal from this Learner.
     * If the time is above syncLimit, the connection will be closed.
     * It keeps track of only one proposal at a time, when the ACK for
     * that proposal arrives, it switches to the last proposal received
     * or clears the value if there is no pending proposal.
     */
    class SyncLimitCheck : public ESynchronizeable {
    private:
    	LearnerHandler* owner;
    	boolean started;// = false;
    	llong currentZxid;// = 0;
    	llong currentTime;// = 0;
    	llong nextZxid;// = 0;
    	llong nextTime;// = 0;

    public:
    	SyncLimitCheck(LearnerHandler* owner) :
    		owner(owner),
    		started(false),
    		currentZxid(0),
    		currentTime(0),
    		nextZxid(0),
    		nextTime(0) {
    	}

        synchronized
        void start() {
        	SYNCHRONIZED(this) {
        		started = true;
        	}}
        }

        synchronized
        void updateProposal(llong zxid, llong time) {
        	SYNCHRONIZED(this) {
				if (!started) {
					return;
				}
				if (currentTime == 0) {
					currentTime = time;
					currentZxid = zxid;
				} else {
					nextTime = time;
					nextZxid = zxid;
				}
        	}}
        }

        synchronized
        void updateAck(llong zxid) {
        	SYNCHRONIZED(this) {
				 if (currentZxid == zxid) {
					 currentTime = nextTime;
					 currentZxid = nextZxid;
					 nextTime = 0;
					 nextZxid = 0;
				 } else if (nextZxid == zxid) {
					 LearnerHandler::LOG->warn(EString("ACK for ") + zxid + " received before ACK for " + currentZxid + "!!!!");
					 nextTime = 0;
					 nextZxid = 0;
				 }
        	}}
        }

        synchronized boolean check(llong time);
    };

    SyncLimitCheck syncLimitCheck;// = new SyncLimitCheck();

    sp<EBinaryInputArchive> ia;

    sp<EBinaryOutputArchive> oa;

    sp<EBufferedInputStream> bufferedInput;
    sp<EBufferedOutputStream> bufferedOutput;

	QuorumServer::LearnerType learnerType;// = QuorumPeer::LearnerType::PARTICIPANT;

	/**
     * This method will use the thread to send packets added to the
     * queuedPackets list
     *
     * @throws InterruptedException
     */
    void sendPackets() THROWS(EInterruptedException);

public:
    sp<ESocket> sock;

    sp<Leader> leader;

    /** Deadline for receiving the next ack. If we are bootstrapping then
     * it's based on the initLimit, if we are done bootstrapping it's based
     * on the syncLimit. Once the deadline is past this learner should
     * be considered no longer "sync'd" with the leader. */
    volatile llong tickOfNextAckDeadline;

    /**
     * ZooKeeper server identifier of this learner
     */
    llong sid;// = 0;

    int version;// = 0x1;

    /**
     * The packets to be sent to the learner
     */
    ELinkedBlockingQueue<QuorumPacket> queuedPackets;// = new LinkedBlockingQueue<QuorumPacket>();

    /**
	 * If this packet is queued, the sender thread will exit
	 */
	sp<QuorumPacket> proposalOfDeath;// = new QuorumPacket();

    virtual ~LearnerHandler();

    LearnerHandler(sp<ESocket> sock, sp<EBufferedInputStream> bufferedInput,
                   sp<Leader> leader) THROWS(EIOException);

    virtual EString toString() {
        EString sb;// = new StringBuilder();
        sb.append("LearnerHandler ").append(sock->toString());
        sb.append(" tickOfNextAckDeadline:").append(tickOfNextAckDeadline);
        sb.append(" synced?:").append(synced());
        sb.append(" queuedPacketLength:").append(queuedPackets.size());
        return sb;
    }

    QuorumServer::LearnerType getLearnerType() {
        return learnerType;
    }

    sp<ESocket> getSocket() {
        return sock;
    }

    llong getSid(){
        return sid;
    }

    int getVersion() {
    	return version;
    }

    static EString packetToString(sp<QuorumPacket> p);

    /**
     * This thread will receive packets from the peer and process them and
     * also listen to new connections from new peers.
     */
    virtual void run();

    virtual void shutdown();

    /**
     * ping calls from the leader to the peers
     */
    void ping();

    void queuePacket(sp<QuorumPacket> p) {
        queuedPackets.add(p);
    }

    boolean synced();
};

} /* namespace ezk */
} /* namespace efc */
#endif /* LearnerHandler_HH_ */
