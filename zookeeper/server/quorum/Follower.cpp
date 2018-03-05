/*
 * Follower.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./Follower.hh"
#include "./QuorumPeer.hh"
#include "./FollowerZooKeeperServer.hh"

namespace efc {
namespace ezk {

Follower::Follower(QuorumPeer* self, sp<FollowerZooKeeperServer> zk) :
		lastQueued(0) {
	this->self = self;
	this->zk = zk;
	this->fzk = zk;
}

void Follower::followLeader() THROWS(EInterruptedException) {
	self->end_fle = TimeUtils::currentElapsedTime();
	llong electionTimeTaken = self->end_fle - self->start_fle;
	self->setElectionTimeTaken(electionTimeTaken);
	LOG->info(EString("FOLLOWING - LEADER ELECTION TOOK - ") + electionTimeTaken);
	self->start_fle = 0;
	self->end_fle = 0;

	{
		QuorumServer* leaderServer = findLeader();
		try {
			connectToLeader(leaderServer->addr, leaderServer->hostname);
			llong newEpochZxid = registerWithLeader(PacketType::FOLLOWERINFO);

			//check to see if the leader zxid is lower than ours
			//this should never happen but is just a safety check
			llong newEpoch = ZxidUtils::getEpochFromZxid(newEpochZxid);
			if (newEpoch < self->getAcceptedEpoch()) {
				LOG->error("Proposed leader epoch " + ZxidUtils::zxidToString(newEpochZxid)
						+ " is less than our accepted epoch " + ZxidUtils::zxidToString(self->getAcceptedEpoch()));
				throw EIOException(__FILE__, __LINE__, "Error: Epoch of leader is lower");
			}
			syncWithLeader(newEpochZxid);
			sp<QuorumPacket> qp = new QuorumPacket();
			while (this->isRunning()) {
				readPacket(qp);
				processPacket(qp);
			}
		} catch (EException& e) {
			LOG->warn("Exception when following the leader", e);
			try {
				sock->close();
			} catch (EIOException& e1) {
				e1.printStackTrace();
			}

			// clear pending revalidations
			pendingRevalidations.clear();
		}
	}
}

void Follower::processPacket(sp<QuorumPacket> qp) THROWS(EIOException) {
	switch (qp->getType()) {
	case PacketType::PING:
		ping(qp);
		break;
	case PacketType::PROPOSAL: {
		sp<TxnHeader> hdr = new TxnHeader();
		sp<ERecord> txn = SerializeUtils::deserializeTxn(qp->getData(), hdr.get());
		if (hdr->getZxid() != lastQueued + 1) {
			LOG->warn("Got zxid 0x"
					+ ELLong::toHexString(hdr->getZxid())
					+ " expected 0x"
					+ ELLong::toHexString(lastQueued + 1));
		}
		lastQueued = hdr->getZxid();
		fzk->logRequest(hdr, txn);
		break;
	}
	case PacketType::COMMIT:
		fzk->commit(qp->getZxid());
		break;
	case PacketType::UPTODATE:
		LOG->error("Received an UPTODATE message after Follower started");
		break;
	case PacketType::REVALIDATE:
		revalidate(qp);
		break;
	case PacketType::SYNC:
		fzk->sync();
		break;
	default:
		LOG->error(EString("Invalid packet type: ") + qp->getType() +" received by Observer");
		break;
	}
}

llong Follower::getZxid() {
	try {
		SYNCHRONIZED(fzk) {
			return fzk->getZxid();
		}}
	} catch (ENullPointerException& e) {
		LOG->warn("error getting zxid", e);
	}
	return -1;
}

} /* namespace ezk */
} /* namespace efc */
