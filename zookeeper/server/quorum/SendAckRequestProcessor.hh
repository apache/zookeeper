/*
 * SendAckRequestProcessor.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef SendAckRequestProcessor_HH_
#define SendAckRequestProcessor_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./PacketType.hh"
#include "./QuorumPacket.hh"
#include "../Request.hh"
#include "../RequestProcessor.hh"
#include "../../ZooDefs.hh"

namespace efc {
namespace ezk {

class Learner;

class SendAckRequestProcessor : virtual public RequestProcessor, virtual public EFlushable {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(SendAckRequestProcessor.class);
    
public:
    sp<Learner> learner;

    SendAckRequestProcessor(sp<Learner> peer) {
        this->learner = peer;
    }

    virtual void processRequest(sp<Request> si);
    
    virtual void flush() THROWS(EIOException);

    virtual void shutdown() {
        // Nothing needed
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* SendAckRequestProcessor_HH_ */
