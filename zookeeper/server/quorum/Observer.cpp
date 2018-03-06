/*
 * Observer.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./Observer.hh"
#include "./ObserverZooKeeperServer.hh"

namespace efc {
namespace ezk {

Observer::Observer(QuorumPeer* self, sp<ObserverZooKeeperServer> observerZooKeeperServer) {
	this->self = self;
	this->zk=observerZooKeeperServer;
}

void Observer::observeLeader() THROWS(EInterruptedException) {
	QuorumServer* leaderServer = findLeader();
	LOG->info("Observing " + leaderServer->addr->toString());
	try {
		connectToLeader(leaderServer->addr, leaderServer->hostname);
		llong newLeaderZxid = registerWithLeader(PacketType::OBSERVERINFO);

		syncWithLeader(newLeaderZxid);
		sp<QuorumPacket> qp = new QuorumPacket();
		while (this->isRunning()) {
			readPacket(qp);
			processPacket(qp);
		}
	} catch (EException& e) {
		LOG->warn("Exception when observing the leader", e);
		try {
			sock->close();
		} catch (EIOException& e1) {
			e1.printStackTrace();
		}

		// clear pending revalidations
		pendingRevalidations.clear();
	}
}

void Observer::processPacket(sp<QuorumPacket> qp) THROWS(EIOException) {
	switch (qp->getType()) {
	case PacketType::PING:
		ping(qp);
		break;
	case PacketType::PROPOSAL:
		LOG->warn("Ignoring proposal");
		break;
	case PacketType::COMMIT:
		LOG->warn("Ignoring commit");
		break;
	case PacketType::UPTODATE:
		LOG->error("Received an UPTODATE message after Observer started");
		break;
	case PacketType::REVALIDATE:
		revalidate(qp);
		break;
	case PacketType::SYNC: {
		sp<ObserverZooKeeperServer> ozk = dynamic_pointer_cast<ObserverZooKeeperServer>(zk);
		ozk->sync();
		break;
	}
	case PacketType::INFORM: {
		sp<TxnHeader> hdr = new TxnHeader();
		sp<ERecord> txn = SerializeUtils::deserializeTxn(qp->getData(), hdr.get());
		sp<Request> request = new Request (null, hdr->getClientId(),
									   hdr->getCxid(),
									   hdr->getType(), null, null);
		request->txn = txn;
		request->hdr = hdr;
		sp<ObserverZooKeeperServer> ozk = dynamic_pointer_cast<ObserverZooKeeperServer>(zk);
		ozk->commitRequest(request);
		break;
	}
	default:
		LOG->error(EString("Invalid packet type: ") + qp->getType() + " received by Observer");
		break;
	}
}

void Observer::shutdown() {
	LOG->info("shutdown called (shutdown Observer)");
	Learner::shutdown();
}

} /* namespace ezk */
} /* namespace efc */
