/*
 * QuorumPeer.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./QuorumPeer.hh"
#include "./Leader.hh"
#include "./Follower.hh"
#include "./Observer.hh"
#include "./LearnerHandler.hh"
#include "./LeaderElection.hh"
#include "./FastLeaderElection.hh"
#include "./AuthFastLeaderElection.hh"
#include "./FollowerZooKeeperServer.hh"
#include "./LeaderZooKeeperServer.hh"
#include "./ObserverZooKeeperServer.hh"

namespace efc {
namespace ezk {

sp<ELogger> QuorumPeer::LOG = ELoggerManager::getLogger("QuorumPeer");

sp<Follower> QuorumPeer::makeFollower(sp<FileTxnSnapLog> logFactory) THROWS(EIOException) {
	return new Follower(this, new FollowerZooKeeperServer(logFactory,
			this, this->zkDb));
}

sp<Leader> QuorumPeer::makeLeader(sp<FileTxnSnapLog> logFactory) THROWS(EIOException) {
	return new Leader(this, new LeaderZooKeeperServer(logFactory,
			this, this->zkDb));
}

sp<Observer> QuorumPeer::makeObserver(sp<FileTxnSnapLog> logFactory) THROWS(EIOException) {
	return new Observer(this, new ObserverZooKeeperServer(logFactory,
			this,  this->zkDb));
}

EA<EString*> QuorumPeer::getQuorumPeers() {
	EArrayList<EString*> l;
	SYNCHRONIZED(this) {
		if (leader != null) {
            auto learners = leader->getLearners();
			auto iter = learners->iterator();
			while (iter->hasNext()) {
				sp<LearnerHandler> fh = iter->next();
				if (fh->getSocket() != null) {
					EString s = fh->getSocket()->getRemoteSocketAddress()->toString();
					if (leader->isLearnerSynced(fh.get()))
						s += "*";
					l.add(new EString(s));
				}
			}
		} else if (follower != null) {
			l.add(new EString(follower->sock->getRemoteSocketAddress()->toString()));
		}
	}}
	return l.toArray();
}

void QuorumPeer::ResponderThread::run() {
	try {
		EA<byte> b(36);
		sp<EIOByteBuffer> responseBuffer = EIOByteBuffer::wrap(b.address(), b.length());
		EDatagramPacket packet(b, b.length());
		while (running) {
			peer->udpSocket->receive(&packet);
			if (packet.getLength() != 4) {
				LOG->warn(EString("Got more than just an xid! Len = ")
						+ packet.getLength());
			} else {
				responseBuffer->clear();
				responseBuffer->getInt(); // Skip the xid
				responseBuffer->putLLong(peer->myid);
				sp<Vote> current = peer->getCurrentVote();
				switch (peer->getPeerState()) {
				case LOOKING:
					responseBuffer->putLLong(current->getId());
					responseBuffer->putLLong(current->getZxid());
					break;
				case LEADING:
					responseBuffer->putLLong(peer->myid);
					try {
						llong proposed;
						SYNCHRONIZED(peer->leader) {
							proposed = peer->leader->lastProposed;
						}}
						responseBuffer->putLLong(proposed);
					} catch (ENullPointerException& npe) {
						// This can happen in state transitions,
						// just ignore the request
					}
					break;
				case FOLLOWING:
					responseBuffer->putLLong(current->getId());
					try {
						responseBuffer->putLLong(peer->follower->getZxid());
					} catch (ENullPointerException& npe) {
						// This can happen in state transitions,
						// just ignore the request
					}
					break;
				case OBSERVING:
					// Do nothing, Observers keep themselves to
					// themselves.
					break;
				}
				packet.setData(b);
				peer->udpSocket->send(&packet);
			}
			packet.setLength(b.length());
		}
	} catch (ERuntimeException& e) {
		LOG->warn("Unexpected runtime exception in ResponderThread",e);
	} catch (EIOException& e) {
		LOG->warn("Unexpected IO exception in ResponderThread",e);
	}

	LOG->warn("QuorumPeer responder thread exited");
}

sp<Election> QuorumPeer::makeLEStrategy(){
	LOG->debug("Initializing leader election protocol...");
	if (getElectionType() == 0) {
		electionAlg = new LeaderElection(this);
	}
	return electionAlg;
}

sp<Election> QuorumPeer::createElectionAlgorithm(int electionAlgorithm)  {
	sp<Election> le=null;

    //TODO: use a factory rather than a switch
    switch (electionAlgorithm) {
    case 0:
        le = new LeaderElection(this);
        break;
    case 1:
        le = new AuthFastLeaderElection(this);
        break;
    case 2:
        le = new AuthFastLeaderElection(this, true);
        break;
    case 3: {
        qcm = createCnxnManager();
        QuorumCnxManager::Listener& listener = qcm->listener;
        //if(listener != null){
            listener.start();
            le = new FastLeaderElection(this, qcm);
        //} else {
        //    LOG.error("Null listener when initializing cnx manager");
        //}
        break;
    }
    default:
        ES_ASSERT(false);
        break;
    }
    return le;
}

sp<ZooKeeperServer> QuorumPeer::getActiveServer() {
	SYNCHRONIZED(this) {
		if(leader!=null)
			return leader->zk;
		else if(follower!=null)
			return follower->zk;
		else if (observer != null)
			return observer->zk;
		return null;
	}}
}

void QuorumPeer::run() {
    setName((EString("QuorumPeer") + "[myid=" + getId() + "]" +
            cnxnFactory->getLocalAddress()->toString()).c_str());

    LOG->debug("Starting quorum peer");

    ON_FINALLY_NOTHROW(
		LOG->warn("QuorumPeer main thread exited");
    ) {
        /*
         * Main loop
         */
        while (running) {
            switch (getPeerState()) {
            case LOOKING: {
                LOG->info("LOOKING");

                boolean enabled = EBoolean::parseBoolean(ESystem::getProperty("readonlymode.enabled"));
                if (enabled) {
                    LOG->info("Attempting to start ReadOnlyZooKeeperServer");

                    // Create read-only server but don't start it immediately
                    sp<ReadOnlyZooKeeperServer> roZk = new ReadOnlyZooKeeperServer(
                            logFactory, this,
                            this->zkDb);

                    // Instead of starting roZk immediately, wait some grace
                    // period before we decide we're partitioned.
                    //
                    // Thread is used here because otherwise it would require
                    // changes in each of election strategy classes which is
                    // unnecessary code coupling.
                    class MgrThread : public EThread {
                    private:
                    	QuorumPeer* self;
                    	sp<ReadOnlyZooKeeperServer> roZk;
                    public:
                    	MgrThread(QuorumPeer* s, sp<ReadOnlyZooKeeperServer> z) : self(s), roZk(z) {
                    	}
                    	virtual void run() {
                    		try {
								// lower-bound grace period to 2 secs
								sleep(EMath::max(2000, self->tickTime));
								if (ServerState::LOOKING == self->getPeerState()) {
									roZk->startup();
								}
							} catch (EInterruptedException& e) {
								QuorumPeer::LOG->info("Interrupted while attempting to start ReadOnlyZooKeeperServer, not started");
							} catch (EException& e) {
								QuorumPeer::LOG->error("FAILED to start ReadOnlyZooKeeperServer", e);
							}
                    	}
                    };
                    sp<MgrThread> roZkMgr = new MgrThread(this, roZk);

                    ON_FINALLY_NOTHROW(
                    		// If the thread is in the the grace period, interrupt
							// to come out of waiting.
							roZkMgr->interrupt();
							roZk->shutdown();
                    ) {
						try {
							EThread::setDaemon(roZkMgr, true); //!!!
							roZkMgr->start();
							setBCVote(null);
							setCurrentVote(makeLEStrategy()->lookForLeader());
						} catch (EException& e) {
							LOG->warn("Unexpected exception",e);
							setPeerState(ServerState::LOOKING);
						}
                    }}
                } else {
                    try {
                        setBCVote(null);
                        setCurrentVote(makeLEStrategy()->lookForLeader());
                    } catch (EException& e) {
                        LOG->warn("Unexpected exception", e);
                        setPeerState(ServerState::LOOKING);
                    }
                }
                break;
            }
            case OBSERVING: {
            	ON_FINALLY_NOTHROW(
					observer->shutdown();
					setObserver(null);
					setPeerState(ServerState::LOOKING);
            	) {
					try {
						LOG->info("OBSERVING");
						setObserver(makeObserver(logFactory));
						observer->observeLeader();
					} catch (EException& e) {
						LOG->warn("Unexpected exception",e );
					}
            	}}
                break;
            }
            case FOLLOWING: {
            	ON_FINALLY_NOTHROW(
					follower->shutdown();
					setFollower(null);
					setPeerState(ServerState::LOOKING);
				) {
					try {
						LOG->info("FOLLOWING");
						setFollower(makeFollower(logFactory));
						follower->followLeader();
					} catch (EException& e) {
						LOG->warn("Unexpected exception",e);
					}
            	}}
                break;
            }
            case LEADING: {
                LOG->info("LEADING");
                ON_FINALLY_NOTHROW(
					if (leader != null) {
						leader->shutdown("Forcing shutdown");
						setLeader(null);
					}
					setPeerState(ServerState::LOOKING);
                ) {
					try {
						setLeader(makeLeader(logFactory));
						leader->lead();
						setLeader(null);
					} catch (EException& e) {
						LOG->warn("Unexpected exception",e);
					}
                }}
                break;
            }
            }
        }
    }}
}

