/*
 * AuthFastLeaderElection.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./AuthFastLeaderElection.hh"
#include "./QuorumPeer.hh"

namespace efc {
namespace ezk {

sp<ELogger> AuthFastLeaderElection::LOG = ELoggerManager::getLogger("AuthFastLeaderElection");

int AuthFastLeaderElection::sequencer = 0;
int AuthFastLeaderElection::maxTag = 0;
int AuthFastLeaderElection::finalizeWait = 100;
int AuthFastLeaderElection::challengeCounter = 0;

//=============================================================================

class Messenger : public ESynchronizeable {
public:
	AuthFastLeaderElection* owner;
	sp<EDatagramSocket> mySocket;
	llong lastProposedLeader;
	llong lastProposedZxid;
	llong lastEpoch;
	EConcurrentHashMap<llong, EBoolean> ackset;
	EConcurrentHashMap<llong, ELLong> challengeMap;
	EConcurrentHashMap<llong, ESemaphore> challengeMutex;
	EConcurrentHashMap<llong, ESemaphore> ackMutex;
	EConcurrentHashMap<EInetSocketAddress, EConcurrentHashMap<llong, ELLong> > addrChallengeMap;

	class WorkerReceiver : virtual public ERunnable {
	public:
		AuthFastLeaderElection* owner;
		sp<EDatagramSocket> mySocket;
		Messenger* myMsg;

		WorkerReceiver(AuthFastLeaderElection* owner, sp<EDatagramSocket> s, Messenger* msg) :
			owner(owner) {
			mySocket = s;
			myMsg = msg;
		}

		boolean saveChallenge(llong tag, llong challenge) {
			sp<ESemaphore> s = myMsg->challengeMutex.get(tag);
			if (s != null) {
					SYNCHRONIZED(myMsg) {
						myMsg->challengeMap.put(tag, new ELLong(challenge));
						myMsg->challengeMutex.remove(tag);
					}}
					s->release();
			} else {
				AuthFastLeaderElection::LOG->error("No challenge mutex object");
			}

			return true;
		}

		virtual void run() {
			EA<byte> responseBytes(48);
			sp<EIOByteBuffer> responseBuffer = EIOByteBuffer::wrap(responseBytes.address(), responseBytes.length());
			sp<EDatagramPacket> responsePacket = new EDatagramPacket(
					responseBytes, responseBytes.length());
			while (true) {
				// Sleeps on receive
				try {
					responseBuffer->clear();
					mySocket->receive(responsePacket.get());
				} catch (EIOException& e) {
					AuthFastLeaderElection::LOG->warn("Ignoring exception receiving", e);
				}
				// Receive new message
				if (responsePacket->getLength() != responseBytes.length()) {
					AuthFastLeaderElection::LOG->warn(EString("Got a short response: ")
							+ responsePacket->getLength() + " "
							+ responsePacket->toString());
					continue;
				}
				responseBuffer->clear();
				int type = responseBuffer->getInt();
				if ((type > 3) || (type < 0)) {
					AuthFastLeaderElection::LOG->warn(EString("Got bad Msg type: ") + type);
					continue;
				}
				llong tag = responseBuffer->getLLong();

				ServerState ackstate = ServerState::LOOKING;
				switch (responseBuffer->getInt()) {
				case 0:
					ackstate = ServerState::LOOKING;
					break;
				case 1:
					ackstate = ServerState::LEADING;
					break;
				case 2:
					ackstate = ServerState::FOLLOWING;
					break;
				}

				sp<Vote> current = owner->self->getCurrentVote();

				switch (type) {
				case 0: {
					// Receive challenge request
					sp<AuthFastLeaderElection::ToSend> c = new AuthFastLeaderElection::ToSend(
							AuthFastLeaderElection::ToSend::mType::challenge, tag,
							current->getId(), current->getZxid(),
							owner->logicalclock, owner->self->getPeerState(),
							responsePacket->getSocketAddress());
					owner->sendqueue->offer(c);
					break;
				}
				case 1: {
					// Receive challenge and store somewhere else
					llong challenge = responseBuffer->getLLong();
					saveChallenge(tag, challenge);
					break;
				}
				case 2: {
					sp<AuthFastLeaderElection::Notification> n = new AuthFastLeaderElection::Notification();
					n->leader = responseBuffer->getLLong();
					n->zxid = responseBuffer->getLLong();
					n->epoch = responseBuffer->getLLong();
					n->state = ackstate;
					n->addr = responsePacket->getSocketAddress();

					if ((myMsg->lastEpoch <= n->epoch)
							&& ((n->zxid > myMsg->lastProposedZxid)
							|| ((n->zxid == myMsg->lastProposedZxid)
							&& (n->leader > myMsg->lastProposedLeader)))) {
						myMsg->lastProposedZxid = n->zxid;
						myMsg->lastProposedLeader = n->leader;
						myMsg->lastEpoch = n->epoch;
					}

					llong recChallenge;
					auto addr = responsePacket->getSocketAddress();
					if (owner->authEnabled) {
						auto tmpMap = myMsg->addrChallengeMap.get(addr.get());
						if(tmpMap != null){
							if (tmpMap->get(tag) != null) {
								recChallenge = responseBuffer->getLLong();

								if (tmpMap->get(tag)->llongValue() == recChallenge) {
									owner->recvqueue->offer(n);

									sp<AuthFastLeaderElection::ToSend> a = new AuthFastLeaderElection::ToSend(
											AuthFastLeaderElection::ToSend::mType::ack,
											tag, current->getId(),
											current->getZxid(),
											owner->logicalclock, owner->self->getPeerState(),
											addr);

									owner->sendqueue->offer(a);
								} else {
									AuthFastLeaderElection::LOG->warn(EString("Incorrect challenge: ")
											+ recChallenge + ", "
											+ myMsg->addrChallengeMap.toString());
								}
							} else {
								AuthFastLeaderElection::LOG->warn(EString("No challenge for host: ") + addr->toString()
										+ " " + tag);
							}
						}
					} else {
						owner->recvqueue->offer(n);

						sp<AuthFastLeaderElection::ToSend> a = new AuthFastLeaderElection::ToSend(
								AuthFastLeaderElection::ToSend::mType::ack, tag,
								current->getId(), current->getZxid(),
								owner->logicalclock, owner->self->getPeerState(),
								responsePacket->getSocketAddress());

						owner->sendqueue->offer(a);
					}
					break;
				}
				// Upon reception of an ack message, remove it from the
				// queue
				case 3: {
					sp<ESemaphore> s = myMsg->ackMutex.get(tag);

					if(s != null)
						s->release();
					else AuthFastLeaderElection::LOG->error("Empty ack semaphore");

					myMsg->ackset.put(tag, new EBoolean(true));

					if (owner->authEnabled) {
						auto tmpMap = myMsg->addrChallengeMap.get(responsePacket
								->getSocketAddress().get());
						if(tmpMap != null) {
							tmpMap->remove(tag);
						} else {
							AuthFastLeaderElection::LOG->warn("No such address in the ensemble configuration " + responsePacket
								->getSocketAddress()->toString());
						}
					}

					if (ackstate != ServerState::LOOKING) {
						sp<AuthFastLeaderElection::Notification> outofsync = new AuthFastLeaderElection::Notification();
						outofsync->leader = responseBuffer->getLLong();
						outofsync->zxid = responseBuffer->getLLong();
						outofsync->epoch = responseBuffer->getLLong();
						outofsync->state = ackstate;
						outofsync->addr = responsePacket->getSocketAddress();

						owner->recvqueue->offer(outofsync);
					}

					break;
				}
				// Default case
				default:
					AuthFastLeaderElection::LOG->warn(EString("Received message of incorrect type ") + type);
					break;
				}
			}
		}
	};

	class WorkerSender : virtual public ERunnable {
	public:
		AuthFastLeaderElection* owner;
		Messenger* myMsg;
		ERandom rand;
		int maxAttempts;
		int ackWait;// = finalizeWait;

		/*
		 * Receives a socket and max number of attempts as input
		 */

		WorkerSender(AuthFastLeaderElection* owner, int attempts, Messenger* msg) :
			owner(owner), myMsg(msg),
			rand(EThread::currentThread()->getId() + TimeUtils::currentElapsedTime()) {
			maxAttempts = attempts;
			ackWait = owner->finalizeWait;
		}

		virtual void run() {
			while (true) {
				try {
					sp<AuthFastLeaderElection::ToSend> m = owner->sendqueue->take();
					process(m);
				} catch (EInterruptedException& e) {
					break;
				}

			}
		}

	private:
		llong genChallenge() {
			EA<byte> buf(8);

			buf[0] = (byte) ((owner->challengeCounter & 0xff000000) >> 24);
			buf[1] = (byte) ((owner->challengeCounter & 0x00ff0000) >> 16);
			buf[2] = (byte) ((owner->challengeCounter & 0x0000ff00) >> 8);
			buf[3] = (byte) ((owner->challengeCounter & 0x000000ff));

			owner->challengeCounter++;
			int secret = rand.nextInt(EInteger::MAX_VALUE);

			buf[4] = (byte) ((secret & 0xff000000) >> 24);
			buf[5] = (byte) ((secret & 0x00ff0000) >> 16);
			buf[6] = (byte) ((secret & 0x0000ff00) >> 8);
			buf[7] = (byte) ((secret & 0x000000ff));

			return (((llong)(buf[0] & 0xFF)) << 56)
					+ (((llong)(buf[1] & 0xFF)) << 48)
					+ (((llong)(buf[2] & 0xFF)) << 40)
					+ (((llong)(buf[3] & 0xFF)) << 32)
					+ (((llong)(buf[4] & 0xFF)) << 24)
					+ (((llong)(buf[5] & 0xFF)) << 16)
					+ (((llong)(buf[6] & 0xFF)) << 8)
					+ ((llong)(buf[7] & 0xFF));
		}

		void process(sp<AuthFastLeaderElection::ToSend> m) {
			int attempts = 0;
			EA<byte> requestBytes(48);
			sp<EDatagramPacket> requestPacket = new EDatagramPacket(requestBytes,
					requestBytes.length());
			sp<EIOByteBuffer> requestBuffer = EIOByteBuffer::wrap(requestBytes.address(), requestBytes.length());

			switch (m->type) {
			case 0: {
				/*
				 * Building challenge request packet to send
				 */
				requestBuffer->clear();
				requestBuffer->putInt(AuthFastLeaderElection::AuthFastLeaderElection::ToSend::mType::crequest);
				requestBuffer->putLLong(m->tag);
				requestBuffer->putInt(m->state);
				byte zeroes[32] = {0};
				requestBuffer->put(zeroes, sizeof(zeroes));

				requestPacket->setLength(48);
				try {
					requestPacket->setSocketAddress(m->addr.get());
				} catch (EIllegalArgumentException& e) {
					// Sun doesn't include the address that causes this
					// exception to be thrown, so we wrap the exception
					// in order to capture this critical detail.
					throw EIllegalArgumentException(__FILE__, __LINE__,
							(EString("Unable to set socket address on packet, msg:")
							+ e.getMessage() + " with addr:" + m->addr->toString()).c_str(),
							&e);
				}

				try {
					if (myMsg->challengeMap.get(m->tag) == null) {
						myMsg->mySocket->send(requestPacket.get());
					}
				} catch (EIOException& e) {
					AuthFastLeaderElection::LOG->warn("Exception while sending challenge: ", e);
				}

				break;
			}
			case 1: {
				/*
				 * Building challenge packet to send
				 */

				llong newChallenge;
				auto tmpMap = myMsg->addrChallengeMap.get(m->addr.get());
				if(tmpMap != null){
					sp<ELLong> tmpLong = tmpMap->get(m->tag);
					if (tmpLong != null) {
						newChallenge = tmpLong->llongValue();
					} else {
						newChallenge = genChallenge();
					}

					tmpMap->put(m->tag, new ELLong(newChallenge));

					requestBuffer->clear();
					requestBuffer->putInt(AuthFastLeaderElection::AuthFastLeaderElection::ToSend::mType::challenge);
					requestBuffer->putLLong(m->tag);
					requestBuffer->putInt(m->state);
					requestBuffer->putLLong(newChallenge);
					byte zeroes[24] = {0};
					requestBuffer->put(zeroes, sizeof(zeroes));

					requestPacket->setLength(48);
					try {
						requestPacket->setSocketAddress(m->addr.get());
					} catch (EIllegalArgumentException& e) {
						// Sun doesn't include the address that causes this
						// exception to be thrown, so we wrap the exception
						// in order to capture this critical detail.
						throw EIllegalArgumentException(__FILE__, __LINE__,
								(EString("Unable to set socket address on packet, msg:")
								+ e.getMessage() + " with addr:" + m->addr->toString()).c_str(),
								&e);
					}


					try {
						myMsg->mySocket->send(requestPacket.get());
					} catch (EIOException& e) {
						AuthFastLeaderElection::LOG->warn("Exception while sending challenge: ", e);
					}
				} else {
					AuthFastLeaderElection::LOG->error("Address is not in the configuration: " + m->addr->toString());
				}

				break;
			}
			case 2: {
				/*
				 * Building notification packet to send
				 */

				requestBuffer->clear();
				requestBuffer->putInt(m->type);
				requestBuffer->putLLong(m->tag);
				requestBuffer->putInt(m->state);
				requestBuffer->putLLong(m->leader);
				requestBuffer->putLLong(m->zxid);
				requestBuffer->putLLong(m->epoch);
				byte zeroes[8] = {0};
				requestBuffer->put(zeroes, sizeof(zeroes));

				requestPacket->setLength(48);
				try {
					requestPacket->setSocketAddress(m->addr.get());
				} catch (EIllegalArgumentException& e) {
					// Sun doesn't include the address that causes this
					// exception to be thrown, so we wrap the exception
					// in order to capture this critical detail.
					throw EIllegalArgumentException(__FILE__, __LINE__,
							(EString("Unable to set socket address on packet, msg:")
							+ e.getMessage() + " with addr:" + m->addr->toString()).c_str(),
							&e);
				}

				boolean myChallenge = false;
				boolean myAck = false;

				while (attempts < maxAttempts) {
					try {
						/*
						 * Try to obtain a challenge only if does not have
						 * one yet
						 */

						if (!myChallenge && owner->authEnabled) {
							sp<AuthFastLeaderElection::ToSend> crequest = new AuthFastLeaderElection::ToSend(
									AuthFastLeaderElection::ToSend::mType::crequest, m->tag, m->leader,
									m->zxid, m->epoch,
									ServerState::LOOKING, m->addr);
							owner->sendqueue->offer(crequest);

							try {
								double timeout = ackWait
										* EMath::pow(2, attempts);

								sp<ESemaphore> s = new ESemaphore(0);
								SYNCHRONIZED(myMsg) {
									myMsg->challengeMutex.put(m->tag, s);
									s->tryAcquire((llong) timeout, ETimeUnit::MILLISECONDS);
									myChallenge = myMsg->challengeMap.containsKey(m->tag);
								}}
							} catch (EInterruptedException& e) {
								AuthFastLeaderElection::LOG->warn("Challenge request exception: ", e);
							}
						}

						/*
						 * If don't have challenge yet, skip sending
						 * notification
						 */

						if (owner->authEnabled && !myChallenge) {
							attempts++;
							continue;
						}

						if (owner->authEnabled) {
							requestBuffer->position(40);
							sp<ELLong> tmpLong = myMsg->challengeMap.get(m->tag);
							if(tmpLong != null){
								requestBuffer->putLLong(tmpLong->llongValue());
							} else {
								AuthFastLeaderElection::LOG->warn(EString("No challenge with tag: ") + m->tag);
							}
						}
						myMsg->mySocket->send(requestPacket.get());
						try {
							sp<ESemaphore> s = new ESemaphore(0);
							double timeout = ackWait
									* EMath::pow(10, attempts);
							myMsg->ackMutex.put(m->tag, s);
							s->tryAcquire((int) timeout, ETimeUnit::MILLISECONDS);
						} catch (EInterruptedException& e) {
							AuthFastLeaderElection::LOG->warn("Ack exception: ", e);
						}

						if(myMsg->ackset.remove(m->tag)->booleanValue()){
							myAck = true;
						}

					} catch (EIOException& e) {
						AuthFastLeaderElection::LOG->warn("Sending exception: ", e);
						/*
						 * Do nothing, just try again
						 */
					}
					if (myAck) {
						/*
						 * Received ack successfully, so return
						 */
						myMsg->challengeMap.remove(m->tag);

						return;
					} else
						attempts++;
				}
				/*
				 * Return message to queue for another attempt later if
				 * epoch hasn't changed.
				 */
				if (m->epoch == owner->logicalclock) {
					myMsg->challengeMap.remove(m->tag);
					owner->sendqueue->offer(m);
				}
				break;
			}
			case 3: {
				requestBuffer->clear();
				requestBuffer->putInt(m->type);
				requestBuffer->putLLong(m->tag);
				requestBuffer->putInt(m->state);
				requestBuffer->putLLong(m->leader);
				requestBuffer->putLLong(m->zxid);
				requestBuffer->putLLong(m->epoch);

				requestPacket->setLength(48);
				try {
					requestPacket->setSocketAddress(m->addr.get());
				} catch (EIllegalArgumentException& e) {
					// Sun doesn't include the address that causes this
					// exception to be thrown, so we wrap the exception
					// in order to capture this critical detail.
					throw EIllegalArgumentException(__FILE__, __LINE__,
							(EString("Unable to set socket address on packet, msg:")
							+ e.getMessage() + " with addr:" + m->addr->toString()).c_str(),
							&e);
				}

				try {
					myMsg->mySocket->send(requestPacket.get());
				} catch (EIOException& e) {
					AuthFastLeaderElection::LOG->warn("Exception while sending ack: ", e);
				}
				break;
			}
			}
		}
	};

	boolean queueEmpty() {
		return (owner->sendqueue->isEmpty() || ackset.isEmpty() || owner->recvqueue
				->isEmpty());
	}

	Messenger(AuthFastLeaderElection* owner, int threads, sp<EDatagramSocket> s) {
		this->owner = owner;
		mySocket = s;
		lastProposedLeader = 0;
		lastProposedZxid = 0;
		lastEpoch = 0;

		for (int i = 0; i < threads; ++i) {
			EThread* t = new ZooKeeperThread(new WorkerSender(owner, 3, this),
					EString("WorkerSender Thread: ") + (i + 1));
            EThread::setDaemon(t, true);
			t->start();
		}

        auto vv = owner->self->getVotingView();
		auto it = vv->values()->iterator();
		while (it->hasNext()) {
			auto server = it->next();
			sp<EInetSocketAddress> saddr = new EInetSocketAddress(server->addr
					->getAddress(), owner->port);
			addrChallengeMap.put(saddr, new EConcurrentHashMap<llong, ELLong>());
		}

		EThread* t = new ZooKeeperThread(new WorkerReceiver(owner, s, this),
				"WorkerReceiver-" + s->getRemoteSocketAddress()->toString());
		EThread::setDaemon(t, true); //!!!
		t->start();
	}
};

