/*
 * Learner.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./Learner.hh"
#include "./QuorumPeer.hh"
#include "./FollowerZooKeeperServer.hh"
#include "./ObserverZooKeeperServer.hh"
#include "./LearnerZooKeeperServer.hh"

namespace efc {
namespace ezk {

sp<ELogger> Learner::LOG = ELoggerManager::getLogger("Learner");
boolean Learner::nodelay = false;

DEFINE_STATIC_INITZZ_BEGIN(Learner)
    ESystem::_initzz_();
    nodelay = EString("true").equals(ESystem::getProperty("follower.nodelay", "true"));
DEFINE_STATIC_INITZZ_END

Learner::~Learner() {
	delete bufferedInput;
	delete bufferedOutput;
}

Learner::Learner() :
		self(null), zk(null),
		bufferedInput(null), bufferedOutput(null),
		leaderProtocolVersion(0x01) {
	//
}

void Learner::syncWithLeader(llong newLeaderZxid) THROWS2(EIOException, EInterruptedException) {
	sp<QuorumPacket> ack = new QuorumPacket(PacketType::ACK, 0, null, null);
	sp<QuorumPacket> qp = new QuorumPacket();
	llong newEpoch = ZxidUtils::getEpochFromZxid(newLeaderZxid);
	// In the DIFF case we don't need to do a snapshot because the transactions will sync on top of any existing snapshot
	// For SNAP and TRUNC the snapshot is needed to save that history
	boolean snapshotNeeded = true;
	readPacket(qp);
	ELinkedList<llong> packetsCommitted;// = new LinkedList<Long>();
	ELinkedList<sp<PacketInFlight> > packetsNotCommitted;// = new LinkedList<PacketInFlight>();
	SYNCHRONIZED(zk) {
		if (qp->getType() == PacketType::DIFF) {
			LOG->info("Getting a diff from the leader 0x" + ELLong::toHexString(qp->getZxid()));
			snapshotNeeded = false;
		}
		else if (qp->getType() == PacketType::SNAP) {
			LOG->info("Getting a snapshot from leader 0x" + ELLong::toHexString(qp->getZxid()));
			// The leader is going to dump the database
			// clear our own database and read
			zk->getZKDatabase()->clear();
			zk->getZKDatabase()->deserializeSnapshot(leaderIs.get());
			EString signature = leaderIs->readString("signature");
			if (!signature.equals("BenWasHere")) {
				LOG->error("Missing signature. Got " + signature);
				throw EIOException(__FILE__, __LINE__, "Missing signature");
			}
			zk->getZKDatabase()->setlastProcessedZxid(qp->getZxid());
		} else if (qp->getType() == PacketType::TRUNC) {
			//we need to truncate the log to the lastzxid of the leader
			LOG->warn("Truncating log to get in sync with the leader 0x"
					+ ELLong::toHexString(qp->getZxid()));
			boolean truncated=zk->getZKDatabase()->truncateLog(qp->getZxid());
			if (!truncated) {
				// not able to truncate the log
				LOG->error("Not able to truncate the log "
						+ ELLong::toHexString(qp->getZxid()));
				ESystem::exit(13);
			}
			zk->getZKDatabase()->setlastProcessedZxid(qp->getZxid());
		}
		else {
			LOG->error(EString("Got unexpected packet from leader ")
					+ qp->getType() + " exiting ... " );
			ESystem::exit(13);

		}
		zk->createSessionTracker();

		llong lastQueued = 0;

		// in Zab V1.0 (ZK 3.4+) we might take a snapshot when we get the NEWLEADER message, but in pre V1.0
		// we take the snapshot on the UPDATE message, since Zab V1.0 also gets the UPDATE (after the NEWLEADER)
		// we need to make sure that we don't take the snapshot twice.
		boolean isPreZAB1_0 = true;
		//If we are not going to take the snapshot be sure the transactions are not applied in memory
		// but written out to the transaction log
		boolean writeToTxnLog = !snapshotNeeded;
		// we are now going to start getting transactions to apply followed by an UPTODATE
		while (self->isRunning()) {
			readPacket(qp);
			switch(qp->getType()) {
			case PacketType::PROPOSAL: {
				sp<PacketInFlight> pif = new PacketInFlight();
				pif->hdr = new TxnHeader();
				pif->rec = SerializeUtils::deserializeTxn(qp->getData(), pif->hdr.get());
				if (pif->hdr->getZxid() != lastQueued + 1) {
					LOG->warn("Got zxid 0x"
						+ ELLong::toHexString(pif->hdr->getZxid())
						+ " expected 0x"
						+ ELLong::toHexString(lastQueued + 1));
				}
				lastQueued = pif->hdr->getZxid();
				packetsNotCommitted.add(pif);
				break;
			}
			case PacketType::COMMIT: {
				if (!writeToTxnLog) {
					sp<PacketInFlight> pif = packetsNotCommitted.peekFirst();
					if (pif->hdr->getZxid() != qp->getZxid()) {
						LOG->warn(EString("Committing ") + qp->getZxid() + ", but next proposal is " + pif->hdr->getZxid());
					} else {
						zk->processTxn(pif->hdr.get(), pif->rec.get());
						packetsNotCommitted.remove();
					}
				} else {
					packetsCommitted.add(qp->getZxid());
				}
				break;
			}
			case PacketType::INFORM: {
				/*
				 * Only observer get this type of packet. We treat this
				 * as receiving PROPOSAL and COMMMIT.
				 */
				sp<PacketInFlight> packet = new PacketInFlight();
				packet->hdr = new TxnHeader();
				packet->rec = SerializeUtils::deserializeTxn(qp->getData(), packet->hdr.get());
				// Log warning message if txn comes out-of-order
				if (packet->hdr->getZxid() != lastQueued + 1) {
					LOG->warn(EString("Got zxid 0x")
							+ ELLong::toHexString(packet->hdr->getZxid())
							+ " expected 0x"
							+ ELLong::toHexString(lastQueued + 1));
				}
				lastQueued = packet->hdr->getZxid();
				if (!writeToTxnLog) {
					// Apply to db directly if we haven't taken the snapshot
					zk->processTxn(packet->hdr.get(), packet->rec.get());
				} else {
					packetsNotCommitted.add(packet);
					packetsCommitted.add(qp->getZxid());
				}
				break;
			}
			case PacketType::UPTODATE: {
				if (isPreZAB1_0) {
					zk->takeSnapshot();
					self->setCurrentEpoch(newEpoch);
				}
				self->cnxnFactory->setZooKeeperServer(zk);
				//@see: break outerLoop;
				goto OUTERLOOP;
			}
			case PacketType::NEWLEADER: { // Getting NEWLEADER here instead of in discovery
				// means this is Zab 1.0
				// Create updatingEpoch file and remove it after current
				// epoch is set. QuorumPeer.loadDataBase() uses this file to
				// detect the case where the server was terminated after
				// taking a snapshot but before setting the current epoch.
				EFile updating(self->getTxnFactory()->getSnapDir(),
									QuorumPeer::UPDATING_EPOCH_FILENAME);
				if (!updating.exists() && !updating.createNewFile()) {
					throw EIOException(__FILE__, __LINE__, ("Failed to create " + updating.toString()).c_str());
				}
				if (snapshotNeeded) {
					zk->takeSnapshot();
				}
				self->setCurrentEpoch(newEpoch);
				if (!updating.remove()) {
					throw EIOException(__FILE__, __LINE__, ("Failed to delete " + updating.toString()).c_str());
				}
				writeToTxnLog = true; //Anything after this needs to go to the transaction log, not applied directly in memory
				isPreZAB1_0 = false;
				writePacket(new QuorumPacket(PacketType::ACK, newLeaderZxid, null, null), true);
				break;
			}
			}
		}
	}}

