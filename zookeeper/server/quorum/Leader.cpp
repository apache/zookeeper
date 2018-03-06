/*
 * Leader.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./Leader.hh"
#include "./QuorumPeer.hh"

namespace efc {
namespace ezk {

sp<ELogger> Leader::LOG = ELoggerManager::getLogger("Leader");
boolean Leader::nodelay = false;

DEFINE_STATIC_INITZZ_BEGIN(Leader)
    ESystem::_initzz_();
    nodelay = EString("true").equals(ESystem::getProperty("leader.nodelay", "true"));
DEFINE_STATIC_INITZZ_END

//=============================================================================

Leader::LearnerCnxAcceptor::LearnerCnxAcceptor(sp<Leader> owner) :
	ZooKeeperThread("LearnerCnxAcceptor-" + owner->ss->getLocalSocketAddress()->toString()),
	owner(owner), stop(false) {
	//
}

void Leader::LearnerCnxAcceptor::run() {
	try {
		while (!stop) {
			try{
				sp<ESocket> s = owner->ss->accept();
				// start with the initLimit, once the ack is processed
				// in LearnerHandler switch to the syncLimit
				s->setSoTimeout(owner->self->tickTime * owner->self->initLimit);
				s->setTcpNoDelay(nodelay);

				sp<EBufferedInputStream> is = new EBufferedInputStream(s->getInputStream());
				sp<LearnerHandler> fh = new LearnerHandler(s, is, owner);
				EThread::setDaemon(fh, true); //!!!
				fh->start();
			} catch (ESocketException& e) {
				if (stop) {
					Leader::LOG->info("exception while shutting down acceptor. ", e);

					// When Leader.shutdown() calls ss.close(),
					// the call to accept throws an exception.
					// We catch and set stop to true.
					stop = true;
				} else {
					throw e;
				}
			} catch (ESaslException& e){
				Leader::LOG->error("Exception while connecting to quorum learner", e);
			}
		}
	} catch (EException& e) {
		Leader::LOG->warn("Exception while accepting follower", e);
	}
}

void Leader::LearnerCnxAcceptor::halt() {
	stop = true;
}

//=============================================================================

Leader::~Leader() {
	//
}

Leader::Leader(QuorumPeer* self, sp<LeaderZooKeeperServer> zk) THROWS(EIOException) :
	quorumFormed(false), electionFinished(false),
	followerCounter(-1), epoch(-1),
	waitingForNewEpoch(true), readyToStart(false),
	lastCommitted(-1), lastProposed(0),
	isShutdown(false)
{
	LOG->info(EString("TCP NoDelay set to: ") + nodelay);
	toBeApplied = new EConcurrentLinkedQueue<Proposal>();
	newLeaderProposal = new Proposal();
	connectingFollowersCond = connectingFollowersLock.newCondition();
	electingFollowersCond = electingFollowersLock.newCondition();

	this->self = self;
	try {
		if (self->getQuorumListenOnAllIPs()) {
			ss = new EServerSocket(self->getQuorumAddress()->getPort());
		} else {
			ss = new EServerSocket();
		}
		ss->setReuseAddress(true);
		if (!self->getQuorumListenOnAllIPs()) {
			ss->bind(self->getQuorumAddress().get());
		}
	} catch (EBindException& e) {
		if (self->getQuorumListenOnAllIPs()) {
			LOG->error(EString("Couldn't bind to port ") + self->getQuorumAddress()->getPort(), e);
		} else {
			LOG->error("Couldn't bind to " + self->getQuorumAddress()->toString(), e);
		}
		throw e;
	}
	this->zk=zk;
}

sp<EList<sp<LearnerHandler> > > Leader::getLearners() {
	SYNCBLOCK(&learnersLock) {
		//@see: return new ArrayList<LearnerHandler>(learners);
		sp<EArrayList<sp<LearnerHandler> > > clone = new EArrayList<sp<LearnerHandler> >();
		clone->addAll(&learners);
		return clone;
	}}
}

sp<EList<sp<LearnerHandler> > > Leader::getForwardingFollowers() {
	SYNCBLOCK(&forwardingFollowersLock) {
		//@see: return new ArrayList<LearnerHandler>(forwardingFollowers);
		sp<EArrayList<sp<LearnerHandler> > > clone = new EArrayList<sp<LearnerHandler> >();
		clone->addAll(&forwardingFollowers);
		return clone;
	}}
}

sp<EList<sp<LearnerHandler> > > Leader::getObservingLearners() {
	SYNCBLOCK(&observingLearnersLock) {
		//@see: return new ArrayList<LearnerHandler>(forwardingFollowers);
		sp<EArrayList<sp<LearnerHandler> > > clone = new EArrayList<sp<LearnerHandler> >();
		clone->addAll(&observingLearners);
		return clone;
	}}
}

int Leader::getNumPendingSyncs() {
	SYNCHRONIZED(this) {
		return pendingSyncs.size();
	}}
}

void Leader::addLearnerHandler(sp<LearnerHandler> learner) {
	SYNCBLOCK(&learnersLock) {
		learners.add(learner);
	}}
}

void Leader::removeLearnerHandler(LearnerHandler* peer) {
	SYNCBLOCK (&forwardingFollowersLock) {
		forwardingFollowers.remove(peer);
	}}
	SYNCBLOCK (&learnersLock) {
		learners.remove(peer);
	}}
	SYNCBLOCK (&observingLearnersLock) {
		observingLearners.remove(peer);
	}}
}

boolean Leader::isLearnerSynced(LearnerHandler* peer){
	SYNCBLOCK (&forwardingFollowersLock) {
		return forwardingFollowers.contains(peer);
	}}
}

void Leader::lead() {
	self->end_fle = TimeUtils::currentElapsedTime();
	llong electionTimeTaken = self->end_fle - self->start_fle;
	self->setElectionTimeTaken(electionTimeTaken);
	LOG->info(EString("LEADING - LEADER ELECTION TOOK - ") + electionTimeTaken);
	self->start_fle = 0;
	self->end_fle = 0;

	self->tick.set(0);
	zk->loadData();

	leaderStateSummary = new StateSummary(self->getCurrentEpoch(), zk->getLastProcessedZxid());

	// Start thread that waits for connection requests from
	// new followers.
	sp<LearnerCnxAcceptor> cnxAcceptor = new LearnerCnxAcceptor(shared_from_this());
	cnxAcceptor_ = cnxAcceptor;
	EThread::setDaemon(cnxAcceptor, true); //!!!
	cnxAcceptor->start();

	readyToStart = true;
	llong epoch = getEpochToPropose(self->getId(), self->getAcceptedEpoch());

	zk->setZxid(ZxidUtils::makeZxid(epoch, 0));

	SYNCHRONIZED(this) {
		lastProposed = zk->getZxid();
	}}

	newLeaderProposal->packet = new QuorumPacket(PacketType::NEWLEADER, zk->getZxid(),
			null, null);


	if ((newLeaderProposal->packet->getZxid() & 0xffffffffL) != 0) {
		LOG->info("NEWLEADER proposal has Zxid of "
				+ ELLong::toHexString(newLeaderProposal->packet->getZxid()));
	}

	waitForEpochAck(self->getId(), leaderStateSummary);
	self->setCurrentEpoch(epoch);

	// We have to get at least a majority of servers in sync with
	// us. We do this by waiting for the NEWLEADER packet to get
	// acknowledged
	try {
		waitForNewLeaderAck(self->getId(), zk->getZxid(), QuorumServer::LearnerType::PARTICIPANT);
	} catch (EInterruptedException& e) {
		shutdown("Waiting for a quorum of followers, only synced with sids: [ "
				+ getSidSetString(&newLeaderProposal->ackSet) + " ]");
		EHashSet<llong> followerSet;
		auto it = learners.iterator();
		while (it->hasNext()) {
			sp<LearnerHandler> f = it->next();
			followerSet.add(f->getSid());
		}

		if (self->getQuorumVerifier()->containsQuorum(&followerSet)) {
			LOG->warn("Enough followers present. "
					  "Perhaps the initTicks need to be increased.");
		}
		EThread::sleep(self->tickTime);
		self->tick.incrementAndGet();
		return;
	}

	startZkServer();

	/**
	 * WARNING: do not use this for anything other than QA testing
	 * on a real cluster. Specifically to enable verification that quorum
	 * can handle the lower 32bit roll-over issue identified in
	 * ZOOKEEPER-1277. Without this option it would take a very long
	 * time (on order of a month say) to see the 4 billion writes
	 * necessary to cause the roll-over to occur.
	 *
	 * This field allows you to override the zxid of the server. Typically
	 * you'll want to set it to something like 0xfffffff0 and then
	 * start the quorum, run some operations and see the re-election.
	 */
	EString initialZxid = ESystem::getProperty("zookeeper.testingonly.initialZxid");
	if (!initialZxid.isEmpty()) {
		llong zxid = ELLong::parseLLong(initialZxid.c_str());
		zk->setZxid((zk->getZxid() & 0xffffffff00000000L) | zxid);
	}

	if (!EString("no").equals(ESystem::getProperty("zookeeper.leaderServes", "yes"))) {
		self->cnxnFactory->setZooKeeperServer(zk);
	}
	// Everything is a go, simply start counting the ticks
	// WARNING: I couldn't find any wait statement on a synchronized
	// block that would be notified by this notifyAll() call, so
	// I commented it out
	//synchronized (this) {
	//    notifyAll();
	//}
	// We ping twice a tick, so we only update the tick every other
	// iteration
	boolean tickSkip = true;

	while (true) {
		EThread::sleep(self->tickTime / 2);
		if (!tickSkip) {
			self->tick.incrementAndGet();
		}
		EHashSet<llong> syncedSet;

		// lock on the followers when we use it.
		syncedSet.add(self->getId());

        auto learners = getLearners();
		auto it = learners->iterator();
		while (it->hasNext()) {
			sp<LearnerHandler> f = it->next();
			// Synced set is used to check we have a supporting quorum, so only
			// PARTICIPANT, not OBSERVER, learners should be used
			if (f->synced() && f->getLearnerType() == QuorumServer::LearnerType::PARTICIPANT) {
				syncedSet.add(f->getSid());
			}
			f->ping();
		}

		// check leader running status
		if (!this->isRunning()) {
			shutdown("Unexpected internal error");
			return;
		}

		if (!tickSkip && !self->getQuorumVerifier()->containsQuorum(&syncedSet)) {
			//if (!tickSkip && syncedCount < self->quorumPeers.size() / 2) {
			// Lost quorum, shutdown
			shutdown(
					"Not sufficient followers synced, only synced with sids: [ "
							+ getSidSetString(&syncedSet) + " ]");
			// make sure the order is the same!
			// the leader goes to looking
			return;
		}
		tickSkip = !tickSkip;
	}
}

