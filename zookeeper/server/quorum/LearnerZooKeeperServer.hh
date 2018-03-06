/*
 * LearnerZooKeeperServer.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef LearnerZooKeeperServer_HH_
#define LearnerZooKeeperServer_HH_

#include "./QuorumZooKeeperServer.hh"
#include "./LearnerSessionTracker.hh"
#include "../ZKDatabase.hh"
#include "../ServerCnxn.hh"
#include "../persistence/FileTxnSnapLog.hh"

namespace efc {
namespace ezk {

/**
 * Parent class for all ZooKeeperServers for Learners 
 */

class Learner;
class QuorumPeer;

abstract class LearnerZooKeeperServer : public QuorumZooKeeperServer {
public:
	LearnerZooKeeperServer(sp<FileTxnSnapLog> logFactory, int tickTime,
            int minSessionTimeout, int maxSessionTimeout,
            sp<ZKDatabase> zkDb, QuorumPeer* self) THROWS(EIOException) :
            QuorumZooKeeperServer(logFactory, tickTime, minSessionTimeout, maxSessionTimeout, zkDb, self)
    {
		//
    }

    /**
     * Abstract method to return the learner associated with this server.
     * Since the Learner may change under our feet (when QuorumPeer reassigns
     * it) we can't simply take a reference here. Instead, we need the 
     * subclasses to implement this.     
     */
    virtual sp<Learner> getLearner() = 0;
    
    /**
     * Returns the current state of the session tracker. This is only currently
     * used by a Learner to build a ping response packet.
     * 
     */
    sp<EHashMap<llong, EInteger*> > getTouchSnapshot() {
        if (sessionTracker != null) {
            return (dynamic_pointer_cast<LearnerSessionTracker>(sessionTracker))->snapshot();
        }
        return new EHashMap<llong, EInteger*>();
    }
    
    /**
     * Returns the id of the associated QuorumPeer, which will do for a unique
     * id of this server. 
     */
    virtual llong getServerId();
    
    virtual void createSessionTracker();
    
    virtual void startSessionTracker() {}
    
    virtual void revalidateSession(sp<ServerCnxn> cnxn, llong sessionId,
            int sessionTimeout) THROWS(EIOException);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* LearnerZooKeeperServer_HH_ */