OUTERLOOP:
	ack->setZxid(ZxidUtils::makeZxid(newEpoch, 0));
	writePacket(ack, true);
	sock->setSoTimeout(self->tickTime * self->syncLimit);
	zk->startup();
	/*
	 * Update the election vote here to ensure that all members of the
	 * ensemble report the same vote to new servers that start up and
	 * send leader election notifications to the ensemble.
	 *
	 * @see https://issues.apache.org/jira/browse/ZOOKEEPER-1732
	 */
	self->updateElectionVote(newEpoch);

	// We need to log the stuff that came in between the snapshot and the uptodate
	sp<FollowerZooKeeperServer> fzk = dynamic_pointer_cast<FollowerZooKeeperServer>(zk);
	if (fzk != null) {
		auto it1 = packetsNotCommitted.iterator();
		while (it1->hasNext()) {
			sp<PacketInFlight> p = it1->next();
			fzk->logRequest(p->hdr, p->rec);
		}
		auto it2 = packetsCommitted.iterator();
		while (it2->hasNext()) {
			llong zxid = it2->next();
			fzk->commit(zxid);
		}
	} else {
		sp<ObserverZooKeeperServer> ozk = dynamic_pointer_cast<ObserverZooKeeperServer>(zk);
		if (ozk != null) {
			// Similar to follower, we need to log requests between the snapshot
			// and UPTODATE
			auto it = packetsNotCommitted.iterator();
			while (it->hasNext()) {
				sp<PacketInFlight> p = it->next();
				llong zxid = packetsCommitted.peekFirst();
				if (p->hdr->getZxid() != zxid) {
					// log warning message if there is no matching commit
					// old leader send outstanding proposal to observer
					LOG->warn("Committing " + ELLong::toHexString(zxid)
							+ ", but next proposal is "
							+ ELLong::toHexString(p->hdr->getZxid()));
					continue;
				}
				packetsCommitted.remove();
				sp<Request> request = new Request(null, p->hdr->getClientId(),
						p->hdr->getCxid(), p->hdr->getType(), null, null);
				request->txn = p->rec;
				request->hdr = p->hdr;
				ozk->commitRequest(request);
			}
		} else {
			// New server type need to handle in-flight packets
			throw EUnsupportedOperationException(__FILE__, __LINE__, "Unknown server type");
		}
	}
}

