/*
 * Observer.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef Observer_HH_
#define Observer_HH_

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
 * Observers are peers that do not take part in the atomic broadcast protocol.
 * Instead, they are informed of successful proposals by the Leader. Observers
 * therefore naturally act as a relay point for publishing the proposal stream
 * and can relieve Followers of some of the connection load. Observers may
 * submit proposals, but do not vote in their acceptance. 
 *
 * See ZOOKEEPER-368 for a discussion of this feature. 
 */

class ObserverZooKeeperServer;

class Observer : public Learner {
public:
    Observer(QuorumPeer* self, sp<ObserverZooKeeperServer> observerZooKeeperServer);

    virtual EString toString() {
    	EString sb;// = new StringBuilder();
        sb.append("Observer ").append(sock->toString());
        sb.append(" pendingRevalidationCount:")
            .append(pendingRevalidations.size());
        return sb;
    }
    
    /**
     * the main method called by the observer to observe the leader
     *
     * @throws InterruptedException
     */
    void observeLeader() THROWS(EInterruptedException);
    
    /**
     * Controls the response of an observer to the receipt of a quorumpacket
     * @param qp
     * @throws IOException
     */
    void processPacket(sp<QuorumPacket> qp) THROWS(EIOException);

    /**
     * Shutdown the Observer.
     */
    virtual void shutdown();
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Observer_HH_ */
