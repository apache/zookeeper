/*
 * AckRequestProcessor.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef AckRequestProcessor_HH_
#define AckRequestProcessor_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./Leader.hh"
#include "../Request.hh"
#include "../RequestProcessor.hh"

namespace efc {
namespace ezk {

/**
 * This is a very simple RequestProcessor that simply forwards a request from a
 * previous stage to the leader as an ACK.
 */

//class Leader;

class AckRequestProcessor : virtual public RequestProcessor {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(AckRequestProcessor.class);

public:
    sp<Leader> leader;

    AckRequestProcessor(sp<Leader> leader) {
        this->leader = leader;
    }

    /**
     * Forward the request as an ACK to the leader
     */
    virtual void processRequest(sp<Request> request);

    virtual void shutdown() {
        // XXX No need to do anything
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* AckRequestProcessor_HH_ */

