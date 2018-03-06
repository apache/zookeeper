/*
 * FastLeaderElection.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./FastLeaderElection.hh"
#include "./QuorumPeer.hh"

namespace efc {
namespace ezk {

FastLeaderElection::Messenger::Messenger(FastLeaderElection* owner,
		sp<QuorumCnxManager> manager) :
		owner(owner) {
	this->ws = new WorkerSender(owner, manager);

	wsThread = new EThread(this->ws,
			(EString("WorkerSender[myid=") + owner->self->getId() + "]").c_str());
	EThread::setDaemon(wsThread, true);
	wsThread->start();

	this->wr = new WorkerReceiver(owner, manager);

	wrThread = new EThread(this->wr,
			(EString("WorkerReceiver[myid=") + owner->self->getId() + "]").c_str());
	EThread::setDaemon(wrThread, true);
	wrThread->start();
}

void FastLeaderElection::Messenger::WorkerReceiver::run() {
    sp<QuorumCnxManager::Message> response;
    while (!stop) {
        // Sleeps on receive
        try{
            response = manager->pollRecvQueue(3000, ETimeUnit::MILLISECONDS);
            if(response == null) continue;

            /*
             * If it is from an observer, respond right away.
             * Note that the following predicate assumes that
             * if a server is not a follower, then it must be
             * an observer. If we ever have any other type of
             * learner in the future, we'll have to change the
             * way we check for observers.
             */
            auto vv = owner->self->getVotingView();
            if(!vv->containsKey(response->sid)){
                sp<Vote> current = owner->self->getCurrentVote();
                sp<ToSend> notmsg = new ToSend(ToSend::mType::notification,
                        current->getId(),
                        current->getZxid(),
                        owner->logicalclock.get(),
                        owner->self->getPeerState(),
                        response->sid,
                        current->getPeerEpoch());

                owner->sendqueue.offer(notmsg);
            } else {
                // Receive new message
                if (LOG->isDebugEnabled()) {
                    LOG->debug(EString("Receive new notification message. My id = ")
                            + owner->self->getId());
                }

                /*
                 * We check for 28 bytes for backward compatibility
                 */
                if (response->buffer->capacity() < 28) {
                	LOG->error(EString("Got a short response: ")
                            + response->buffer->capacity());
                    continue;
                }
                boolean backCompatibility = (response->buffer->capacity() == 28);
                response->buffer->clear();

                // Instantiate Notification and set its attributes
                sp<Notification> n = new Notification();

                // State of peer that sent this message
                ServerState ackstate = ServerState::LOOKING;
                switch (response->buffer->getInt()) {
                case 0:
                    ackstate = ServerState::LOOKING;
                    break;
                case 1:
                    ackstate = ServerState::FOLLOWING;
                    break;
                case 2:
                    ackstate = ServerState::LEADING;
                    break;
                case 3:
                    ackstate = ServerState::OBSERVING;
                    break;
                default:
                    continue;
                }

                n->leader = response->buffer->getLLong();
                n->zxid = response->buffer->getLLong();
                n->electionEpoch = response->buffer->getLLong();
                n->state = ackstate;
                n->sid = response->sid;
                if(!backCompatibility){
                	n->peerEpoch = response->buffer->getLLong();
                } else {
                    if(LOG->isInfoEnabled()){
                    	LOG->info(EString("Backward compatibility mode, server id=") + n->sid);
                    }
                    n->peerEpoch = ZxidUtils::getEpochFromZxid(n->zxid);
                }

                /*
                 * Version added in 3.4.6
                 */

                n->version = (response->buffer->remaining() >= 4) ?
                		response->buffer->getInt() : 0x0;

                /*
                 * Print notification info
                 */
                if(LOG->isInfoEnabled()){
                    owner->printNotification(n);
                }

                /*
                 * If this server is looking, then send proposed leader
                 */

                if(owner->self->getPeerState() == ServerState::LOOKING){
                    owner->recvqueue.offer(n);

                    /*
                     * Send a notification back if the peer that sent this
                     * message is also looking and its logical clock is
                     * lagging behind.
                     */
                    if((ackstate == ServerState::LOOKING)
                            && (n->electionEpoch < owner->logicalclock.get())){
                    	sp<Vote> v = owner->getVote();
                        sp<ToSend> notmsg = new ToSend(ToSend::mType::notification,
                                v->getId(),
                                v->getZxid(),
                                owner->logicalclock.get(),
                                owner->self->getPeerState(),
                                response->sid,
                                v->getPeerEpoch());
                        owner->sendqueue.offer(notmsg);
                    }
                } else {
                    /*
                     * If this server is not looking, but the one that sent the ack
                     * is looking, then send back what it believes to be the leader.
                     */
                    sp<Vote> current = owner->self->getCurrentVote();
                    if(ackstate == ServerState::LOOKING){
                        if(LOG->isDebugEnabled()){
                        	LOG->debug(EString("Sending new notification. My id =  ") +
                        			owner->self->getId() + " recipient=" +
                                    response->sid + " zxid=0x" +
                                    ELLong::toHexString(current->getZxid()) +
                                    " leader=" + current->getId());
                        }

                        sp<ToSend> notmsg;
                        if(n->version > 0x0) {
                            notmsg = new ToSend(
                            		ToSend::mType::notification,
                                    current->getId(),
                                    current->getZxid(),
                                    current->getElectionEpoch(),
                                    owner->self->getPeerState(),
                                    response->sid,
                                    current->getPeerEpoch());

                        } else {
                            sp<Vote> bcVote = owner->self->getBCVote();
                            notmsg = new ToSend(
                            		ToSend::mType::notification,
                                    bcVote->getId(),
                                    bcVote->getZxid(),
                                    bcVote->getElectionEpoch(),
                                    owner->self->getPeerState(),
                                    response->sid,
                                    bcVote->getPeerEpoch());
                        }
                        owner->sendqueue.offer(notmsg);
                    }
                }
            }
        } catch (EInterruptedException& e) {
            ESystem::out->println("Interrupted Exception while waiting for new message");
        }
    }
    LOG->info("WorkerReceiver is down");
}

