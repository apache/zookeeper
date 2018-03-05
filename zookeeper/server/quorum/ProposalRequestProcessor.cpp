/*
 * ProposalRequestProcessor.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./ProposalRequestProcessor.hh"
#include "./LearnerSyncRequest.hh"
#include "./Leader.hh"

namespace efc {
namespace ezk {

sp<ELogger> ProposalRequestProcessor::LOG = ELoggerManager::getLogger("ProposalRequestProcessor");

void ProposalRequestProcessor::processRequest(sp<Request> request) {
	/* In the following IF-THEN-ELSE block, we process syncs on the leader.
	 * If the sync is coming from a follower, then the follower
	 * handler adds it to syncHandler. Otherwise, if it is a client of
	 * the leader that issued the sync command, then syncHandler won't
	 * contain the handler. In this case, we add it to syncHandler, and
	 * call processRequest on the next processor.
	 */

	sp<LearnerSyncRequest> lsr = dynamic_pointer_cast<LearnerSyncRequest>(request);

	if (lsr != null) {
		zks->getLeader()->processSync(lsr);
	} else {
			nextProcessor->processRequest(request);
		if (request->hdr != null) {
			// We need to sync and get consensus on any transactions
			try {
				zks->getLeader()->propose(request);
			} catch (XidRolloverException& e) {
				throw RequestProcessorException(__FILE__, __LINE__, e.getMessage(), &e);
			}
			syncProcessor->processRequest(request);
		}
	}
}

} /* namespace ezk */
} /* namespace efc */