void Leader::shutdown(EString reason) {
	LOG->info("Shutting down");

	if (isShutdown) {
		return;
	}

	{
		EException e(__FILE__, __LINE__, ("shutdown Leader! reason: " + reason).c_str());
		LOG->info("Shutdown called", e);
	}

	auto cnxAcceptor = cnxAcceptor_.lock();
	if (cnxAcceptor != null) {
		cnxAcceptor->halt();
	}

	// NIO should not accept conenctions
	self->cnxnFactory->setZooKeeperServer(null);
	try {
		ss->close();
	} catch (EIOException& e) {
		LOG->warn("Ignoring unexpected exception during close",e);
	}
	// clear all the connections
	self->cnxnFactory->closeAll();
	// shutdown the previous zk
	if (zk != null) {
		zk->shutdown();
	}
	SYNCBLOCK(&learnersLock) {
		auto it = learners.iterator();
		while (it->hasNext()) {
			sp<LearnerHandler> f = it->next();
			it->remove();
			f->shutdown();
		}
	}}
	isShutdown = true;
}

void Leader::processAck(llong sid, llong zxid, EInetSocketAddress* followerAddr) {
	SYNCHRONIZED(this) {
		if (LOG->isTraceEnabled()) {
			LOG->trace("Ack zxid: 0x" + ELLong::toHexString(zxid));
			auto it = outstandingProposals.values()->iterator();
			while (it->hasNext()) {
				auto p = it->next();
				llong packetZxid = p->packet->getZxid();
				LOG->trace("outstanding proposal: 0x" +
						ELLong::toHexString(packetZxid));
			}
			LOG->trace("outstanding proposals all");
		}

		if ((zxid & 0xffffffffL) == 0) {
			/*
			 * We no longer process NEWLEADER ack by this method. However,
			 * the learner sends ack back to the leader after it gets UPTODATE
			 * so we just ignore the message.
			 */
			return;
		}

		if (outstandingProposals.size() == 0) {
			if (LOG->isDebugEnabled()) {
				LOG->debug("outstanding is 0");
			}
			return;
		}
		if (lastCommitted >= zxid) {
			if (LOG->isDebugEnabled()) {
				LOG->debug("proposal has already been committed, pzxid: 0x" +
						ELLong::toHexString(lastCommitted) + " zxid: 0x" +
						ELLong::toHexString(zxid));
			}
			// The proposal has already been committed
			return;
		}
		sp<Proposal> p = outstandingProposals.get(zxid);
		if (p == null) {
			LOG->warn("Trying to commit future proposal: zxid 0x" +
					ELLong::toHexString(zxid) + " from " +
					followerAddr->toString());
			return;
		}

		p->ackSet.add(sid);
		if (LOG->isDebugEnabled()) {
			LOG->debug(EString::formatOf("Count for zxid: 0x%s is %d",
					ELLong::toHexString(zxid).c_str(), p->ackSet.size()));
		}
		if (self->getQuorumVerifier()->containsQuorum(&p->ackSet)){
			if (zxid != lastCommitted+1) {
				LOG->warn("Commiting zxid 0x" + ELLong::toHexString(zxid) + " from " +
						followerAddr->toString() + "not first!");
				LOG->warn("First is 0x" + ELLong::toHexString(lastCommitted + 1));
			}
			outstandingProposals.remove(zxid);
			if (p->request != null) {
				toBeApplied->add(p);
			}

			if (p->request == null) {
				LOG->warn("Going to commmit null request for proposal: " + p->toString());
			}
			commit(zxid);
			inform(p);
			zk->commitProcessor->commit(p->request);
			if(pendingSyncs.containsKey(zxid)){
				auto list = pendingSyncs.remove(zxid);
				auto it = list->iterator();
				while (it->hasNext()) {
					auto r = it->next();
					sendSync(r);
				}
			}
		}
	}}
}

