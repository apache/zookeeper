/*
 * ReadOnlyZooKeeperServer.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./ReadOnlyZooKeeperServer.hh"
#include "./ReadOnlyRequestProcessor.hh"
#include "./QuorumPeer.hh"

namespace efc {
namespace ezk {

ReadOnlyZooKeeperServer::ReadOnlyZooKeeperServer(sp<FileTxnSnapLog> logFactory, QuorumPeer* self,
		sp<ZKDatabase> zkDb) :
		QuorumZooKeeperServer(logFactory, self->tickTime,self->minSessionTimeout,
				self->maxSessionTimeout, zkDb, self) {
	shutdown_ = false;
}

void ReadOnlyZooKeeperServer::setupRequestProcessors() {
	/* @see:
	RequestProcessor finalProcessor = new FinalRequestProcessor(this);
	RequestProcessor prepProcessor = new PrepRequestProcessor(this, finalProcessor);
	((PrepRequestProcessor) prepProcessor).start();
	firstProcessor = new ReadOnlyRequestProcessor(this, prepProcessor);
	((ReadOnlyRequestProcessor) firstProcessor).start();
	*/
	sp<PrepRequestProcessor> prepProcessor = new PrepRequestProcessor(shared_from_this(),
			new FinalRequestProcessor(shared_from_this()));
	EThread::setDaemon(prepProcessor, true); //!!!
	prepProcessor->start();
	sp<ReadOnlyRequestProcessor> rrp = new ReadOnlyRequestProcessor(shared_from_this(), prepProcessor);
	firstProcessor = rrp;
	EThread::setDaemon(rrp, true); //!!!
	rrp->start();
}

void ReadOnlyZooKeeperServer::startup()  {
	SYNCHRONIZED(this) {
		// check to avoid startup follows shutdown
		if (shutdown_) {
			LOG->warn("Not starting Read-only server as startup follows shutdown!");
			return;
		}
		QuorumZooKeeperServer::startup();
		self->cnxnFactory->setZooKeeperServer(shared_from_this());
		LOG->info("Read-only server started");
	}}
}

llong ReadOnlyZooKeeperServer::getServerId() {
	return self->getId();
}

void ReadOnlyZooKeeperServer::shutdown() {
	SYNCHRONIZED(this) {
		if (!canShutdown()) {
			LOG->debug("ReadOnlyZooKeeper server is not running, so not proceeding to shutdown!");
			return;
		}
		shutdown_ = true;

		// set peer's server to null
		self->cnxnFactory->setZooKeeperServer(null);
		// clear all the connections
		self->cnxnFactory->closeAll();

		// shutdown the server itself
		QuorumZooKeeperServer::shutdown();
	}}
}

} /* namespace ezk */
} /* namespace efc */
