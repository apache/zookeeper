/*
 * LeaderElection.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./LeaderElection.hh"
#include "./QuorumPeer.hh"

namespace efc {
namespace ezk {

sp<ELogger> LeaderElection::LOG = ELoggerManager::getLogger("LeaderElection");

ERandom LeaderElection::epochGen;

LeaderElection::LeaderElection(QuorumPeer* self) {
	this->self = self;
}

sp<LeaderElection::ElectionResult> LeaderElection::countVotes(
		EHashMap<sp<EInetSocketAddress>, sp<Vote> >& votes,
		EHashSet<llong>& heardFrom) {
	sp<ElectionResult> result = new ElectionResult();
	// Initialize with null vote
	result->vote = new Vote(ELLong::MIN_VALUE, ELLong::MIN_VALUE);
	result->winner = new Vote(ELLong::MIN_VALUE, ELLong::MIN_VALUE);

	// First, filter out votes from unheard-from machines. Then
	// make the views consistent. Sometimes peers will have
	// different zxids for a server depending on timing.
	EHashMap<sp<EInetSocketAddress>, sp<Vote> > validVotes;// = new HashMap<InetSocketAddress, Vote>();
	EHashMap<llong, ELLong*> maxZxids;
	auto it1 = votes.entrySet()->iterator();
	while (it1->hasNext()) {
		auto e = it1->next();
		// Only include votes from machines that we heard from
		sp<Vote> v = e->getValue();
		if (heardFrom.contains(v->getId())) {
			validVotes.put(e->getKey(), v);
			ELLong* val = maxZxids.get(v->getId());
			if (val == null || val->llongValue() < v->getZxid()) {
				maxZxids.put(v->getId(), new ELLong(v->getZxid()));
			}
		}
	}

	// Make all zxids for a given vote id equal to the largest zxid seen for
	// that id
	auto it2 = validVotes.entrySet()->iterator();
	while (it2->hasNext()) {
		auto e = it2->next();
		sp<Vote> v = e->getValue();
		ELLong* zxid = maxZxids.get(v->getId());
		if (v->getZxid() < zxid->intValue()) {
			// This is safe inside an iterator as per
			// http://download.oracle.com/javase/1.5.0/docs/api/java/util/Map.Entry.html
			e->setValue(new Vote(v->getId(), zxid->llongValue(), v->getElectionEpoch(), v->getPeerEpoch(), v->getState()));
		}
	}

	result->numValidVotes = validVotes.size();

	EHashMap<sp<Vote>, sp<EInteger> > countTable;// = new HashMap<Vote, Integer>();
	// Now do the tally
	auto it3 = validVotes.values()->iterator();
	while (it3->hasNext()) {
		sp<Vote> v = it3->next();
		sp<EInteger> count = countTable.get(v.get());
		if (count == null) {
			count = new EInteger(0);
		}
		countTable.put(v, new EInteger(count->intValue() + 1));
		if (v->getId() == result->vote->getId()) {
			result->count++;
		} else if (v->getZxid() > result->vote->getZxid()
				|| (v->getZxid() == result->vote->getZxid() && v->getId() > result->vote->getId())) {
			result->vote = v;
			result->count = 1;
		}
	}
	result->winningCount = 0;
	LOG->info("Election tally: ");
	auto it4 = countTable.entrySet()->iterator();
	while (it4->hasNext()) {
		auto entry = it4->next();
		if (entry->getValue()->intValue() > result->winningCount) {
			result->winningCount = entry->getValue()->intValue();
			result->winner = entry->getKey();
		}
		LOG->info(EString("") + entry->getKey()->getId() + "\t-> " + entry->getValue()->intValue());
	}
	return result;
}

sp<Vote> LeaderElection::lookForLeader() THROWS(EInterruptedException) {
	self->setCurrentVote(new Vote(self->getId(),
			self->getLastLoggedZxid()));
	// We are going to look for a leader by casting a vote for ourself
	EA<byte> requestBytes(4);// = new byte[4];
	sp<EIOByteBuffer> requestBuffer = EIOByteBuffer::wrap(requestBytes.address(), requestBytes.length());
	EA<byte> responseBytes(28);// = new byte[28];
	sp<EIOByteBuffer> responseBuffer = EIOByteBuffer::wrap(responseBytes.address(), responseBytes.length());
	/* The current vote for the leader. Initially me! */
	sp<EDatagramSocket> s = null;
	try {
		s = new EDatagramSocket();
		s->setSoTimeout(200);
	} catch (ESocketException& e1) {
		LOG->error("Socket exception when creating socket for leader election", e1);
		ESystem::exit(4);
	}
	EDatagramPacket requestPacket(requestBytes, requestBytes.length());
	EDatagramPacket responsePacket(responseBytes, responseBytes.length());
	int xid = epochGen.nextInt();
	while (self->isRunning()) {
		EHashMap<sp<EInetSocketAddress>, sp<Vote> > votes(self->getVotingView()->size());

		requestBuffer->clear();
		requestBuffer->putInt(xid);
		requestPacket.setLength(4);
		EHashSet<llong> heardFrom;// = new HashSet<Long>();
		auto vv = self->getVotingView();
		auto iter = vv->values()->iterator();
		while (iter->hasNext()) {
			QuorumServer* server = iter->next();
			LOG->info("Server address: " + server->addr->toString());
			try {
				requestPacket.setSocketAddress(server->addr.get());
			} catch (EIllegalArgumentException& e) {
				// Sun doesn't include the address that causes this
				// exception to be thrown, so we wrap the exception
				// in order to capture this critical detail.
				throw EIllegalArgumentException(__FILE__, __LINE__,
						(EString("Unable to set socket address on packet, msg:")
						+ e.getMessage() + " with addr:" + server->addr->toString()).c_str(),
						&e);
			}

			try {
				s->send(&requestPacket);
				responsePacket.setLength(responseBytes.length());
				s->receive(&responsePacket);
				if (responsePacket.getLength() != responseBytes.length()) {
					LOG->error(EString("Got a short response: ")
							+ responsePacket.getLength());
					continue;
				}
				responseBuffer->clear();
				int recvedXid = responseBuffer->getInt();
				if (recvedXid != xid) {
					LOG->error(EString("Got bad xid: expected ") + xid
							+ " got " + recvedXid);
					continue;
				}
				llong peerId = responseBuffer->getLLong();
				heardFrom.add(peerId);

				sp<Vote> vote = new Vote(responseBuffer->getLLong(),
					responseBuffer->getLLong());
				sp<EInetSocketAddress> addr = responsePacket.getSocketAddress();
				votes.put(addr, vote);

			} catch (EIOException& e) {
				LOG->warn("Ignoring exception while looking for leader", e);
				// Errors are okay, since hosts may be
				// down
			}
		}

		sp<ElectionResult> result = countVotes(votes, heardFrom);
		// ZOOKEEPER-569:
		// If no votes are received for live peers, reset to voting
		// for ourselves as otherwise we may hang on to a vote
		// for a dead peer
		if (result->numValidVotes == 0) {
			self->setCurrentVote(new Vote(self->getId(),
					self->getLastLoggedZxid()));
		} else {
			if (result->winner->getId() >= 0) {
				self->setCurrentVote(result->vote);
				// To do: this doesn't use a quorum verifier
				if (result->winningCount > (self->getVotingView()->size() / 2)) {
					self->setCurrentVote(result->winner);
					s->close();
					sp<Vote> current = self->getCurrentVote();
					LOG->info(EString("Found leader: my type is: ") + self->getLearnerType());
					/*
					 * We want to make sure we implement the state machine
					 * correctly. If we are a PARTICIPANT, once a leader
					 * is elected we can move either to LEADING or
					 * FOLLOWING. However if we are an OBSERVER, it is an
					 * error to be elected as a Leader.
					 */
					if (self->getLearnerType() == QuorumServer::LearnerType::OBSERVER) {
						if (current->getId() == self->getId()) {
							// This should never happen!
							LOG->error("OBSERVER elected as leader!");
							EThread::sleep(100);
						}
						else {
							self->setPeerState(ServerState::OBSERVING);
							EThread::sleep(100);
							return current;
						}
					} else {
						self->setPeerState((current->getId() == self->getId())
								? ServerState::LEADING: ServerState::FOLLOWING);
						if (self->getPeerState() == ServerState::FOLLOWING) {
							EThread::sleep(100);
						}
						return current;
					}
				}
			}
		}
		EThread::sleep(1000);
	}
	return null;
}

} /* namespace ezk */
} /* namespace efc */
