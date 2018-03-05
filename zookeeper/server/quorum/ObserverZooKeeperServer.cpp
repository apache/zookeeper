/*
 * ObserverZooKeeperServer.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./Observer.hh"
#include "./QuorumPeer.hh"
#include "./ObserverZooKeeperServer.hh"
#include "./ObserverRequestProcessor.hh"
#include "../FinalRequestProcessor.hh"

namespace efc {
namespace ezk {

sp<ELogger> ObserverZooKeeperServer::LOG = ELoggerManager::getLogger("ObserverZooKeeperServer");

ObserverZooKeeperServer::ObserverZooKeeperServer(sp<FileTxnSnapLog> logFactory, QuorumPeer* self,
		sp<ZKDatabase> zkDb) THROWS(EIOException) :
	LearnerZooKeeperServer(logFactory, self->tickTime, self->minSessionTimeout,
			self->maxSessionTimeout, zkDb, self) {
	syncRequestProcessorEnabled = self->getSyncEnabled();
	LOG->info(EString("syncEnabled =") + syncRequestProcessorEnabled);
}

sp<Observer> ObserverZooKeeperServer::getObserver() {
	return self->observer;
}

void ObserverZooKeeperServer::setupRequestProcessors() {
	// We might consider changing the processor behaviour of
	// Observers to, for example, remove the disk sync requirements.
	// Currently, they behave almost exactly the same as followers.
	sp<RequestProcessor> finalProcessor = new FinalRequestProcessor(shared_from_this());
	commitProcessor = new CommitProcessor(finalProcessor,
			ELLong::toString(getServerId()), true,
			getZooKeeperServerListener());
	EThread::setDaemon(commitProcessor, true); //!!!
	commitProcessor->start();

	sp<ObserverRequestProcessor> orp = new ObserverRequestProcessor(dynamic_pointer_cast<ObserverZooKeeperServer>(shared_from_this()), commitProcessor);
	firstProcessor = orp;
	EThread::setDaemon(orp, true); //!!!
	orp->start();
	/*
	 * Observer should write to disk, so that the it won't request
	 * too old txn from the leader which may lead to getting an entire
	 * snapshot.
	 *
	 * However, this may degrade performance as it has to write to disk
	 * and do periodic snapshot which may double the memory requirements
	 */
	if (syncRequestProcessorEnabled) {
		syncProcessor = new SyncRequestProcessor(shared_from_this(), null);
		EThread::setDaemon(syncProcessor, true); //!!!
		syncProcessor->start();
	}
}

sp<Learner> ObserverZooKeeperServer::getLearner() {
	return self->observer;
}

void ObserverZooKeeperServer::commitRequest(sp<Request> request) {
	if (syncRequestProcessorEnabled) {
		// Write to txnlog and take periodic snapshot
		syncProcessor->processRequest(request);
	}
	commitProcessor->commit(request);
}

void ObserverZooKeeperServer::sync(){
	SYNCHRONIZED(this) {
		if(pendingSyncs.size() ==0){
			LOG->warn("Not expecting a sync.");
			return;
		}

		sp<Request> r = pendingSyncs.remove();
		commitProcessor->commit(r);
	}}
}

void ObserverZooKeeperServer::shutdown() {
	SYNCHRONIZED(this) {
		if (!canShutdown()) {
			LOG->debug("ObserverZooKeeper server is not running, so not proceeding to shutdown!");
			return;
		}
		LearnerZooKeeperServer::shutdown();
		if (syncRequestProcessorEnabled && syncProcessor != null) {
			syncProcessor->shutdown();
		}
	}}
}

} /* namespace ezk */
} /* namespace efc */