void Learner::connectToLeader(sp<EInetSocketAddress> addr, EString hostname)
		THROWS3(EIOException, EConnectException, EInterruptedException) {
	sock = new ESocket();
	sock->setSoTimeout(self->tickTime * self->initLimit);
	for (int tries = 0; tries < 5; tries++) {
		try {
			sock->connect(addr.get(), self->tickTime * self->syncLimit);
			sock->setTcpNoDelay(nodelay);
			break;
		} catch (EIOException& e) {
			if (tries == 4) {
				LOG->error("Unexpected exception",e);
				throw e;
			} else {
				LOG->warn(EString("Unexpected exception, tries=")+tries+
						", connecting to " + addr->toString(),e);
				sock = new ESocket();
				sock->setSoTimeout(self->tickTime * self->initLimit);
			}
		}
		EThread::sleep(1000);
	}

	self->authLearner->authenticate(sock, hostname);

	bufferedInput = new EBufferedInputStream(sock->getInputStream());
	leaderIs = EBinaryInputArchive::getArchive(bufferedInput);
	bufferedOutput = new EBufferedOutputStream(sock->getOutputStream());
	leaderOs = EBinaryOutputArchive::getArchive(bufferedOutput);
}

llong Learner::registerWithLeader(int pktType) THROWS(EIOException) {
	/*
	 * Send follower info, including last zxid and sid
	 */
	llong lastLoggedZxid = self->getLastLoggedZxid();
	sp<QuorumPacket> qp = new QuorumPacket();
	qp->setType(pktType);
	qp->setZxid(ZxidUtils::makeZxid(self->getAcceptedEpoch(), 0));

	/*
	 * Add sid to payload
	 */
	sp<LearnerInfo> li = new LearnerInfo(self->getId(), 0x10000);
	EByteArrayOutputStream bsid;// = new ByteArrayOutputStream();
	sp<EBinaryOutputArchive> boa = EBinaryOutputArchive::getArchive(&bsid);
	boa->writeRecord(li.get(), "LearnerInfo");
	qp->setData(bsid.toByteArray());

	writePacket(qp, true);
	readPacket(qp);
	llong newEpoch = ZxidUtils::getEpochFromZxid(qp->getZxid());
	if (qp->getType() == PacketType::LEADERINFO) {
		// we are connected to a 1.0 server so accept the new epoch and read the next packet
		//@see: leaderProtocolVersion = ByteBuffer.wrap(qp.getData()).getInt();
		auto data = qp->getData();
		EIOByteBuffer bb(data->address(), data->length());
		leaderProtocolVersion = bb.getInt();
		sp<EA<byte> > epochBytes = new EA<byte>(4);
		EIOByteBuffer wrappedEpochBytes(epochBytes->address(), 4);
		if (newEpoch > self->getAcceptedEpoch()) {
			wrappedEpochBytes.putInt((int)self->getCurrentEpoch());
			self->setAcceptedEpoch(newEpoch);
		} else if (newEpoch == self->getAcceptedEpoch()) {
			// since we have already acked an epoch equal to the leaders, we cannot ack
			// again, but we still need to send our lastZxid to the leader so that we can
			// sync with it if it does assume leadership of the epoch.
			// the -1 indicates that this reply should not count as an ack for the new epoch
			wrappedEpochBytes.putInt(-1);
		} else {
			throw EIOException(__FILE__, __LINE__,
					(EString("Leaders epoch, ") + newEpoch + " is less than accepted epoch, " + self->getAcceptedEpoch()).c_str());
		}
		sp<QuorumPacket> ackNewEpoch = new QuorumPacket(PacketType::ACKEPOCH, lastLoggedZxid, epochBytes, null);
		writePacket(ackNewEpoch, true);
		return ZxidUtils::makeZxid(newEpoch, 0);
	} else {
		if (newEpoch > self->getAcceptedEpoch()) {
			self->setAcceptedEpoch(newEpoch);
		}
		if (qp->getType() != PacketType::NEWLEADER) {
			LOG->error("First packet should have been NEWLEADER");
			throw EIOException(__FILE__, __LINE__,"First packet should have been NEWLEADER");
		}
		return qp->getZxid();
	}
}

