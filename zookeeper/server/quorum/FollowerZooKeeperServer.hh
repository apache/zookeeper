/*
 * FollowerZooKeeperServer.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef FollowerZooKeeperServer_HH_
#define FollowerZooKeeperServer_HH_

#include "./Follower.hh"
#include "./LearnerZooKeeperServer.hh"
#include "./CommitProcessor.hh"
#include "../SyncRequestProcessor.hh"

namespace efc {
namespace ezk {

/**
 * Just like the standard ZooKeeperServer. We just replace the request
 * processors: FollowerRequestProcessor -> CommitProcessor ->
 * FinalRequestProcessor
 * 
 * A SyncRequestProcessor is also spawned off to log proposals from the leader.
 */
class FollowerZooKeeperServer : public LearnerZooKeeperServer {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(FollowerZooKeeperServer.class);

public:
    sp<CommitProcessor> commitProcessor;

    sp<SyncRequestProcessor> syncProcessor;

    /*
     * Pending sync requests
     */
    EMutexLinkedQueue<Request> pendingSyncs;

    ELinkedBlockingQueue<Request> pendingTxns;// = new LinkedBlockingQueue<Request>();

    /**
     * @param port
     * @param dataDir
     * @throws IOException
     */
    FollowerZooKeeperServer(sp<FileTxnSnapLog> logFactory,QuorumPeer* self,
            sp<ZKDatabase> zkDb) THROWS(EIOException);

    sp<Follower> getFollower();

    virtual void setupRequestProcessors();

    void logRequest(sp<TxnHeader> hdr, sp<ERecord> txn);

    /**
     * When a COMMIT message is received, eventually this method is called, 
     * which matches up the zxid from the COMMIT with (hopefully) the head of
     * the pendingTxns queue and hands it to the commitProcessor to commit.
     * @param zxid - must correspond to the head of pendingTxns if it exists
     */
    void commit(llong zxid);
    
    synchronized
    void sync();
             
    virtual int getGlobalOutstandingLimit();
    
    virtual void shutdown();
    
    virtual EString getState() {
        return "follower";
    }

    virtual sp<Learner> getLearner();
};

} /* namespace ezk */
} /* namespace efc */
#endif /* FollowerZooKeeperServer_HH_ */
