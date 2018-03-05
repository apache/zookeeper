/*
 * LeaderZooKeeperServer.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef LeaderZooKeeperServer_HH_
#define LeaderZooKeeperServer_HH_

#include "./CommitProcessor.hh"
#include "./QuorumZooKeeperServer.hh"
#include "../ZKDatabase.hh"
#include "../FinalRequestProcessor.hh"
#include "../PrepRequestProcessor.hh"
#include "../persistence/FileTxnSnapLog.hh"

namespace efc {
namespace ezk {

/**
 * 
 * Just like the standard ZooKeeperServer. We just replace the request
 * processors: PrepRequestProcessor -> ProposalRequestProcessor ->
 * CommitProcessor -> Leader.ToBeAppliedRequestProcessor ->
 * FinalRequestProcessor
 */

class Leader;
class QuorumPeer;

class LeaderZooKeeperServer : public QuorumZooKeeperServer {
public:
    sp<CommitProcessor> commitProcessor;

    /**
     * @param port
     * @param dataDir
     * @throws IOException
     */
    LeaderZooKeeperServer(sp<FileTxnSnapLog> logFactory, QuorumPeer* self,
            sp<ZKDatabase> zkDb) THROWS(EIOException);

    sp<Leader> getLeader();
    
    virtual void setupRequestProcessors();

    virtual int getGlobalOutstandingLimit();
    
    virtual void createSessionTracker();
    
    virtual void startSessionTracker();

    boolean touch(llong sess, int to) {
        return sessionTracker->touchSession(sess, to);
    }
    
    virtual EString getState() {
        return "leader";
    }

    /**
     * Returns the id of the associated QuorumPeer, which will do for a unique
     * id of this server. 
     */
    virtual llong getServerId();
    
    virtual void revalidateSession(sp<ServerCnxn> cnxn, llong sessionId,
        int sessionTimeout) THROWS(EIOException);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* LeaderZooKeeperServer_HH_ */