void FastLeaderElection::Messenger::WorkerSender::run() {
    while (!stop) {
        try {
            sp<ToSend> m = owner->sendqueue.poll(3000, ETimeUnit::MILLISECONDS);
            if(m == null) continue;

            process(m);
        } catch (EInterruptedException& e) {
            break;
        }
    }
    LOG->info("WorkerSender is down");
}

//=============================================================================

sp<ELogger> FastLeaderElection::LOG = ELoggerManager::getLogger("FastLeaderElection");

FastLeaderElection::~FastLeaderElection() {
	//
}

FastLeaderElection::FastLeaderElection(QuorumPeer* self, sp<QuorumCnxManager> manager) :
	proposedLeader(0), proposedZxid(0), proposedEpoch(0), stop(false) {
	this->manager = manager;
	starter(self, manager);
}

void FastLeaderElection::shutdown(){
	stop = true;
	LOG->debug("Shutting down connection manager");
	manager->halt();
	LOG->debug("Shutting down messenger");
	messenger->halt();
	LOG->debug("FLE is down");
}

boolean FastLeaderElection::totalOrderPredicate(llong newId, llong newZxid,
		llong newEpoch, llong curId, llong curZxid, llong curEpoch) {
	LOG->debug(EString("id: ") + newId + ", proposed id: " + curId + ", zxid: 0x" +
			ELLong::toHexString(newZxid) + ", proposed zxid: 0x" + ELLong::toHexString(curZxid));
	if(self->getQuorumVerifier()->getWeight(newId) == 0){
		return false;
	}

	/*
	 * We return true if one of the following three cases hold:
	 * 1- New epoch is higher
	 * 2- New epoch is the same as current epoch, but new zxid is higher
	 * 3- New epoch is the same as current epoch, new zxid is the same
	 *  as current zxid, but server id is higher.
	 */

	return ((newEpoch > curEpoch) ||
			((newEpoch == curEpoch) &&
			((newZxid > curZxid) || ((newZxid == curZxid) && (newId > curId)))));
}

