/*
 * ToBeAppliedRequestProcessor.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ToBeAppliedRequestProcessor_HH_
#define ToBeAppliedRequestProcessor_HH_

#include "Efc.hh"

#include "../RequestProcessor.hh"

namespace efc {
namespace ezk {

//@see: Leader.java#ToBeAppliedRequestProcessor

class ToBeAppliedRequestProcessor : public RequestProcessor {
public:
	sp<RequestProcessor> next;

    sp<EConcurrentLinkedQueue<Proposal> > toBeApplied;

    /**
     * This request processor simply maintains the toBeApplied list. For
     * this to work next must be a FinalRequestProcessor and
     * FinalRequestProcessor.processRequest MUST process the request
     * synchronously!
     *
     * @param next
     *                a reference to the FinalRequestProcessor
     */
    ToBeAppliedRequestProcessor(sp<RequestProcessor> next,
    		sp<EConcurrentLinkedQueue<Proposal> > toBeApplied) {
    	sp<FinalRequestProcessor> frp = dynamic_pointer_cast<FinalRequestProcessor>(next);
        if (frp == null) {
            throw ERuntimeException(__FILE__, __LINE__,
            		"ToBeAppliedRequestProcessor must be connected to "
                    "FinalRequestProcessor not RequestProcessor");
        }
        this->toBeApplied = toBeApplied;
        this->next = next;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.RequestProcessor#processRequest(org.apache.zookeeper.server.Request)
     */
    virtual void processRequest(sp<Request> request) THROWS(RequestProcessorException) {
        // request.addRQRec(">tobe");
        next->processRequest(request);
        sp<Proposal> p = toBeApplied->peek();
        if (p != null && p->request != null
                && p->request->zxid == request->zxid) {
            toBeApplied->remove();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.RequestProcessor#shutdown()
     */
    virtual void shutdown() {
        next->shutdown();
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ToBeAppliedRequestProcessor_HH_ */
