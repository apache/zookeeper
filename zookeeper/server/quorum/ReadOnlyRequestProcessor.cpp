/*
 * ReadOnlyRequestProcessor.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./ReadOnlyRequestProcessor.hh"
#include "./Leader.hh"

namespace efc {
namespace ezk {

sp<ELogger> ReadOnlyRequestProcessor::LOG = ELoggerManager::getLogger("ReadOnlyRequestProcessor");

void ReadOnlyRequestProcessor::run() {
	try {
		while (!finished) {
			sp<Request> request = queuedRequests.take();

			// log request
			llong traceMask = ZooTrace::CLIENT_REQUEST_TRACE_MASK;
			if (request->type == ZooDefs::OpCode::ping) {
				traceMask = ZooTrace::CLIENT_PING_TRACE_MASK;
			}
			if (LOG->isTraceEnabled()) {
				ZooTrace::logRequest(LOG, traceMask, 'R', request.get(), "");
			}
			if (Request::requestOfDeath == request) {
				break;
			}

			// filter read requests
			switch (request->type) {
			case ZooDefs::OpCode::sync:
			case ZooDefs::OpCode::create:
			case ZooDefs::OpCode::delete_:
			case ZooDefs::OpCode::setData:
			case ZooDefs::OpCode::setACL:
			case ZooDefs::OpCode::multi:
			case ZooDefs::OpCode::check:
				sp<ReplyHeader> hdr = new ReplyHeader(request->cxid, zks->getZKDatabase()
						->getDataTreeLastProcessedZxid(), KeeperException::Code::NOTREADONLY);
				try {
					request->cnxn->sendResponse(hdr, null, null);
				} catch (EIOException& e) {
					LOG->error("IO exception while sending response", e);
				}
				continue;
			}

			// proceed to the next processor
			if (nextProcessor != null) {
				nextProcessor->processRequest(request);
			}
		}
	} catch (RequestProcessorException& e) {
		XidRolloverException* o = dynamic_cast<XidRolloverException*>(e.getCause());
		if (o) {
			LOG->info(e.getCause()->getMessage());
		}
		handleException(this->getName(), e);
	} catch (EException& e) {
		handleException(this->getName(), e);
	}
	LOG->info("ReadOnlyRequestProcessor exited loop!");
}

} /* namespace ezk */
} /* namespace efc */