boolean FastLeaderElection::checkLeader(
		EHashMap<llong, sp<Vote> >& votes,
		llong leader,
		llong electionEpoch){

	boolean predicate = true;

	/*
	 * If everyone else thinks I'm the leader, I must be the leader.
	 * The other two checks are just for the case in which I'm not the
	 * leader. If I'm not the leader and I haven't received a message
	 * from leader stating that it is leading, then predicate is false.
	 */

	if(leader != self->getId()){
		if(votes.get(leader) == null) predicate = false;
		else if(votes.get(leader)->getState() != ServerState::LEADING) predicate = false;
	} else if(logicalclock.get() != electionEpoch) {
		predicate = false;
	}

	return predicate;
}

boolean FastLeaderElection::ooePredicate(EHashMap<llong, sp<Vote> >& recv,
		EHashMap<llong, sp<Vote> >& ooe, sp<Notification> n) {
	return (termPredicate(recv, new Vote(n->version,
			n->leader,
			n->zxid,
			n->electionEpoch,
			n->peerEpoch,
			n->state))
			&& checkLeader(ooe, n->leader, n->electionEpoch));

}

void FastLeaderElection::updateProposal(llong leader, llong zxid, llong epoch){
	SYNCHRONIZED(this) {
		if(LOG->isDebugEnabled()){
			LOG->debug(EString("Updating proposal: ") + leader + " (newleader), 0x"
					+ ELLong::toHexString(zxid) + " (newzxid), " + proposedLeader
					+ " (oldleader), 0x" + ELLong::toHexString(proposedZxid) + " (oldzxid)");
		}
		proposedLeader = leader;
		proposedZxid = zxid;
		proposedEpoch = epoch;
	}}
}

sp<Vote> FastLeaderElection::getVote(){
	SYNCHRONIZED(this) {
		return new Vote(proposedLeader, proposedZxid, proposedEpoch);
	}}
}

