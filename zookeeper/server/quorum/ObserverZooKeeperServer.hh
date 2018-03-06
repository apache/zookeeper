/*
 * ObserverZooKeeperServer.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef ObserverZooKeeperServer_HH_
#define ObserverZooKeeperServer_HH_

#include "./Observer.hh"
#include "./LearnerZooKeeperServer.hh"
#include "./CommitProcessor.hh"
#include "../SyncRequestProcessor.hh"

namespace efc {
namespace ezk {

/**
 * A ZooKeeperServer for the Observer node type. Not much is different, but
 * we anticipate specializing the request processors in the future. 
 *
 */

class ObserverZooKeeperServer : public LearnerZooKeeperServer {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(ObserverZooKeeperServer.class);
    
    /**
     * Enable since request processor for writing txnlog to disk and
     * take periodic snapshot. Default is ON.
     */
    
    boolean syncRequestProcessorEnabled;// = this.self.getSyncEnabled();
    
    /*
     * Request processors
     */
    sp<CommitProcessor> commitProcessor;
    sp<SyncRequestProcessor> syncProcessor;

public:
    
    /*
     * Pending sync requests
     */
    EMutexLinkedQueue<Request> pendingSyncs;// = new ConcurrentLinkedQueue<Request>();
        
    ObserverZooKeeperServer(sp<FileTxnSnapLog> logFactory, QuorumPeer* self,
            sp<ZKDatabase> zkDb) THROWS(EIOException);
    
    sp<Observer> getObserver();
    
    virtual sp<Learner> getLearner();
    
    /**
     * Unlike a Follower, which sees a full request only during the PROPOSAL
     * phase, Observers get all the data required with the INFORM packet. 
     * This method commits a request that has been unpacked by from an INFORM
     * received from the Leader. 
     *      
     * @param request
     */
    void commitRequest(sp<Request> request);
    
    /**
     * Set up the request processors for an Observer:
     * firstProcesor->commitProcessor->finalProcessor
     */
    virtual void setupRequestProcessors();

    /*
     * Process a sync request
     */
    synchronized
    void sync();
    
	virtual EString getState() {
        return "observer";
    };    

	synchronized
	virtual void shutdown();
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ObserverZooKeeperServer_HH_ */