void Leader::sendPacket(sp<QuorumPacket> qp) {
	SYNCBLOCK(&forwardingFollowersLock) {
		auto it = forwardingFollowers.iterator();
		while (it->hasNext()) {
			sp<LearnerHandler> f = it->next();
			f->queuePacket(qp);
		}
	}}
}

void Leader::sendObserverPacket(sp<QuorumPacket> qp) {
    auto learners = getObservingLearners();
	auto it = learners->iterator();
	while (it->hasNext()) {
		sp<LearnerHandler> f = it->next();
		f->queuePacket(qp);
	}
}

void Leader::commit(llong zxid) {
	SYNCHRONIZED(this) {
		lastCommitted = zxid;
	}}
	sp<QuorumPacket> qp = new QuorumPacket(PacketType::COMMIT, zxid, null, null);
	sendPacket(qp);
}

void Leader::inform(sp<Proposal> proposal) {
	sp<QuorumPacket> qp = new QuorumPacket(PacketType::INFORM, proposal->request->zxid,
										proposal->packet->getData(), null);
	sendObserverPacket(qp);
}

llong Leader::getEpoch(){
	return ZxidUtils::getEpochFromZxid(lastProposed);
}

sp<Proposal> Leader::propose(sp<Request> request) THROWS(XidRolloverException) {
	/**
	 * Address the rollover issue. All lower 32bits set indicate a new leader
	 * election. Force a re-election instead. See ZOOKEEPER-1277
	 */
	if ((request->zxid & 0xffffffffL) == 0xffffffffL) {
		EString msg =
				"zxid lower 32 bits have rolled over, forcing re-election, and therefore new epoch start";
		shutdown(msg);
		throw XidRolloverException(__FILE__, __LINE__, msg);
	}

	EByteArrayOutputStream baos;// = new ByteArrayOutputStream();
	sp<EBinaryOutputArchive> boa = EBinaryOutputArchive::getArchive(&baos);
	try {
		request->hdr->serialize(boa.get(), "hdr");
		if (request->txn != null) {
			request->txn->serialize(boa.get(), "txn");
		}
		baos.close();
	} catch (EIOException& e) {
		LOG->warn("This really should be impossible", e);
	}
	sp<QuorumPacket> pp = new QuorumPacket(PacketType::PROPOSAL, request->zxid,
			baos.toByteArray(), null);

	sp<Proposal> p = new Proposal();
	p->packet = pp;
	p->request = request;
	SYNCHRONIZED(this) {
		if (LOG->isDebugEnabled()) {
			LOG->debug("Proposing:: " + request->toString());
		}

		lastProposed = p->packet->getZxid();
		outstandingProposals.put(lastProposed, p);
		sendPacket(pp);
	}}
	return p;
}

