/*
 * ObserverRequestProcessor.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef ObserverRequestProcessor_HH_
#define ObserverRequestProcessor_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./ObserverZooKeeperServer.hh"
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

class ObserverRequestProcessor : public ZooKeeperCriticalThread, virtual public  RequestProcessor {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(ObserverRequestProcessor.class);

public:
    sp<ObserverZooKeeperServer> zks;

    sp<RequestProcessor> nextProcessor;

    // We keep a queue of requests. As requests get submitted they are 
    // stored here. The queue is drained in the run() method. 
    ELinkedBlockingQueue<Request> queuedRequests;// = new LinkedBlockingQueue<Request>();

    boolean finished;// = false;

    /**
     * Constructor - takes an ObserverZooKeeperServer to associate with
     * and the next processor to pass requests to after we're finished. 
     * @param zks
     * @param nextProcessor
     */
    ObserverRequestProcessor(sp<ObserverZooKeeperServer> zks,
    		sp<RequestProcessor> nextProcessor) :
		ZooKeeperCriticalThread(EString("ObserverRequestProcessor:") + zks->getServerId(), zks
                ->getZooKeeperServerListener()), finished(false) {
        this->zks = zks;
        this->nextProcessor = nextProcessor;
    }

    virtual void run();

    /**
     * Simply queue the request, which will be processed in FIFO order. 
     */
    virtual void processRequest(sp<Request> request) {
        if (!finished) {
            queuedRequests.add(request);
        }
    }

    /**
     * Shutdown the processor.
     */
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
#endif /* ObserverRequestProcessor_HH_ */
