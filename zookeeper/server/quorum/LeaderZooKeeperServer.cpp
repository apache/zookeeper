/*
 * LeaderZooKeeperServer.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./Leader.hh"
#include "./QuorumPeer.hh"
#include "./LeaderZooKeeperServer.hh"
#include "./ProposalRequestProcessor.hh"
#include "./ToBeAppliedRequestProcessor.hh"
#include "../PrepRequestProcessor.hh"

namespace efc {
namespace ezk {

LeaderZooKeeperServer::LeaderZooKeeperServer(sp<FileTxnSnapLog> logFactory, QuorumPeer* self,
		sp<ZKDatabase> zkDb) THROWS(EIOException) :
		QuorumZooKeeperServer(logFactory, self->tickTime, self->minSessionTimeout,
				self->maxSessionTimeout, zkDb, self) {
}

sp<Leader> LeaderZooKeeperServer::getLeader() {
	return self->leader;
}

int LeaderZooKeeperServer::getGlobalOutstandingLimit() {
	return ZooKeeperServer::getGlobalOutstandingLimit() / (self->getQuorumSize() - 1);
}

void LeaderZooKeeperServer::createSessionTracker() {
	sessionTracker = new SessionTrackerImpl(shared_from_this(), getZKDatabase()
			->getSessionWithTimeOuts(), tickTime, self->getId(),
			getZooKeeperServerListener());
}

void LeaderZooKeeperServer::startSessionTracker() {
	sp<SessionTrackerImpl> st = dynamic_pointer_cast<SessionTrackerImpl>(sessionTracker);
	EThread::setDaemon(st, true); //!!!
	st->start();
}

void LeaderZooKeeperServer::setupRequestProcessors() {
	sp<RequestProcessor> finalProcessor = new FinalRequestProcessor(shared_from_this());
	sp<RequestProcessor> toBeAppliedProcessor = new ToBeAppliedRequestProcessor(
			finalProcessor, getLeader()->toBeApplied);
	commitProcessor = new CommitProcessor(toBeAppliedProcessor,
			ELLong::toString(getServerId()), false,
			getZooKeeperServerListener());
	EThread::setDaemon(commitProcessor, true); //!!!
	commitProcessor->start();
	sp<ProposalRequestProcessor> proposalProcessor = new ProposalRequestProcessor(dynamic_pointer_cast<LeaderZooKeeperServer>(shared_from_this()),
			commitProcessor);
	proposalProcessor->initialize();
	sp<PrepRequestProcessor> prp = new PrepRequestProcessor(shared_from_this(), proposalProcessor);
    firstProcessor = prp;
    EThread::setDaemon(prp, true); //!!!
    prp->start();
}

llong LeaderZooKeeperServer::getServerId() {
	return self->getId();
}

void LeaderZooKeeperServer::revalidateSession(sp<ServerCnxn> cnxn, llong sessionId,
	int sessionTimeout) THROWS(EIOException) {
	ZooKeeperServer::revalidateSession(cnxn, sessionId, sessionTimeout);
	try {
		// setowner as the leader itself, unless updated
		// via the follower handlers
		setOwner(sessionId, ServerCnxn::me);
	} catch (SessionExpiredException& e) {
		// this is ok, it just means that the session revalidation failed.
	}
}

} /* namespace ezk */
} /* namespace efc */