void Learner::revalidate(sp<QuorumPacket> qp) THROWS(EIOException) {
	sp<EA<byte> > data = qp->getData();
	EByteArrayInputStream bis(data->address(), data->length());
	EDataInputStream dis(&bis);
	llong sessionId = dis.readLLong();
	boolean valid = dis.readBoolean();
	sp<ServerCnxn> cnxn = pendingRevalidations.remove(sessionId);
	if (cnxn == null) {
		LOG->warn("Missing session 0x"
				+ ELLong::toHexString(sessionId)
				+ " for validation");
	} else {
		zk->finishSessionInit(cnxn, valid);
	}
	if (LOG->isTraceEnabled()) {
		ZooTrace::logTraceMessage(LOG,
				ZooTrace::SESSION_TRACE_MASK,
				"Session 0x" + ELLong::toHexString(sessionId)
				+ " is valid: " + valid);
	}
}

void Learner::validateSession(sp<ServerCnxn> cnxn, llong clientId, int timeout)
		THROWS(EIOException) {
	LOG->info("Revalidating client: 0x" + ELLong::toHexString(clientId));
	EByteArrayOutputStream baos;// = new ByteArrayOutputStream();
	EDataOutputStream dos(&baos);
	dos.writeLLong(clientId);
	dos.writeInt(timeout);
	dos.close();
	sp<QuorumPacket> qp = new QuorumPacket(PacketType::REVALIDATE, -1, baos
			.toByteArray(), null);
	pendingRevalidations.put(clientId, cnxn);
	if (LOG->isTraceEnabled()) {
		ZooTrace::logTraceMessage(LOG,
								 ZooTrace::SESSION_TRACE_MASK,
								 "To validate session 0x"
								 + ELLong::toHexString(clientId));
	}
	writePacket(qp, true);
}

