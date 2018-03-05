/*
 * ReadOnlyRequestProcessor.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ReadOnlyRequestProcessor_HH_
#define ReadOnlyRequestProcessor_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "../Request.hh"
#include "../RequestProcessor.hh"
#include "../ZooKeeperCriticalThread.hh"
#include "../ZooKeeperServer.hh"
#include "../ZooTrace.hh"
#include "../../ZooDefs.hh"
#include "../../KeeperException.hh"

namespace efc {
namespace ezk {

/**
 * This processor is at the beginning of the ReadOnlyZooKeeperServer's
 * processors chain. All it does is, it passes read-only operations (e.g.
 * OpCode.getData, OpCode.exists) through to the next processor, but drops
 * state-changing operations (e.g. OpCode.create, OpCode.setData).
 */
class ReadOnlyRequestProcessor : public ZooKeeperCriticalThread, virtual public RequestProcessor {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(ReadOnlyRequestProcessor.class);

    ELinkedBlockingQueue<Request> queuedRequests;// = new LinkedBlockingQueue<Request>();

    boolean finished;// = false;

    sp<RequestProcessor> nextProcessor;

    sp<ZooKeeperServer> zks;

public:
    ReadOnlyRequestProcessor(sp<ZooKeeperServer> zks,
            sp<RequestProcessor> nextProcessor) :
            	ZooKeeperCriticalThread(EString("ReadOnlyRequestProcessor:") + zks->getServerId(), zks
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
        finished = true;
        queuedRequests.clear();
        queuedRequests.add(Request::requestOfDeath);
        nextProcessor->shutdown();
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ReadOnlyRequestProcessor_HH_ */