void QuorumPeer::shutdown() {
	running = false;
	if (leader != null) {
		leader->shutdown("quorum Peer shutdown");
	}
	if (follower != null) {
		follower->shutdown();
	}
	cnxnFactory->shutdown();
	if(udpSocket != null) {
		udpSocket->close();
	}

	if(getElectionAlg() != null){
		this->interrupt();
		getElectionAlg()->shutdown();
	}
	try {
		zkDb->close();
	} catch (EIOException& ie) {
		LOG->warn("Error closing logs ", ie);
	}
}

void QuorumPeer::updateElectionVote(llong newEpoch) {
	sp<Vote> currentVote = getCurrentVote();
	setBCVote(currentVote);
	if (currentVote != null) {
		setCurrentVote(new Vote(currentVote->getId(),
			currentVote->getZxid(),
			currentVote->getElectionEpoch(),
			newEpoch,
			currentVote->getState()));
	}
}

sp<EHashMap<llong,QuorumServer*> > QuorumPeer::getVotingView() {
	return QuorumPeer::viewToVotingView(getView());
}

sp<EHashMap<llong,QuorumServer*> > QuorumPeer::viewToVotingView(
		EHashMap<llong, QuorumServer*>* view) {
	sp<EHashMap<llong,QuorumServer*> > ret =
		new EHashMap<llong, QuorumServer*>(false); //false!!!
	auto iter = view->values()->iterator();
	while (iter->hasNext()) {
		QuorumServer* server = iter->next();
		if (server->type == QuorumServer::LearnerType::PARTICIPANT) {
			ret->put(server->id, server);
		}
	}
	return ret;
}

} /* namespace ezk */
} /* namespace efc */