void Leader::processSync(sp<LearnerSyncRequest> r){
	if(outstandingProposals.isEmpty()){
		sendSync(r);
	} else {
		auto l = pendingSyncs.get(lastProposed);
		if (l == null) {
			l = new EArrayList<sp<LearnerSyncRequest> >();
		}
		l->add(r);
		pendingSyncs.put(lastProposed, l);
	}
}

void Leader::sendSync(sp<LearnerSyncRequest> r){
	sp<QuorumPacket> qp = new QuorumPacket(PacketType::SYNC, 0, null, null);
	r->fh->queuePacket(qp);
}

llong Leader::startForwarding(sp<LearnerHandler> handler, llong lastSeenZxid) {
	SYNCHRONIZED(this) {
		// Queue up any outstanding requests enabling the receipt of
		// new requests
		if (lastProposed > lastSeenZxid) {
			auto it = toBeApplied->iterator();
			while (it->hasNext()) {
				sp<Proposal> p = it->next();
				if (p->packet->getZxid() <= lastSeenZxid) {
					continue;
				}
				handler->queuePacket(p->packet);
				// Since the proposal has been committed we need to send the
				// commit message also
				sp<QuorumPacket> qp = new QuorumPacket(PacketType::COMMIT, p->packet
						->getZxid(), null, null);
				handler->queuePacket(qp);
			}
			// Only participant need to get outstanding proposals
			if (handler->getLearnerType() == QuorumServer::LearnerType::PARTICIPANT) {
				//@see: List<Long>zxids = new ArrayList<Long>(outstandingProposals.keySet());
				EArrayList<llong> zxids;
				auto it = outstandingProposals.keySet()->iterator();
				while (it->hasNext()) {
					zxids.add(it->next());
				}
				ECollections::sort(&zxids);
				for (int i=0; i<zxids.size(); i++) {
					llong zxid = zxids.getAt(i);
					if (zxid <= lastSeenZxid) {
						continue;
					}
					handler->queuePacket(outstandingProposals.get(zxid)->packet);
				}
			}
		}
		if (handler->getLearnerType() == QuorumServer::LearnerType::PARTICIPANT) {
			addForwardingFollower(handler);
		} else {
			addObserverLearnerHandler(handler);
		}

		return lastProposed;
	}}
}

