/*
 * LearnerZooKeeperServer.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./LearnerZooKeeperServer.hh"
#include "./Learner.hh"
#include "./QuorumPeer.hh"

namespace efc {
namespace ezk {

void LearnerZooKeeperServer::revalidateSession(sp<ServerCnxn> cnxn, llong sessionId,
		int sessionTimeout) THROWS(EIOException) {
	getLearner()->validateSession(cnxn, sessionId, sessionTimeout);
}

llong LearnerZooKeeperServer::getServerId() {
	return self->getId();
}

void LearnerZooKeeperServer::createSessionTracker() {
	sessionTracker = new LearnerSessionTracker(shared_from_this(), getZKDatabase()
			->getSessionWithTimeOuts(), self->getId(),
			getZooKeeperServerListener());
}

} /* namespace ezk */
} /* namespace efc */
