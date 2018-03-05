/*
 * LearnerHandler.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./Leader.hh"
#include "./LearnerInfo.hh"
#include "./QuorumPeer.hh"
#include "./LearnerHandler.hh"

namespace efc {
namespace ezk {

sp<ELogger> LearnerHandler::LOG = ELoggerManager::getLogger("LearnerHandler");

boolean LearnerHandler::SyncLimitCheck::check(llong time) {
	SYNCHRONIZED(this) {
		if (currentTime == 0) {
			return true;
		} else {
			llong msDelay = (time - currentTime) / 1000000;
			return (msDelay < (owner->leader->self->tickTime * owner->leader->self->syncLimit));
		}
	}}
}

LearnerHandler::~LearnerHandler() {
	//
}

LearnerHandler::LearnerHandler(sp<ESocket> sock, sp<EBufferedInputStream> bufferedInput,
                   sp<Leader> leader) :
		   ZooKeeperThread("LearnerHandler-" + sock->getRemoteSocketAddress()->toString()),
		   syncLimitCheck(this), tickOfNextAckDeadline(0), sid(0), version(0x1) {
	proposalOfDeath = new QuorumPacket();
	learnerType = QuorumServer::LearnerType::PARTICIPANT;

	this->sock = sock;
	this->leader = leader;
	this->bufferedInput = bufferedInput;
	try {
		leader->self->authServer->authenticate(sock,
				new EDataInputStream(bufferedInput.get()));
	} catch (EIOException& e) {
		LOG->error(
				"Server failed to authenticate quorum learner, addr: "
						+ sock->getRemoteSocketAddress()->toString()
						+ ", closing connection", e);
		try {
			sock->close();
		} catch (EIOException& ie) {
			LOG->error("Exception while closing socket", ie);
		}
		throw ESaslException(__FILE__, __LINE__, (EString("Authentication failure: ") + e.getMessage()).c_str());
	}
}

void LearnerHandler::sendPackets() {
    llong traceMask = ZooTrace::SERVER_PACKET_TRACE_MASK;
    while (true) {
        try {
            sp<QuorumPacket> p;
            p = queuedPackets.poll();
            if (p == null) {
                bufferedOutput->flush();
                p = queuedPackets.take();
            }

            if (p == proposalOfDeath) {
                // Packet of death!
                break;
            }
            if (p->getType() == PacketType::PING) {
                traceMask = ZooTrace::SERVER_PING_TRACE_MASK;
            }
            if (p->getType() == PacketType::PROPOSAL) {
                syncLimitCheck.updateProposal(p->getZxid(), ESystem::nanoTime());
            }
            if (LOG->isTraceEnabled()) {
                ZooTrace::logQuorumPacket(LOG, traceMask, 'o', p);
            }
            oa->writeRecord(p.get(), "packet");
        } catch (EIOException& e) {
            if (!sock->isClosed()) {
                LOG->warn("Unexpected exception", e);
                try {
                    // this will cause everything to shutdown on
                    // this learner handler and will help notify
                    // the learner/observer instantaneously
                    sock->close();
                } catch(EIOException& ie) {
                    LOG->warn("Error closing socket for handler ", ie);
                }
            }
            break;
        }
    }
}

EString LearnerHandler::packetToString(sp<QuorumPacket> p) {
	EString type;// = null;
	EString mess;// = null;
	sp<ERecord> txn;// = null;

	switch (p->getType()) {
	case PacketType::ACK:
		type = "ACK";
		break;
	case PacketType::COMMIT:
		type = "COMMIT";
		break;
	case PacketType::FOLLOWERINFO:
		type = "FOLLOWERINFO";
		break;
	case PacketType::NEWLEADER:
		type = "NEWLEADER";
		break;
	case PacketType::PING:
		type = "PING";
		break;
	case PacketType::PROPOSAL: {
		type = "PROPOSAL";
		sp<TxnHeader> hdr = new TxnHeader();
		try {
			SerializeUtils::deserializeTxn(p->getData(), hdr.get());
			// mess = "transaction: " + txn.toString();
		} catch (EIOException& e) {
			LOG->warn("Unexpected exception",e);
		}
		break;
	}
	case PacketType::REQUEST:
		type = "REQUEST";
		break;
	case PacketType::REVALIDATE: {
		type = "REVALIDATE";
		auto data = p->getData();
		EByteArrayInputStream bis(data->address(), data->length());
		EDataInputStream dis(&bis);
		try {
			llong id = dis.readLLong();
			mess = EString(" sessionid = ") + id;
		} catch (EIOException& e) {
			LOG->warn("Unexpected exception", e);
		}

		break;
	}
	case PacketType::UPTODATE:
		type = "UPTODATE";
		break;
	default:
		type = EString("UNKNOWN") + p->getType();
		break;
	}
	EString entry;
	if (!type.isEmpty()) {
		entry = type + " " + ELLong::toHexString(p->getZxid()) + " " + mess;
	}
	return entry;
}

void LearnerHandler::run() {
	sp<LearnerHandler> self = shared_from_this();

	try {
		leader->addLearnerHandler(self);
		tickOfNextAckDeadline = leader->self->tick.get()
				+ leader->self->initLimit + leader->self->syncLimit;

		ia = EBinaryInputArchive::getArchive(bufferedInput.get());
		bufferedOutput = new EBufferedOutputStream(sock->getOutputStream());
		oa = EBinaryOutputArchive::getArchive(bufferedOutput.get());

		sp<QuorumPacket> qp = new QuorumPacket();
		ia->readRecord(qp.get(), "packet");
		if(qp->getType() != PacketType::FOLLOWERINFO && qp->getType() != PacketType::OBSERVERINFO){
			LOG->error("First packet " + qp->toString()
					+ " is not FOLLOWERINFO or OBSERVERINFO!");
			return;
		}
		sp<EA<byte> > learnerInfoData = qp->getData();
		if (learnerInfoData != null) {
			sp<EIOByteBuffer> bbsid = EIOByteBuffer::wrap(learnerInfoData->address(), learnerInfoData->length());
			if (learnerInfoData->length() == 8) {
				this->sid = bbsid->getLLong();
			} else {
				sp<LearnerInfo> li = new LearnerInfo();
				ByteBufferInputStream::byteBuffer2Record(bbsid.get(), li.get());
				this->sid = li->getServerid();
				this->version = li->getProtocolVersion();
			}
		} else {
			this->sid = leader->followerCounter.getAndDecrement();
		}

		LOG->info(EString("Follower sid: ") + sid + " : info : "
				+ leader->self->quorumPeers->get(sid));

		if (qp->getType() == PacketType::OBSERVERINFO) {
			  learnerType = QuorumServer::LearnerType::OBSERVER;
		}

		llong lastAcceptedEpoch = ZxidUtils::getEpochFromZxid(qp->getZxid());

		llong peerLastZxid;
		sp<StateSummary> ss = null;
		llong zxid = qp->getZxid();
		llong newEpoch = leader->getEpochToPropose(this->getSid(), lastAcceptedEpoch);

		if (this->getVersion() < 0x10000) {
			// we are going to have to extrapolate the epoch information
			llong epoch = ZxidUtils::getEpochFromZxid(zxid);
			ss = new StateSummary(epoch, zxid);
			// fake the message
			leader->waitForEpochAck(this->getSid(), ss);
		} else {
			sp<EA<byte> > ver = new EA<byte>(4);
			//@see: ByteBuffer.wrap(ver).putInt(0x10000);
			EStream::writeInt(ver->address(), 0x10000);
			sp<QuorumPacket> newEpochPacket = new QuorumPacket(PacketType::LEADERINFO, ZxidUtils::makeZxid(newEpoch, 0), ver, null);
			oa->writeRecord(newEpochPacket.get(), "packet");
			bufferedOutput->flush();
			sp<QuorumPacket> ackEpochPacket = new QuorumPacket();
			ia->readRecord(ackEpochPacket.get(), "packet");
			if (ackEpochPacket->getType() != PacketType::ACKEPOCH) {
				LOG->error(ackEpochPacket->toString()
						+ " is not ACKEPOCH");
				return;
			}
			auto data = ackEpochPacket->getData();
			sp<EIOByteBuffer> bbepoch = EIOByteBuffer::wrap(data->address(), data->length());
			ss = new StateSummary(bbepoch->getInt(), ackEpochPacket->getZxid());
			leader->waitForEpochAck(this->getSid(), ss);
		}
		peerLastZxid = ss->getLastZxid();

		/* the default to send to the follower */
		int packetToSend = PacketType::SNAP;
		llong zxidToSend = 0;
		llong leaderLastZxid = 0;
		/** the packets that the follower needs to get updates from **/
		llong updates = peerLastZxid;

		/* we are sending the diff check if we have proposals in memory to be able to
		 * send a diff to the
		 */
		EReentrantReadWriteLock* lock = leader->zk->getZKDatabase()->getLogLock();
		EReentrantReadWriteLock::ReadLock* rl = lock->readLock();
		SYNCBLOCK(rl) {
			llong maxCommittedLog = leader->zk->getZKDatabase()->getmaxCommittedLog();
			llong minCommittedLog = leader->zk->getZKDatabase()->getminCommittedLog();
			LOG->info(EString("Synchronizing with Follower sid: ") + sid
					+" maxCommittedLog=0x"+ELLong::toHexString(maxCommittedLog)
					+" minCommittedLog=0x"+ELLong::toHexString(minCommittedLog)
					+" peerLastZxid=0x"+ELLong::toHexString(peerLastZxid));

			sp<ELinkedList<sp<Proposal> > > proposals = leader->zk->getZKDatabase()->getCommittedLog();

			if (peerLastZxid == leader->zk->getZKDatabase()->getDataTreeLastProcessedZxid()) {
				// Follower is already sync with us, send empty diff
				LOG->info("leader and follower are in sync, zxid=0x" +
						ELLong::toHexString(peerLastZxid));
				packetToSend = PacketType::DIFF;
				zxidToSend = peerLastZxid;
			} else if (proposals->size() != 0) {
				LOG->debug(EString("proposal size is ") + proposals->size());
				if ((maxCommittedLog >= peerLastZxid)
						&& (minCommittedLog <= peerLastZxid)) {
					LOG->debug("Sending proposals to follower");

					// as we look through proposals, this variable keeps track of previous
					// proposal Id.
					llong prevProposalZxid = minCommittedLog;

					// Keep track of whether we are about to send the first packet.
					// Before sending the first packet, we have to tell the learner
					// whether to expect a trunc or a diff
					boolean firstPacket=true;

					// If we are here, we can use committedLog to sync with
					// follower. Then we only need to decide whether to
					// send trunc or not
					packetToSend = PacketType::DIFF;
					zxidToSend = maxCommittedLog;

					auto iter = proposals->iterator();
					while (iter->hasNext()) {
						sp<Proposal> propose = iter->next();
						// skip the proposals the peer already has
						if (propose->packet->getZxid() <= peerLastZxid) {
							prevProposalZxid = propose->packet->getZxid();
							continue;
						} else {
							// If we are sending the first packet, figure out whether to trunc
							// in case the follower has some proposals that the leader doesn't
							if (firstPacket) {
								firstPacket = false;
								// Does the peer have some proposals that the leader hasn't seen yet
								if (prevProposalZxid < peerLastZxid) {
									// send a trunc message before sending the diff
									packetToSend = PacketType::TRUNC;
									zxidToSend = prevProposalZxid;
									updates = zxidToSend;
								}
							}
							queuePacket(propose->packet);
							sp<QuorumPacket> qcommit = new QuorumPacket(PacketType::COMMIT, propose->packet->getZxid(),
									null, null);
							queuePacket(qcommit);
						}
					}
				} else if (peerLastZxid > maxCommittedLog) {
					LOG->debug("Sending TRUNC to follower zxidToSend=0x{} updates=0x" +
							ELLong::toHexString(maxCommittedLog) +
							ELLong::toHexString(updates));

					packetToSend = PacketType::TRUNC;
					zxidToSend = maxCommittedLog;
					updates = zxidToSend;
				} else {
					LOG->warn("Unhandled proposal scenario");
				}
			} else {
				// just let the state transfer happen
				LOG->debug("proposals is empty");
			}

			LOG->info(EString("Sending ") + PacketType::getPacketType(packetToSend));
			leaderLastZxid = leader->startForwarding(self, updates);

		}}

		sp<QuorumPacket> newLeaderQP = new QuorumPacket(PacketType::NEWLEADER,
				ZxidUtils::makeZxid(newEpoch, 0), null, null);
		if (getVersion() < 0x10000) {
			oa->writeRecord(newLeaderQP.get(), "packet");
		} else {
			queuedPackets.add(newLeaderQP);
		}
		bufferedOutput->flush();
		//Need to set the zxidToSend to the latest zxid
		if (packetToSend == PacketType::SNAP) {
			zxidToSend = leader->zk->getZKDatabase()->getDataTreeLastProcessedZxid();
		}
		oa->writeRecord(new QuorumPacket(packetToSend, zxidToSend, null, null), "packet");
		bufferedOutput->flush();

		/* if we are not truncating or sending a diff just send a snapshot */
		if (packetToSend == PacketType::SNAP) {
			LOG->info("Sending snapshot last zxid of peer is 0x"
					+ ELLong::toHexString(peerLastZxid) + " "
					+ " zxid of leader is 0x"
					+ ELLong::toHexString(leaderLastZxid)
					+ "sent zxid of db as 0x"
					+ ELLong::toHexString(zxidToSend));
			// Dump data to peer
			leader->zk->getZKDatabase()->serializeSnapshot(oa.get());
			oa->writeString("BenWasHere", "signature");
		}
		bufferedOutput->flush();

		// Start sending packets
		/* @see:
		new Thread() {
			public void run() {
				Thread.currentThread().setName(
						"Sender-" + sock.getRemoteSocketAddress());
				try {
					sendPackets();
				} catch (InterruptedException e) {
					LOG->warn("Unexpected interruption",e);
				}
			}
		}.start();
		*/
		class SendThread : public EThread {
		private:
			sp<LearnerHandler> self;
		public:
			SendThread(sp<LearnerHandler> s) : self(s) {
			}
			virtual void run() {
				EThread::currentThread()->setName(
						("Sender-" + self->sock->getRemoteSocketAddress()->toString()).c_str());
				try {
					self->sendPackets();
				} catch (EInterruptedException& e) {
					LOG->warn("Unexpected interruption",e);
				}
			}
		};
		sp<SendThread> thread = new SendThread(self);
		EThread::setDaemon(thread, true); //!!!
		thread->start();

		/*
		 * Have to wait for the first ACK, wait until
		 * the leader is ready, and only then we can
		 * start processing messages.
		 */
		qp = new QuorumPacket();
		ia->readRecord(qp.get(), "packet");
		if(qp->getType() != PacketType::ACK){
			LOG->error("Next packet was supposed to be an ACK");
			return;
		}
		LOG->info(EString("Received NEWLEADER-ACK message from ") + getSid());
		leader->waitForNewLeaderAck(getSid(), qp->getZxid(), getLearnerType());

		syncLimitCheck.start();

		// now that the ack has been processed expect the syncLimit
		sock->setSoTimeout(leader->self->tickTime * leader->self->syncLimit);

		/*
		 * Wait until leader starts up
		 */
		SYNCHRONIZED(leader->zk){
			while(!leader->zk->isRunning() && !this->isInterrupted()){
				leader->zk->wait(20);
			}
		}}
		// Mutation packets will be queued during the serialize,
		// so we need to mark when the peer can actually start
		// using the data
		//
		queuedPackets.add(new QuorumPacket(PacketType::UPTODATE, -1, null, null));

		while (true) {
			qp = new QuorumPacket();
			ia->readRecord(qp.get(), "packet");

			llong traceMask = ZooTrace::SERVER_PACKET_TRACE_MASK;
			if (qp->getType() == PacketType::PING) {
				traceMask = ZooTrace::SERVER_PING_TRACE_MASK;
			}
			if (LOG->isTraceEnabled()) {
				ZooTrace::logQuorumPacket(LOG, traceMask, 'i', qp);
			}
			tickOfNextAckDeadline = leader->self->tick.get() + leader->self->syncLimit;


			sp<EIOByteBuffer> bb;
			llong sessionId;
			int cxid;
			int type;

			switch (qp->getType()) {
			case PacketType::ACK:
				if (this->learnerType == QuorumServer::LearnerType::OBSERVER) {
					if (LOG->isDebugEnabled()) {
						LOG->debug(EString("Received ACK from Observer  ") + this->sid);
					}
				}
				syncLimitCheck.updateAck(qp->getZxid());
				leader->processAck(this->sid, qp->getZxid(), sock->getLocalSocketAddress());
				break;
			case PacketType::PING: {
				// Process the touches
				auto data = qp->getData();
				EByteArrayInputStream bis(data->address(), data->length());
				EDataInputStream dis(&bis);
				while (dis.available() > 0) {
					llong sess = dis.readLLong();
					int to = dis.readInt();
					leader->zk->touch(sess, to);
				}
				break;
			}
			case PacketType::REVALIDATE: {
				auto data = qp->getData();
				EByteArrayInputStream bis(data->address(), data->length());
				EDataInputStream dis(&bis);
				llong id = dis.readLLong();
				int to = dis.readInt();
				EByteArrayOutputStream bos;
				EDataOutputStream dos(&bos);
				dos.writeLLong(id);
				boolean valid = leader->zk->touch(id, to);
				if (valid) {
					try {
						//set the session owner
						// as the follower that
						// owns the session
						leader->zk->setOwner(id, self);
					} catch (SessionExpiredException& e) {
						LOG->error("Somehow session " + ELLong::toHexString(id) + " expired right after being renewed! (impossible)", e);
					}
				}
				if (LOG->isTraceEnabled()) {
					ZooTrace::logTraceMessage(LOG,
							ZooTrace::SESSION_TRACE_MASK,
											 "Session 0x" + ELLong::toHexString(id)
											 + " is valid: "+ valid);
				}
				dos.writeBoolean(valid);
				qp->setData(bos.toByteArray());
				queuedPackets.add(qp);
				break;
			}
			case PacketType::REQUEST: {
				auto data = qp->getData();
				bb = EIOByteBuffer::wrap(data->address(), data->length());
				sessionId = bb->getLLong();
				cxid = bb->getInt();
				type = bb->getInt();
				bb = bb->slice();
				sp<Request> si;
				if(type == ZooDefs::OpCode::sync){
					si = new LearnerSyncRequest(self, sessionId, cxid, type, bb, qp->getAuthinfo());
				} else {
					si = new Request(null, sessionId, cxid, type, bb, qp->getAuthinfo());
				}
				si->setOwner(self);
				leader->zk->submitRequest(si);
				break;
			}
			default:
				LOG->warn("unexpected quorum packet, type: " + packetToString(qp));
				break;
			}
		}
	} catch (EIOException& e) {
		if (sock != null && !sock->isClosed()) {
			LOG->error("Unexpected exception causing shutdown while sock "
					   "still open", e);
			//close the socket to make sure the
			//other side can see it being close
			try {
				sock->close();
			} catch(EIOException& ie) {
				// do nothing
			}
		}
	} catch (EInterruptedException& e) {
		LOG->error("Unexpected exception causing shutdown", e);
	} finally {
		LOG->warn(EString("******* GOODBYE ")
				+ (sock != null ? sock->getRemoteSocketAddress()->toString() : "<null>")
				+ " ********");
		shutdown();
	}
}

void LearnerHandler::shutdown() {
	// Send the packet of death
	try {
		queuedPackets.put(proposalOfDeath);
	} catch (EInterruptedException& e) {
		LOG->warn("Ignoring unexpected exception", e);
	}
	try {
		if (sock != null && !sock->isClosed()) {
			sock->close();
		}
	} catch (EIOException& e) {
		LOG->warn("Ignoring unexpected exception during socket close", e);
	}
	this->interrupt();
	leader->removeLearnerHandler(this);
}

void LearnerHandler::ping() {
	llong id;
	if (syncLimitCheck.check(ESystem::nanoTime())) {
		SYNCHRONIZED(leader) {
			id = leader->lastProposed;
		}}
		sp<QuorumPacket> ping = new QuorumPacket(PacketType::PING, id, null, null);
		queuePacket(ping);
	} else {
		LOG->warn("Closing connection to peer due to transaction timeout.");
		shutdown();
	}
}

boolean LearnerHandler::synced() {
	return isAlive()
	&& leader->self->tick.get() <= tickOfNextAckDeadline;
}

} /* namespace ezk */
} /* namespace efc */