llong Leader::getEpochToPropose(llong sid, llong lastAcceptedEpoch) THROWS2(EInterruptedException, EIOException) {
	SYNCBLOCK(&connectingFollowersLock) {
		if (!waitingForNewEpoch) {
			return epoch;
		}
		if (lastAcceptedEpoch >= epoch) {
			epoch = lastAcceptedEpoch+1;
		}
		connectingFollowers.add(sid);
		sp<QuorumVerifier> verifier = self->getQuorumVerifier();
		if (connectingFollowers.contains(self->getId()) &&
										verifier->containsQuorum(&connectingFollowers)) {
			waitingForNewEpoch = false;
			self->setAcceptedEpoch(epoch);
			//@see: connectingFollowers.notifyAll();
			connectingFollowersCond->signalAll();
		} else {
			llong start = TimeUtils::currentElapsedTime();
			llong cur = start;
			llong end = start + self->getInitLimit()*self->getTickTime();
			while(waitingForNewEpoch && cur < end) {
				//@see: connectingFollowers.wait(end - cur);
				connectingFollowersCond->awaitNanos((end - cur) * 1000000);
				cur = TimeUtils::currentElapsedTime();
			}
			if (waitingForNewEpoch) {
				throw EInterruptedException(__FILE__, __LINE__, "Timeout while waiting for epoch from quorum");
			}
		}
		return epoch;
	}}
}