void Learner::writePacket(sp<QuorumPacket> pp, boolean flush) THROWS(EIOException) {
	SYNCHRONIZED(leaderOs) {
		if (pp != null) {
			leaderOs->writeRecord(pp.get(), "packet");
		}
		if (flush) {
			bufferedOutput->flush();
		}
	}}
}

void Learner::readPacket(sp<QuorumPacket> pp) THROWS(EIOException) {
	SYNCHRONIZED (leaderIs) {
		leaderIs->readRecord(pp.get(), "packet");
	}}
	llong traceMask = ZooTrace::SERVER_PACKET_TRACE_MASK;
	if (pp->getType() == PacketType::PING) {
		traceMask = ZooTrace::SERVER_PING_TRACE_MASK;
	}
	if (LOG->isTraceEnabled()) {
		ZooTrace::logQuorumPacket(LOG, traceMask, 'i', pp);
	}
}

void Learner::request(sp<Request> request) THROWS(EIOException) {
	EByteArrayOutputStream baos;// = new ByteArrayOutputStream();
	EDataOutputStream oa(&baos);
	oa.writeLLong(request->sessionId);
	oa.writeInt(request->cxid);
	oa.writeInt(request->type);
	if (request->request != null) {
		request->request->rewind();
		int len = request->request->remaining();
		EA<byte> b(len);
		request->request->get(b.address(), b.length(), b.length());
		request->request->rewind();
		oa.write(b.address(), b.length());
	}
	oa.close();
	sp<QuorumPacket> qp = new QuorumPacket(PacketType::REQUEST, -1, baos
			.toByteArray(), request->authInfo);
	writePacket(qp, true);
}

QuorumServer* Learner::findLeader() {
	QuorumServer* leaderServer = null;
	// Find the leader by id
	sp<Vote> current = self->getCurrentVote();
	auto iter = self->getView()->values()->iterator();
	while (iter->hasNext()) {
		QuorumServer* s = iter->next();
		if (s->id == current->getId()) {
			// Ensure we have the leader's correct IP address before
			// attempting to connect.
			s->recreateSocketAddresses();
			leaderServer = s;
			break;
		}
	}
	if (leaderServer == null) {
		LOG->warn(EString("Couldn't find the leader with id = ")
				+ current->getId());
	}
	return leaderServer;
}

void Learner::ping(sp<QuorumPacket> qp) THROWS(EIOException) {
	// Send back the ping with our session data
	EByteArrayOutputStream bos;
	EDataOutputStream dos(&bos);
	auto touchTable = zk->getTouchSnapshot();
	auto it = touchTable->entrySet()->iterator();
	while (it->hasNext()) {
		auto entry = it->next();
		dos.writeLLong(entry->getKey());
		dos.writeInt(entry->getValue()->intValue());
	}
	qp->setData(bos.toByteArray());
	writePacket(qp, true);
}

void Learner::shutdown() {
	// set the zookeeper server to null
	self->cnxnFactory->setZooKeeperServer(null);
	// clear all the connections
	self->cnxnFactory->closeAll();
	// shutdown previous zookeeper
	if (zk != null) {
		zk->shutdown();
	}
}

boolean Learner::isRunning() {
   return self->isRunning() && zk->isRunning();
}

} /* namespace ezk */
} /* namespace efc */
