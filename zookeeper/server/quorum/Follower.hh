/*
 * Follower.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef Follower_HH_
#define Follower_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./Learner.hh"
#include "./QuorumServer.hh"
#include "../util/ZxidUtils.hh"
#include "../util/SerializeUtils.hh"
#include "../../txn/TxnHeader.hh"
#include "../../common/TimeUtils.hh"
#include "../../../jute/inc/ERecord.hh"

namespace efc {
namespace ezk {

/**
 * This class has the control logic for the Follower.
 */

class FollowerZooKeeperServer;

class Follower : public Learner{
public:
	llong lastQueued;

    // This is the same object as this.zk, but we cache the downcast op
    sp<FollowerZooKeeperServer> fzk;
    
    Follower(QuorumPeer* self, sp<FollowerZooKeeperServer> zk);

    virtual EString toString() {
    	EString sb;
        sb.append("Follower ").append(sock->toString());
        sb.append(" lastQueuedZxid:").append(lastQueued);
        sb.append(" pendingRevalidationCount:")
            .append(pendingRevalidations.size());
        return sb;
    }

    /**
     * the main method called by the follower to follow the leader
     *
     * @throws InterruptedException
     */
    void followLeader() THROWS(EInterruptedException);

    /**
     * Examine the packet received in qp and dispatch based on its contents.
     * @param qp
     * @throws IOException
     */
    void processPacket(sp<QuorumPacket> qp) THROWS(EIOException);

    /**
     * The zxid of the last operation seen
     * @return zxid
     */
    llong getZxid();
    
    /**
     * The zxid of the last operation queued
     * @return zxid
     */
    llong getLastQueued() {
        return lastQueued;
    }

    virtual void shutdown() {
        LOG->info("shutdown called (shutdown Follower)");
        Learner::shutdown();
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Follower_HH_ */