sp<Vote> FastLeaderElection::lookForLeader() THROWS(EInterruptedException) {
    if (self->start_fle == 0) {
    	self->start_fle = TimeUtils::currentElapsedTime();
    }

	EHashMap<llong, sp<Vote> > recvset;// = new HashMap<Long, Vote>();

	EHashMap<llong, sp<Vote> > outofelection;// = new HashMap<Long, Vote>();

	int notTimeout = finalizeWait;

	SYNCHRONIZED(this) {
		logicalclock.incrementAndGet();
		updateProposal(getInitId(), getInitLastLoggedZxid(), getPeerEpoch());
	}}

	LOG->info(EString("New election. My id =  ") + self->getId() +
			", proposed zxid=0x" + ELLong::toHexString(proposedZxid));
	sendNotifications();

	/*
	 * Loop in which we exchange notifications until we find a leader
	 */

	while ((self->getPeerState() == ServerState::LOOKING) && (!stop)){
		/*
		 * Remove next notification from queue, times out after 2 times
		 * the termination time
		 */
		sp<Notification> n = recvqueue.poll(notTimeout, ETimeUnit::MILLISECONDS);

		/*
		 * Sends more notifications if haven't received enough.
		 * Otherwise processes new notification.
		 */
		if(n == null){
			if(manager->haveDelivered()){
				sendNotifications();
			} else {
				manager->connectAll();
			}

			/*
			 * Exponential backoff
			 */
			int tmpTimeOut = notTimeout*2;
			notTimeout = (tmpTimeOut < maxNotificationInterval?
					tmpTimeOut : maxNotificationInterval);
			LOG->info(EString("Notification time out: ") + notTimeout);
		}
		else if(self->getVotingView()->containsKey(n->sid)) {
			/*
			 * Only proceed if the vote comes from a replica in the
			 * voting view.
			 */
			switch (n->state) {
			case LOOKING:
				// If notification > current, replace and send messages out
				if (n->electionEpoch > logicalclock.get()) {
					logicalclock.set(n->electionEpoch);
					recvset.clear();
					if(totalOrderPredicate(n->leader, n->zxid, n->peerEpoch,
							getInitId(), getInitLastLoggedZxid(), getPeerEpoch())) {
						updateProposal(n->leader, n->zxid, n->peerEpoch);
					} else {
						updateProposal(getInitId(),
								getInitLastLoggedZxid(),
								getPeerEpoch());
					}
					sendNotifications();
				} else if (n->electionEpoch < logicalclock.get()) {
					if(LOG->isDebugEnabled()){
						LOG->debug("Notification election epoch is smaller than logicalclock. n.electionEpoch = 0x"
								+ ELLong::toHexString(n->electionEpoch)
								+ ", logicalclock=0x" + ELLong::toHexString(logicalclock.get()));
					}
					break;
				} else if (totalOrderPredicate(n->leader, n->zxid, n->peerEpoch,
						proposedLeader, proposedZxid, proposedEpoch)) {
					updateProposal(n->leader, n->zxid, n->peerEpoch);
					sendNotifications();
				}

				if(LOG->isDebugEnabled()){
					LOG->debug(EString("Adding vote: from=") + n->sid +
							", proposed leader=" + n->leader +
							", proposed zxid=0x" + ELLong::toHexString(n->zxid) +
							", proposed election epoch=0x" + ELLong::toHexString(n->electionEpoch));
				}

				recvset.put(n->sid, new Vote(n->leader, n->zxid, n->electionEpoch, n->peerEpoch));

				if (termPredicate(recvset,
						new Vote(proposedLeader, proposedZxid,
								logicalclock.get(), proposedEpoch))) {

					// Verify if there is any change in the proposed leader
					while((n = recvqueue.poll(finalizeWait,
							ETimeUnit::MILLISECONDS)) != null){
						if(totalOrderPredicate(n->leader, n->zxid, n->peerEpoch,
								proposedLeader, proposedZxid, proposedEpoch)){
							recvqueue.put(n);
							break;
						}
					}

					/*
					 * This predicate is true once we don't read any new
					 * relevant message from the reception queue
					 */
					if (n == null) {
						self->setPeerState((proposedLeader == self->getId()) ?
								ServerState::LEADING: learningState());

						sp<Vote> endVote = new Vote(proposedLeader,
												proposedZxid,
												logicalclock.get(),
												proposedEpoch);
						leaveInstance(endVote);
						return endVote;
					}
				}
				break;
			case OBSERVING:
				LOG->debug(EString("Notification from observer: ") + n->sid);
				break;
			case FOLLOWING:
			case LEADING:
				/*
				 * Consider all notifications from the same epoch
				 * together.
				 */
				if(n->electionEpoch == logicalclock.get()){
					recvset.put(n->sid, new Vote(n->leader,
							n->zxid,
							n->electionEpoch,
							n->peerEpoch));

					if(ooePredicate(recvset, outofelection, n)) {
						self->setPeerState((n->leader == self->getId()) ?
								ServerState::LEADING: learningState());

						sp<Vote> endVote = new Vote(n->leader,
								n->zxid,
								n->electionEpoch,
								n->peerEpoch);
						leaveInstance(endVote);
						return endVote;
					}
				}

				/*
				 * Before joining an established ensemble, verify
				 * a majority is following the same leader.
				 */
				outofelection.put(n->sid, new Vote(n->version,
						n->leader,
						n->zxid,
						n->electionEpoch,
						n->peerEpoch,
						n->state));

				if(ooePredicate(outofelection, outofelection, n)) {
					SYNCHRONIZED(this){
						logicalclock.set(n->electionEpoch);
						self->setPeerState((n->leader == self->getId()) ?
								ServerState::LEADING: learningState());
					}}
					sp<Vote> endVote = new Vote(n->leader,
							n->zxid,
							n->electionEpoch,
							n->peerEpoch);
					leaveInstance(endVote);
					return endVote;
				}
				break;
			default:
				LOG->warn(EString::formatOf("Notification state unrecognized: %s (n.state), %ld (n.sid)",
						getStateName(n->state), n->sid));
				break;
			}
		} else {
			LOG->warn(EString("Ignoring notification from non-cluster member ") + n->sid);
		}
	}
	return null;
}

