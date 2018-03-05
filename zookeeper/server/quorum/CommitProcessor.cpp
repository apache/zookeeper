/*
 * CommitProcessor.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./CommitProcessor.hh"

namespace efc {
namespace ezk {

sp<ELogger> CommitProcessor::LOG = ELoggerManager::getLogger("CommitProcessor");

CommitProcessor::CommitProcessor(sp<RequestProcessor> nextProcessor, EString id,
		boolean matchSyncs, ZooKeeperServerListener* listener) :
			ZooKeeperCriticalThread("CommitProcessor:" + id, listener) {
	this->nextProcessor = nextProcessor;
	this->matchSyncs = matchSyncs;

	finished = false;
}

void CommitProcessor::run() {
	try {
		sp<Request> nextPending = null;
		while (!finished) {
			int len = toProcess.size();
			for (int i = 0; i < len; i++) {
				nextProcessor->processRequest(toProcess.getAt(i));
			}
			toProcess.clear();
			SYNCHRONIZED(this) {
				if ((queuedRequests.size() == 0 || nextPending != null)
						&& committedRequests.size() == 0) {
					wait();
					continue;
				}
				// First check and see if the commit came in for the pending
				// request
				if ((queuedRequests.size() == 0 || nextPending != null)
						&& committedRequests.size() > 0) {
					sp<Request> r = committedRequests.remove();
					/*
					 * We match with nextPending so that we can move to the
					 * next request when it is committed. We also want to
					 * use nextPending because it has the cnxn member set
					 * properly.
					 */
					if (nextPending != null
							&& nextPending->sessionId == r->sessionId
							&& nextPending->cxid == r->cxid) {
						// we want to send our version of the request.
						// the pointer to the connection in the request
						nextPending->hdr = r->hdr;
						nextPending->txn = r->txn;
						nextPending->zxid = r->zxid;
						toProcess.add(nextPending);
						nextPending = null;
					} else {
						// this request came from someone else so just
						// send the commit packet
						toProcess.add(r);
					}
				}
			}}

			// We haven't matched the pending requests, so go back to
			// waiting
			if (nextPending != null) {
				continue;
			}

			SYNCHRONIZED (this) {
				// Process the next requests in the queuedRequests
				while (nextPending == null && queuedRequests.size() > 0) {
					sp<Request> request = queuedRequests.remove();
					switch (request->type) {
					case ZooDefs::OpCode::create:
					case ZooDefs::OpCode::delete_:
					case ZooDefs::OpCode::setData:
					case ZooDefs::OpCode::multi:
					case ZooDefs::OpCode::setACL:
					case ZooDefs::OpCode::createSession:
					case ZooDefs::OpCode::closeSession:
						nextPending = request;
						break;
					case ZooDefs::OpCode::sync:
						if (matchSyncs) {
							nextPending = request;
						} else {
							toProcess.add(request);
						}
						break;
					default:
						toProcess.add(request);
						break;
					}
				}
			}}
		}
	} catch (EInterruptedException& e) {
		LOG->warn("Interrupted exception while waiting", e);
	} catch (EThrowable& e) {
		LOG->error("Unexpected exception causing CommitProcessor to exit", e);
	}
	LOG->info("CommitProcessor exited loop!");
}

void CommitProcessor::commit(sp<Request> request) {
	SYNCHRONIZED (this) {
		if (!finished) {
			if (request == null) {
				EException e(__FILE__, __LINE__, "committing a null! ");
				LOG->warn("Committed a null!", e);
				return;
			}
			if (LOG->isDebugEnabled()) {
				LOG->debug("Committing request:: " + request->toString());
			}
			committedRequests.add(request);
			notifyAll();
		}
	}}
}

void CommitProcessor::processRequest(sp<Request> request) {
	SYNCHRONIZED (this) {
		// request.addRQRec(">commit");
		if (LOG->isDebugEnabled()) {
			LOG->debug("Processing request:: " + request->toString());
		}

		if (!finished) {
			queuedRequests.add(request);
			notifyAll();
		}
	}}
}

void CommitProcessor::shutdown() {
	LOG->info("Shutting down");
	SYNCHRONIZED (this) {
		finished = true;
		queuedRequests.clear();
		notifyAll();
	}}
	if (nextProcessor != null) {
		nextProcessor->shutdown();
	}
}

} /* namespace ezk */
} /* namespace efc */
