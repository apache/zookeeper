/*
 * ProposalRequestProcessor.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ProposalRequestProcessor_HH_
#define ProposalRequestProcessor_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./AckRequestProcessor.hh"
#include "./LeaderZooKeeperServer.hh"
#include "../RequestProcessor.hh"
#include "../Request.hh"
#include "../SyncRequestProcessor.hh"

namespace efc {
namespace ezk {

/**
 * This RequestProcessor simply forwards requests to an AckRequestProcessor and
 * SyncRequestProcessor.
 */

class ProposalRequestProcessor : public RequestProcessor {
private:
	static sp<ELogger> LOG; //= LoggerFactory.getLogger(ProposalRequestProcessor.class);

    sp<LeaderZooKeeperServer> zks;
    
    sp<RequestProcessor> nextProcessor;

    sp<SyncRequestProcessor> syncProcessor;

public:
    ProposalRequestProcessor(sp<LeaderZooKeeperServer> zks,
            sp<RequestProcessor> nextProcessor) {
        this->zks = zks;
        this->nextProcessor = nextProcessor;
        sp<AckRequestProcessor> ackProcessor = new AckRequestProcessor(zks->getLeader());
        syncProcessor = new SyncRequestProcessor(zks, ackProcessor);
    }
    
    /**
     * initialize this processor
     */
    void initialize() {
    	EThread::setDaemon(syncProcessor, true); //!!!
        syncProcessor->start();
    }
    
    virtual void processRequest(sp<Request> request) THROWS(RequestProcessorException);

    virtual void shutdown() {
        LOG->info("Shutting down");
        nextProcessor->shutdown();
        syncProcessor->shutdown();
    }

};

} /* namespace ezk */
} /* namespace efc */
#endif /* ProposalRequestProcessor_HH_ */