void Leader::waitForEpochAck(llong id, sp<StateSummary> ss) THROWS2(EIOException, EInterruptedException) {
	SYNCBLOCK(&electingFollowersLock) {
		if (electionFinished) {
			return;
		}
		if (ss->getCurrentEpoch() != -1) {
			if (ss->isMoreRecentThan(leaderStateSummary)) {
				throw EIOException(__FILE__, __LINE__, (EString("Follower is ahead of the leader, leader summary: ")
												+ leaderStateSummary->getCurrentEpoch()
												+ " (current epoch), "
												+ leaderStateSummary->getLastZxid()
												+ " (last zxid)").c_str());
			}
			electingFollowers.add(id);
		}
		sp<QuorumVerifier> verifier = self->getQuorumVerifier();
		if (electingFollowers.contains(self->getId()) && verifier->containsQuorum(&electingFollowers)) {
			electionFinished = true;
			//@see: electingFollowers.notifyAll();
			electingFollowersCond->signalAll();
		} else {
			llong start = TimeUtils::currentElapsedTime();
			llong cur = start;
			llong end = start + self->getInitLimit()*self->getTickTime();
			while(!electionFinished && cur < end) {
				//@see: electingFollowers.wait(end - cur);
				electingFollowersCond->awaitNanos((end - cur) * 1000000);
				cur = TimeUtils::currentElapsedTime();
			}
			if (!electionFinished) {
				throw EInterruptedException(__FILE__, __LINE__, "Timeout while waiting for epoch to be acked by quorum");
			}
		}
	}}
}

void Leader::waitForNewLeaderAck(llong sid, llong zxid, QuorumServer::LearnerType learnerType)
		THROWS(EInterruptedException) {

	SYNCHRONIZED(&newLeaderProposal->ackSet) {
		if (quorumFormed) {
			return;
		}

		llong currentZxid = newLeaderProposal->packet->getZxid();
		if (zxid != currentZxid) {
			LOG->error(EString("NEWLEADER ACK from sid: ") + sid
					+ " is from a different epoch - current 0x"
					+ ELLong::toHexString(currentZxid) + " receieved 0x"
					+ ELLong::toHexString(zxid));
			return;
		}

		if (learnerType == QuorumServer::LearnerType::PARTICIPANT) {
			newLeaderProposal->ackSet.add(sid);
		}

		if (self->getQuorumVerifier()->containsQuorum(
				&newLeaderProposal->ackSet)) {
			quorumFormed = true;
			newLeaderProposal->ackSet.notifyAll();
		} else {
			llong start = TimeUtils::currentElapsedTime();
			llong cur = start;
			llong end = start + self->getInitLimit() * self->getTickTime();
			while (!quorumFormed && cur < end) {
				newLeaderProposal->ackSet.wait(end - cur);
				cur = TimeUtils::currentElapsedTime();
			}
			if (!quorumFormed) {
				throw EInterruptedException(__FILE__, __LINE__,
						"Timeout while waiting for NEWLEADER to be acked by quorum");
			}
		}
	}}
}

void Leader::addForwardingFollower(sp<LearnerHandler> lh) {
	SYNCBLOCK(&forwardingFollowersLock) {
		forwardingFollowers.add(lh);
	}}
}

void Leader::addObserverLearnerHandler(sp<LearnerHandler> lh) {
	SYNCBLOCK(&observingLearnersLock) {
		observingLearners.add(lh);
	}}
}

EString Leader::getSidSetString(ESet<llong>* sidSet) {
	EString sids;// = new StringBuilder();
	auto iter = sidSet->iterator();
	while (iter->hasNext()) {
		sids.append(iter->next());
		if (!iter->hasNext()) {
		  break;
		}
		sids.append(",");
	}
	return sids;
}

void Leader::startZkServer() {
	SYNCHRONIZED(this) {
		// Update lastCommitted and Db's zxid to a value representing the new epoch
		lastCommitted = zk->getZxid();
		LOG->info("Have quorum of supporters, sids: [ "
				+ getSidSetString(&newLeaderProposal->ackSet)
				+ " ]; starting up and setting last processed zxid: 0x"
				+ ELLong::toHexString(zk->getZxid()));
		zk->startup();
		/*
		 * Update the election vote here to ensure that all members of the
		 * ensemble report the same vote to new servers that start up and
		 * send leader election notifications to the ensemble.
		 *
		 * @see https://issues.apache.org/jira/browse/ZOOKEEPER-1732
		 */
		self->updateElectionVote(getEpoch());

		zk->getZKDatabase()->setlastProcessedZxid(zk->getZxid());
	}}
}

boolean Leader::isRunning() {
	return self->isRunning() && zk->isRunning();
}

} /* namespace ezk */
} /* namespace efc */
