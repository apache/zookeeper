/*
 * ObserverRequestProcessor.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./ObserverRequestProcessor.hh"
#include "./Observer.hh"

namespace efc {
namespace ezk {

sp<ELogger> ObserverRequestProcessor::LOG = ELoggerManager::getLogger("ObserverRequestProcessor");

void ObserverRequestProcessor::run() {
	try {
		while (!finished) {
			sp<Request> request = queuedRequests.take();
			if (LOG->isTraceEnabled()) {
				ZooTrace::logRequest(LOG, ZooTrace::CLIENT_REQUEST_TRACE_MASK,
						'F', request.get(), "");
			}
			if (request == Request::requestOfDeath) {
				break;
			}
			// We want to queue the request to be processed before we submit
			// the request to the leader so that we are ready to receive
			// the response
			nextProcessor->processRequest(request);

			// We now ship the request to the leader. As with all
			// other quorum operations, sync also follows this code
			// path, but different from others, we need to keep track
			// of the sync operations this Observer has pending, so we
			// add it to pendingSyncs.
			switch (request->type) {
			case ZooDefs::OpCode::sync:
				zks->pendingSyncs.add(request);
				zks->getObserver()->request(request);
				break;
			case ZooDefs::OpCode::create:
			case ZooDefs::OpCode::delete_:
			case ZooDefs::OpCode::setData:
			case ZooDefs::OpCode::setACL:
			case ZooDefs::OpCode::createSession:
			case ZooDefs::OpCode::closeSession:
			case ZooDefs::OpCode::multi:
				zks->getObserver()->request(request);
				break;
			}
		}
	} catch (EException& e) {
		handleException(this->getName(), e);
	}
	LOG->info("ObserverRequestProcessor exited loop!");
}

} /* namespace ezk */
} /* namespace efc */