boolean FastLeaderElection::termPredicate(
		EHashMap<llong, sp<Vote> >& votes,
		sp<Vote> vote) {

	EHashSet<llong> set;// = new HashSet<Long>();

	/*
	 * First make the views consistent. Sometimes peers will have
	 * different zxids for a server depending on timing.
	 */
	auto iter = votes.entrySet()->iterator();
	while (iter->hasNext()) {
		auto entry = iter->next();
		if (vote->equals(entry->getValue().get())){
			set.add(entry->getKey());
		}
	}

	return self->getQuorumVerifier()->containsQuorum(&set);
}

void FastLeaderElection::starter(QuorumPeer* self, sp<QuorumCnxManager> manager) {
	this->self = self;
	proposedLeader = -1;
	proposedZxid = -1;

	//sendqueue = new LinkedBlockingQueue<ToSend>();
	//recvqueue = new LinkedBlockingQueue<Notification>();
	this->messenger = new Messenger(this, manager);
}

void FastLeaderElection::leaveInstance(sp<Vote> v) {
	if(LOG->isDebugEnabled()){
		LOG->debug(EString("About to leave FLE instance: leader=")
			+ v->getId() + ", zxid=0x" +
			ELLong::toHexString(v->getZxid()) + ", my id=" + self->getId()
			+ ", my state=" + self->getPeerStateName());
	}
	recvqueue.clear();
}

void FastLeaderElection::sendNotifications() {
	auto vv = self->getVotingView();
	auto iter = vv->values()->iterator();
	while (iter->hasNext()) {
		QuorumServer* server = iter->next();
    	llong sid = server->id;

        sp<ToSend> notmsg = new ToSend(ToSend::mType::notification,
                proposedLeader,
                proposedZxid,
                logicalclock.get(),
                ServerState::LOOKING,
                sid,
                proposedEpoch);
        if(LOG->isDebugEnabled()){
        	LOG->debug(EString("Sending Notification: ") + proposedLeader + " (n.leader), 0x"  +
            		ELLong::toHexString(proposedZxid) + " (n.zxid), 0x" + ELLong::toHexString(logicalclock.get())  +
                  " (n.round), " + sid + " (recipient), " + self->getId() +
                  " (myid), 0x" + ELLong::toHexString(proposedEpoch) + " (n.peerEpoch)");
        }
        sendqueue.offer(notmsg);
    }
}

void FastLeaderElection::printNotification(sp<Notification> n){
	LOG->info("Notification: " + n->toString()
			+ self->getPeerStateName() + " (my state)");
}

ServerState FastLeaderElection::learningState(){
	if(self->getLearnerType() == QuorumServer::LearnerType::PARTICIPANT){
		LOG->debug(EString("I'm a participant: ") + self->getId());
		return ServerState::FOLLOWING;
	}
	else{
		LOG->debug(EString("I'm an observer: ") + self->getId());
		return ServerState::OBSERVING;
	}
}

llong FastLeaderElection::getInitId(){
	if(self->getLearnerType() == QuorumServer::LearnerType::PARTICIPANT)
		return self->getId();
	else return ELLong::MIN_VALUE;
}

llong FastLeaderElection::getInitLastLoggedZxid(){
	if(self->getLearnerType() == QuorumServer::LearnerType::PARTICIPANT)
		return self->getLastLoggedZxid();
	else return ELLong::MIN_VALUE;
}

llong FastLeaderElection::getPeerEpoch(){
	if(self->getLearnerType() == QuorumServer::LearnerType::PARTICIPANT)
		try {
			return self->getCurrentEpoch();
		} catch(EIOException& e) {
			ERuntimeException re(e.getSourceFile(), e.getSourceLine(), e.getMessage(), &e);
			throw re;
		}
	else return ELLong::MIN_VALUE;
}

} /* namespace ezk */
} /* namespace efc */