//=============================================================================

AuthFastLeaderElection::~AuthFastLeaderElection() {
	delete messenger;
}

AuthFastLeaderElection::AuthFastLeaderElection(QuorumPeer* self, boolean auth) :
		port(0), logicalclock(0), proposedLeader(0), proposedZxid(0) {
	this->authEnabled = auth;
	this->messenger = null;
	starter(self);
}

AuthFastLeaderElection::AuthFastLeaderElection(QuorumPeer* self) :
		port(0), logicalclock(0), proposedLeader(0), proposedZxid(0) {
	this->authEnabled = false;
	this->messenger = null;
	starter(self);
}

void AuthFastLeaderElection::starter(QuorumPeer* self) {
	this->self = self;
	port = self->getVotingView()->get(self->getId())->electionAddr->getPort();
	proposedLeader = -1;
	proposedZxid = -1;

	try {
		mySocket = new EDatagramSocket(port);
		// mySocket.setSoTimeout(20000);
	} catch (ESocketException& e1) {
		e1.printStackTrace();
		throw ERuntimeException(__FILE__, __LINE__);
	}
	sendqueue = new ELinkedBlockingQueue<ToSend>(2 * self->getVotingView()->size());
	recvqueue = new ELinkedBlockingQueue<Notification>(2 * self->getVotingView()->size());
	messenger = new Messenger(this, self->getVotingView()->size() * 2, mySocket);
}

