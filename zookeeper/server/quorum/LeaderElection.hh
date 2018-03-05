/*
 * LeaderElection.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef LeaderElection_HH_
#define LeaderElection_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./Vote.hh"
#include "./Election.hh"
#include "./QuorumServer.hh"
#include "./ServerState.hh"

namespace efc {
namespace ezk {

/**
 * @deprecated This class has been deprecated as of release 3.4.0. 
 */

class QuorumPeer;

class LeaderElection : virtual public Election  {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(LeaderElection.class);

public:
	static ERandom epochGen;// = new Random();

    QuorumPeer* self;

    struct ElectionResult : public EObject {
        sp<Vote> vote;

        int count;

        sp<Vote> winner;

        int winningCount;

        int numValidVotes;

        ElectionResult() : count(0), winningCount(0), numValidVotes(0) {
        }
    };

    LeaderElection(QuorumPeer* self);

    sp<ElectionResult> countVotes(EHashMap<sp<EInetSocketAddress>, sp<Vote> >& votes, EHashSet<llong>& heardFrom);

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
    sp<Vote> lookForLeader() THROWS(EInterruptedException);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* LeaderElection_HH_ */
