/*
 * FollowerZooKeeperServer.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./Follower.hh"
#include "./QuorumPeer.hh"
#include "./FollowerZooKeeperServer.hh"
#include "./FollowerRequestProcessor.hh"
#include "./SendAckRequestProcessor.hh"

namespace efc {
namespace ezk {

sp<ELogger> FollowerZooKeeperServer::LOG = ELoggerManager::getLogger("FollowerZooKeeperServer");

FollowerZooKeeperServer::FollowerZooKeeperServer(sp<FileTxnSnapLog> logFactory,QuorumPeer* self,
		sp<ZKDatabase> zkDb) THROWS(EIOException) :
		LearnerZooKeeperServer(logFactory, self->tickTime, self->minSessionTimeout,
			self->maxSessionTimeout, zkDb, self) {
	//
}

sp<Follower> FollowerZooKeeperServer::getFollower(){
	return self->follower;
}

void FollowerZooKeeperServer::setupRequestProcessors() {
	sp<RequestProcessor> finalProcessor = new FinalRequestProcessor(shared_from_this());
	commitProcessor = new CommitProcessor(finalProcessor,
			ELLong::toString(getServerId()), true,
			getZooKeeperServerListener());
	EThread::setDaemon(commitProcessor, true); //!!!
	commitProcessor->start();
	sp<FollowerRequestProcessor> frp = new FollowerRequestProcessor(dynamic_pointer_cast<FollowerZooKeeperServer>(shared_from_this()), commitProcessor);
	firstProcessor = frp;
	EThread::setDaemon(frp, true); //!!!
	frp->start();
	syncProcessor = new SyncRequestProcessor(shared_from_this(),
			new SendAckRequestProcessor(dynamic_pointer_cast<Learner>(getFollower())));
	EThread::setDaemon(syncProcessor, true); //!!!
	syncProcessor->start();
}

sp<Learner> FollowerZooKeeperServer::getLearner() {
	return getFollower();
}

void FollowerZooKeeperServer::logRequest(sp<TxnHeader> hdr, sp<ERecord> txn) {
	sp<Request> request = new Request(null, hdr->getClientId(), hdr->getCxid(),
			hdr->getType(), null, null);
	request->hdr = hdr;
	request->txn = txn;
	request->zxid = hdr->getZxid();
	if ((request->zxid & 0xffffffffL) != 0) {
		pendingTxns.add(request);
	}
	syncProcessor->processRequest(request);
}

void FollowerZooKeeperServer::commit(llong zxid) {
	if (pendingTxns.size() == 0) {
		LOG->warn("Committing " + ELLong::toHexString(zxid)
				+ " without seeing txn");
		return;
	}
	llong firstElementZxid = pendingTxns.element()->zxid;
	if (firstElementZxid != zxid) {
		LOG->error("Committing zxid 0x" + ELLong::toHexString(zxid)
				+ " but next pending txn 0x"
				+ ELLong::toHexString(firstElementZxid));
		ESystem::exit(12);
	}
	sp<Request> request = pendingTxns.remove();
	commitProcessor->commit(request);
}

void FollowerZooKeeperServer::sync(){
	SYNCHRONIZED(this) {
		if(pendingSyncs.size() ==0){
			LOG->warn("Not expecting a sync.");
			return;
		}

		sp<Request> r = pendingSyncs.remove();
		commitProcessor->commit(r);
	}}
}

int FollowerZooKeeperServer::getGlobalOutstandingLimit() {
	return LearnerZooKeeperServer::getGlobalOutstandingLimit() / (self->getQuorumSize() - 1);
}

void FollowerZooKeeperServer::shutdown() {
	LOG->info("Shutting down");
	try {
		LearnerZooKeeperServer::shutdown();
	} catch (EException& e) {
		LOG->warn("Ignoring unexpected exception during shutdown", e);
	}
	try {
		if (syncProcessor != null) {
			syncProcessor->shutdown();
		}
	} catch (EException& e) {
		LOG->warn("Ignoring unexpected exception in syncprocessor shutdown",
				e);
	}
}

} /* namespace ezk */
} /* namespace efc */
