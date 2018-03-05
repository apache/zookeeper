/*
 * FollowerRequestProcessor.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef FollowerRequestProcessor_HH_
#define FollowerRequestProcessor_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./FollowerZooKeeperServer.hh"
#include "../Request.hh"
#include "../ZooTrace.hh"
#include "../RequestProcessor.hh"
#include "../ZooKeeperCriticalThread.hh"
#include "../../ZooDefs.hh"

namespace efc {
namespace ezk {

/**
 * This RequestProcessor forwards any requests that modify the state of the
 * system to the Leader.
 */
class FollowerRequestProcessor : public ZooKeeperCriticalThread, virtual public RequestProcessor {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(FollowerRequestProcessor.class);

public:
    sp<FollowerZooKeeperServer> zks;

    sp<RequestProcessor> nextProcessor;

    ELinkedBlockingQueue<Request> queuedRequests;// = new LinkedBlockingQueue<Request>();

    boolean finished;// = false;

    FollowerRequestProcessor(sp<FollowerZooKeeperServer> zks,
            sp<RequestProcessor> nextProcessor) :
    	ZooKeeperCriticalThread(EString("FollowerRequestProcessor:") + zks->getServerId(), zks
                ->getZooKeeperServerListener()), finished(false) {
        this->zks = zks;
        this->nextProcessor = nextProcessor;
    }

    virtual void run();

    virtual void processRequest(sp<Request> request) {
        if (!finished) {
            queuedRequests.add(request);
        }
    }

    virtual void shutdown() {
        LOG->info("Shutting down");
        finished = true;
        queuedRequests.clear();
        queuedRequests.add(Request::requestOfDeath);
        nextProcessor->shutdown();
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* FollowerRequestProcessor_HH_ */