void AuthFastLeaderElection::sendNotifications() {
	auto iter = self->getView()->values()->iterator();
	while (iter->hasNext()) {
		auto server = iter->next();
		sp<ToSend> notmsg = new ToSend(ToSend::mType::notification,
				AuthFastLeaderElection::sequencer++, proposedLeader,
				proposedZxid, logicalclock, ServerState::LOOKING,
				self->getView()->get(server->id)->electionAddr);

		sendqueue->offer(notmsg);
	}
}

boolean AuthFastLeaderElection::termPredicate(EHashMap<sp<EInetSocketAddress>, sp<Vote> >& votes,
			llong l, llong zxid) {
	auto votesCast = votes.values();
	int count = 0;
	/*
	 * First make the views consistent. Sometimes peers will have different
	 * zxids for a server depending on timing.
	 */
	auto it = votesCast->iterator();
	while (it->hasNext()) {
		sp<Vote> v = it->next();
		if ((v->getId() == l) && (v->getZxid() == zxid))
			count++;
	}

	if (count > (self->getVotingView()->size() / 2))
		return true;
	else
		return false;
}

sp<Vote> AuthFastLeaderElection::lookForLeader() THROWS(EInterruptedException) {
	EHashMap<sp<EInetSocketAddress>, sp<Vote> > recvset;;
	EHashMap<sp<EInetSocketAddress>, sp<Vote> > outofelection;

	logicalclock++;

	proposedLeader = self->getId();
	proposedZxid = self->getLastLoggedZxid();

	LOG->info("Election tally");
	sendNotifications();

	/*
	 * Loop in which we exchange notifications until we find a leader
	 */

	while (self->getPeerState() == ServerState::LOOKING) {
		/*
		 * Remove next notification from queue, times out after 2 times
		 * the termination time
		 */
		sp<Notification> n = recvqueue->poll(2 * finalizeWait,
				ETimeUnit::MILLISECONDS);

		/*
		 * Sends more notifications if haven't received enough.
		 * Otherwise processes new notification.
		 */
		if (n == null) {
			if (((!outofelection.isEmpty()) || (recvset.size() > 1)))
				sendNotifications();
		} else
			switch (n->state) {
			case LOOKING:
				if (n->epoch > logicalclock) {
					logicalclock = n->epoch;
					recvset.clear();
					if (totalOrderPredicate(n->leader, n->zxid)) {
						proposedLeader = n->leader;
						proposedZxid = n->zxid;
					}
					sendNotifications();
				} else if (n->epoch < logicalclock) {
					break;
				} else if (totalOrderPredicate(n->leader, n->zxid)) {
					proposedLeader = n->leader;
					proposedZxid = n->zxid;

					sendNotifications();
				}

				recvset.put(n->addr, new Vote(n->leader, n->zxid));

				// If have received from all nodes, then terminate
				if (self->getVotingView()->size() == recvset.size()) {
					self->setPeerState((proposedLeader == self->getId()) ?
							ServerState::LEADING: ServerState::FOLLOWING);
					// if (self->state == ServerState.FOLLOWING) {
					// Thread.sleep(100);
					// }
					leaveInstance();
					return new Vote(proposedLeader, proposedZxid);

				} else if (termPredicate(recvset, proposedLeader,
						proposedZxid)) {
					// Otherwise, wait for a fixed amount of time
					LOG->info("Passed predicate");
					EThread::sleep(finalizeWait);

					// Notification probe = recvqueue->peek();

					// Verify if there is any change in the proposed leader
					while ((!recvqueue->isEmpty())
							&& !totalOrderPredicate(
									recvqueue->peek()->leader, recvqueue
											->peek()->zxid)) {
						recvqueue->poll();
					}
					if (recvqueue->isEmpty()) {
						// LOG->warn("Proposed leader: " +
						// proposedLeader);
						self->setPeerState(
								(proposedLeader == self->getId()) ?
								 ServerState::LEADING :
								 ServerState::FOLLOWING);

						leaveInstance();
						return new Vote(proposedLeader, proposedZxid);
					}
				}
				break;
			case LEADING:
				outofelection.put(n->addr, new Vote(n->leader, n->zxid));

				if (termPredicate(outofelection, n->leader, n->zxid)) {

					self->setPeerState((n->leader == self->getId()) ?
							ServerState::LEADING: ServerState::FOLLOWING);

					leaveInstance();
					return new Vote(n->leader, n->zxid);
				}
				break;
			case FOLLOWING:
				outofelection.put(n->addr, new Vote(n->leader, n->zxid));

				if (termPredicate(outofelection, n->leader, n->zxid)) {

					self->setPeerState((n->leader == self->getId()) ?
							ServerState::LEADING: ServerState::FOLLOWING);

					leaveInstance();
					return new Vote(n->leader, n->zxid);
				}
				break;
			default:
				break;
			}
	}

	return null;
}

} /* namespace ezk */
} /* namespace efc */
