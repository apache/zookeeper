/*
 * ZooKeeperServer.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ZooKeeperServer_HH_
#define ZooKeeperServer_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./SessionTrackerImpl.hh"
#include "./ServerCnxnFactory.hh"
#include "./ZKDatabase.hh"
#include "./DataTree.hh"
#include "./ServerCnxn.hh"
#include "./SessionTracker.hh"
#include "./RequestProcessor.hh"
#include "./ZooKeeperServerState.hh"
#include "./ZooKeeperServerShutdownHandler.hh"
#include "./UnimplementedRequestProcessor.hh"
#include "../ZooDefs.hh"
#include "../Environment.hh"
#include "../KeeperException.hh"
#include "../data/ACL.hh"
#include "../data/Id.hh"
#include "../data/StatPersisted.hh"
#include "../proto/AuthPacket.hh"
#include "../proto/ConnectRequest.hh"
#include "../proto/ConnectResponse.hh"
#include "../proto/RequestHeader.hh"
#include "../proto/RequestHeader.hh"
#include "../server/auth/ProviderRegistry.hh"
#include "../server/auth/AuthenticationProvider.hh"
#include "../server/persistence/FileTxnSnapLog.hh"
#include "../txn/TxnHeader.hh"
#include "../txn/CreateSessionTxn.hh"
#include "../../jute/inc/ERecord.hh"
#include "../../jute/inc/EBinaryInputArchive.hh"
#include "../../jute/inc/EBinaryOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 * This class implements a simple standalone ZooKeeperServer. It sets up the
 * following chain of RequestProcessors to process requests:
 * PrepRequestProcessor -> SyncRequestProcessor -> FinalRequestProcessor
 */
class ZooKeeperServer: public enable_shared_from_this<ZooKeeperServer>,
		public SessionExpirer,
		public Provider,
		public ESynchronizeable {
public:
	static sp<ELogger> LOG;

	int tickTime;// = DEFAULT_TICK_TIME;
	/** value of -1 indicates unset, use default */
	int minSessionTimeout;// = -1;
	/** value of -1 indicates unset, use default */
	int maxSessionTimeout;// = -1;
	sp<SessionTracker> sessionTracker;

	sp<RequestProcessor> firstProcessor;
	volatile State state;// = State.INITIAL;

public:
	/**
	 * This structure is used to facilitate information sharing between PrepRP
	 * and FinalRP.
	 */
	class ChangeRecord : public EObject {
	public:
		ChangeRecord(llong zxid, EString path, sp<StatPersisted> stat, int childCount,
				sp<EList<sp<ACL> > > acl) :
				zxid(zxid),
				path(path),
				stat(stat),
				childCount(childCount),
				acl(acl) {
		}

		llong zxid;

		EString path;

		sp<StatPersisted> stat; /* Make sure to create a new object when changing */

		int childCount;

		sp<EList<sp<ACL> > > acl; /* Make sure to create a new object when changing */

		sp<ChangeRecord> duplicate(llong zxid) {
			sp<StatPersisted> stat = new StatPersisted();
			if (this->stat != null) {
				DataTree::copyStatPersisted(this->stat, stat);
			}
			sp<EList<sp<ACL> > > clone = new EArrayList<sp<ACL> >();
			if (acl != null) {
				auto iter = acl->iterator();
				while (iter->hasNext()) {
					sp<ACL> a = iter->next();
					clone->add(a);
				}
			}
			return new ChangeRecord(zxid, path, stat, childCount, clone);
		}
	};

    virtual void removeCnxn(ServerCnxn* cnxn) {
        zkDb->removeCnxn(cnxn);
    }

    virtual void setupRequestProcessors();

    virtual void createSessionTracker() {
		sessionTracker = new SessionTrackerImpl(this, zkDb->getSessionWithTimeOuts(),
				tickTime, 1, getZooKeeperServerListener());
	}

    virtual void startSessionTracker() {
    	auto sti = dynamic_pointer_cast<SessionTrackerImpl>(sessionTracker);
    	EThread::setDaemon(sti, true); //!!!
    	sti->start();
    }

    /**
     * This can be used while shutting down the server to see whether the server
     * is already shutdown or not.
     *
     * @return true if the server is running or server hits an error, false
     *         otherwise.
     */
    virtual boolean canShutdown() {
        return state == State::RUNNING || state == State::ERROR;
    }

private:
    sp<FileTxnSnapLog> txnLogFactory;// = null;
    sp<ZKDatabase> zkDb;
    EAtomicLLong hzxid;// = new AtomicLong(0);

	/**
	 * This is the secret that we use to generate passwords, for the moment it
	 * is more of a sanity check.
	 */
    llong superSecret;// = 0XB3415C00L;

    EAtomicInteger requestsInProcess;// = new AtomicInteger(0);

    ServerCnxnFactory* serverCnxnFactory;

    ServerStats stats;
    ZooKeeperServerListener* listener;
    ZooKeeperServerShutdownHandler* zkShutdownHandler;

    void close(llong sessionId) {
        submitRequest(null, sessionId, ZooDefs::OpCode::closeSession, 0, null, null);
    }

    sp<EA<byte> > generatePasswd(llong id) {
		ERandom r(id ^ superSecret);
		sp<EA<byte> > p = new EA<byte>(16);
		r.nextBytes(p->address(), p->length());
		return p;
	}

    /**
	 * @param cnxn
	 * @param sessionId
	 * @param xid
	 * @param bb
	 */
	void submitRequest(sp<ServerCnxn> cnxn, llong sessionId, int type,
			int xid, sp<EIOByteBuffer> bb, sp<EList<sp<Id> > > authInfo) {
		sp<Request> si = new Request(cnxn, sessionId, xid, type, bb, authInfo);
		submitRequest(si);
	}

public:
	static const int DEFAULT_TICK_TIME = 3000;

    EArrayList<sp<ChangeRecord> > outstandingChanges;// = new ArrayList<ChangeRecord>();
    EReentrantLock outstandingChangesLock;
    // this data structure must be accessed under the outstandingChanges lock
    EHashMap<sp<EString>, sp<ChangeRecord> > outstandingChangesForPath;// = new HashMap<String, ChangeRecord>();

    class MissingSessionException : public EIOException {
    public:
    	MissingSessionException(const char *_file_, int _line_, EString msg) : EIOException(_file_, _line_, msg.c_str()) {
		}
	};

public:
    virtual ~ZooKeeperServer();

    /**
     * Creates a ZooKeeperServer instance. Nothing is setup, use the setX
     * methods to prepare the instance (eg datadir, datalogdir, ticktime, 
     * builder, etc...)
     * 
     * @throws IOException
     */
    ZooKeeperServer();
    
    /**
     * Creates a ZooKeeperServer instance. It sets everything up, but doesn't
     * actually start listening for clients until run() is invoked.
     * 
     * @param dataDir the directory to put the data
     */
    ZooKeeperServer(sp<FileTxnSnapLog> txnLogFactory, int tickTime,
            int minSessionTimeout, int maxSessionTimeout, sp<ZKDatabase> zkDb);

    /**
     * creates a zookeeperserver instance. 
     * @param txnLogFactory the file transaction snapshot logging class
     * @param tickTime the ticktime for the server
     * @throws IOException
     */
    ZooKeeperServer(sp<FileTxnSnapLog> txnLogFactory, int tickTime);
    
    ServerStats* serverStats() {
        return &stats;
    }

    void dumpConf(EPrintStream* pwriter) {
        pwriter->print("clientPort=");
        pwriter->println(getClientPort());
        pwriter->print("dataDir=");
        pwriter->println(zkDb->snapLog->getSnapDir()->getAbsolutePath().c_str());
        pwriter->print("dataLogDir=");
        pwriter->println(zkDb->snapLog->getDataDir()->getAbsolutePath().c_str());
        pwriter->print("tickTime=");
        pwriter->println(getTickTime());
        pwriter->print("maxClientCnxns=");
        pwriter->println(serverCnxnFactory->getMaxClientCnxnsPerHost());
        pwriter->print("minSessionTimeout=");
        pwriter->println(getMinSessionTimeout());
        pwriter->print("maxSessionTimeout=");
        pwriter->println(getMaxSessionTimeout());

        pwriter->print("serverId=");
        pwriter->println(getServerId());
    }

    /**
     * Sets the state of ZooKeeper server. After changing the state, it notifies
     * the server state change to a registered shutdown handler, if any.
     * <p>
     * The following are the server state transitions:
     * <li>During startup the server will be in the INITIAL state.</li>
     * <li>After successfully starting, the server sets the state to RUNNING.
     * </li>
     * <li>The server transitions to the ERROR state if it hits an internal
     * error. {@link ZooKeeperServerListenerImpl} notifies any critical resource
     * error events, e.g., SyncRequestProcessor not being able to write a txn to
     * disk.</li>
     * <li>During shutdown the server sets the state to SHUTDOWN, which
     * corresponds to the server not running.</li>
     *
     * @param state new server state.
     */
    virtual void setState(State state) {
        this->state = state;
        // Notify server state changes to the registered shutdown handler, if any.
        if (zkShutdownHandler != null) {
            zkShutdownHandler->handle(state);
        } else {
            LOG->error("ZKShutdownHandler is not registered, so ZooKeeper server "
                       "won't take any action on ERROR or SHUTDOWN server state changes");
        }
    }
    
    /**
     * get the zookeeper database for this server
     * @return the zookeeper database for this server
     */
    sp<ZKDatabase> getZKDatabase() {
        return this->zkDb;
    }
    
    /**
     * set the zkdatabase for this zookeeper server
     * @param zkDb
     */
    void setZKDatabase(sp<ZKDatabase> zkDb) {
    	this->zkDb = zkDb;
    }
    
    /**
     *  Restore sessions and data
     */
    void loadData() THROWS2(EIOException, EInterruptedException) {
        /*
         * When a new leader starts executing Leader#lead, it 
         * invokes this method. The database, however, has been
         * initialized before running leader election so that
         * the server could pick its zxid for its initial vote.
         * It does it by invoking QuorumPeer#getLastLoggedZxid.
         * Consequently, we don't need to initialize it once more
         * and avoid the penalty of loading it a second time. Not 
         * reloading it is particularly important for applications
         * that host a large database.
         * 
         * The following if block checks whether the database has
         * been initialized or not. Note that this method is
         * invoked by at least one other method: 
         * ZooKeeperServer#startdata.
         *  
         * See ZOOKEEPER-1642 for more detail.
         */
        if(zkDb->isInitialized()){
            setZxid(zkDb->getDataTreeLastProcessedZxid());
        }
        else {
            setZxid(zkDb->loadDataBase());
        }
        
        // Clean up dead sessions
        ELinkedList<llong> deadSessions;
        auto iter = zkDb->getSessions()->iterator();
        while (iter->hasNext()) {
        	llong session = iter->next();
            if (zkDb->getSessionWithTimeOuts()->get(session) == null) {
                deadSessions.add(session);
            }
        }
        zkDb->setDataTreeInit(true);
        auto iter2 = deadSessions.iterator();
        while (iter2->hasNext()) {
        	llong session = iter2->next();
            // XXX: Is lastProcessedZxid really the best thing to use?
            killSession(session, zkDb->getDataTreeLastProcessedZxid());
        }
    }

    void takeSnapshot(){
        try {
            txnLogFactory->save(zkDb->getDataTree(), zkDb->getSessionWithTimeOuts());
        } catch (EIOException& e) {
            LOG->error("Severe unrecoverable error, exiting", e);
            // This is a severe error that we cannot recover from,
            // so we need to exit
            ESystem::exit(10);
        }
    }

  
    /**
     * This should be called from a synchronized block on this!
     */
    llong getZxid() {
        return hzxid.get();
    }

    llong getNextZxid() {
        return hzxid.incrementAndGet();
    }

    void setZxid(llong zxid) {
        hzxid.set(zxid);
    }
    
    void closeSession(llong sessionId) {
        LOG->info("Closing session 0x" + ELLong::toHexString(sessionId));
        
        // we do not want to wait for a session close. send it as soon as we
        // detect it!
        close(sessionId);
    }

    void killSession(llong sessionId, llong zxid) {
        zkDb->killSession(sessionId, zxid);
        if (LOG->isTraceEnabled()) {
            ZooTrace::logTraceMessage(LOG, ZooTrace::SESSION_TRACE_MASK,
                                         "ZooKeeperServer --- killSession: 0x"
                    + ELLong::toHexString(sessionId));
        }
        if (sessionTracker != null) {
            sessionTracker->removeSession(sessionId);
        }
    }

    virtual void expire(Session* session) {
        llong sessionId = session->getSessionId();
        LOG->info("Expiring session 0x" + ELLong::toHexString(sessionId)
                + ", timeout of " + session->getTimeout() + "ms exceeded");
        close(sessionId);
    }

    void touch(sp<ServerCnxn> cnxn) THROWS(MissingSessionException) {
        if (cnxn == null) {
            return;
        }
        llong id = cnxn->getSessionId();
        int to = cnxn->getSessionTimeout();
        if (!sessionTracker->touchSession(id, to)) {
            throw MissingSessionException(__FILE__, __LINE__,
                    "No session with sessionid 0x" + ELLong::toHexString(id)
                    + " exists, probably expired and removed");
        }
    }
    
    void startdata() THROWS2(EIOException, EInterruptedException) {
        //check to see if zkDb is not null
        if (zkDb == null) {
            zkDb = new ZKDatabase(this->txnLogFactory);
        }  
        if (!zkDb->isInitialized()) {
            loadData();
        }
    }
    
    synchronized
    virtual void startup() {
    	SYNCHRONIZED(this) {
			if (sessionTracker == null) {
				createSessionTracker();
			}
			startSessionTracker();
			setupRequestProcessors();

			setState(State::RUNNING);
			notifyAll();
    	}}
    }

    ZooKeeperServerListener* getZooKeeperServerListener() {
        return listener;
    }

    boolean isRunning() {
        return state == State::RUNNING;
    }

    virtual void shutdown() {
        shutdown(false);
    }

    /**
     * Shut down the server instance
     * @param fullyShutDown true if another server using the same database will not replace this one in the same process
     */
    synchronized
    virtual void shutdown(boolean fullyShutDown) {
    	SYNCHRONIZED(this) {
			if (!canShutdown()) {
				LOG->debug("ZooKeeper server is not running, so not proceeding to shutdown!");
				return;
			}
			LOG->info("shutting down");

			// new RuntimeException("Calling shutdown").printStackTrace();
			setState(State::SHUTDOWN);
			// Since sessionTracker and syncThreads poll we just have to
			// set running to false and they will detect it during the poll
			// interval.
			if (sessionTracker != null) {
				sessionTracker->shutdown();
			}
			if (firstProcessor != null) {
				firstProcessor->shutdown();
			}

			if (fullyShutDown && zkDb != null) {
				zkDb->clear();
			}
			// else there is no need to clear the database
			//  * When a new quorum is established we can still apply the diff
			//    on top of the same zkDb data
			//  * If we fetch a new snapshot from leader, the zkDb will be
			//    cleared anyway before loading the snapshot
    	}}
    }

    void incInProcess() {
        requestsInProcess.incrementAndGet();
    }

    void decInProcess() {
        requestsInProcess.decrementAndGet();
    }

    int getInProcess() {
        return requestsInProcess.get();
    }

    boolean checkPasswd(llong sessionId, EA<byte>* passwd) {
    	sp<EA<byte> > p = generatePasswd(sessionId);
        return sessionId != 0
                && EArrays::equals(passwd, p.get());
    }

    llong createSession(sp<ServerCnxn> cnxn, EA<byte>* passwd, int timeout) {
        llong sessionId = sessionTracker->createSession(timeout);
        ERandom r(sessionId ^ superSecret);
        r.nextBytes(passwd->address(), passwd->length());
        sp<EIOByteBuffer> to = EIOByteBuffer::allocate(4);
        to->putInt(timeout);
        cnxn->setSessionId(sessionId);
        submitRequest(cnxn, sessionId, ZooDefs::OpCode::createSession, 0, to, null);
        return sessionId;
    }

    /**
     * set the owner of this session as owner
     * @param id the session id
     * @param owner the owner of the session
     * @throws SessionExpiredException
     */
    void setOwner(llong id, sp<EObject> owner) THROWS(SessionExpiredException) {
        sessionTracker->setOwner(id, owner);
    }

    void revalidateSession(sp<ServerCnxn> cnxn, llong sessionId,
            int sessionTimeout) THROWS(EIOException) {
        boolean rc = sessionTracker->touchSession(sessionId, sessionTimeout);
        if (LOG->isTraceEnabled()) {
            ZooTrace::logTraceMessage(LOG, ZooTrace::SESSION_TRACE_MASK,
                                     "Session 0x" + ELLong::toHexString(sessionId) +
                    " is valid: " + rc);
        }
        finishSessionInit(cnxn, rc);
    }

    void reopenSession(sp<ServerCnxn> cnxn, llong sessionId, EA<byte>* passwd,
            int sessionTimeout) THROWS(EIOException) {
        if (!checkPasswd(sessionId, passwd)) {
            finishSessionInit(cnxn, false);
        } else {
            revalidateSession(cnxn, sessionId, sessionTimeout);
        }
    }

    void finishSessionInit(sp<ServerCnxn> cnxn, boolean valid);
    
    void closeSession(sp<ServerCnxn> cnxn, RequestHeader* requestHeader) {
        closeSession(cnxn->getSessionId());
    }

    virtual llong getServerId() {
        return 0;
    }

    virtual void submitRequest(sp<Request> si) {
        if (firstProcessor == null) {
            SYNCHRONIZED(this) {
                try {
                    // Since all requests are passed to the request
                    // processor it should wait for setting up the request
                    // processor chain. The state will be updated to RUNNING
                    // after the setup.
                    while (state == State::INITIAL) {
                        wait(1000);
                    }
                } catch (EInterruptedException& e) {
                    LOG->warn("Unexpected interruption", e);
                }
                if (firstProcessor == null || state != State::RUNNING) {
                    throw ERuntimeException(__FILE__, __LINE__, "Not started");
                }
            }}
        }
        try {
            touch(si->cnxn);
            boolean validpacket = Request::isValid(si->type);
            if (validpacket) {
                firstProcessor->processRequest(si);
                if (si->cnxn != null) {
                    incInProcess();
                }
            } else {
                LOG->warn(EString("Received packet at server of unknown type ") + si->type);
                UnimplementedRequestProcessor urp;
                urp.processRequest(si);
            }
        } catch (MissingSessionException& e) {
            if (LOG->isDebugEnabled()) {
                LOG->debug(EString("Dropping request: ") + e.getMessage());
            }
        } catch (RequestProcessorException& e) {
            LOG->error(EString("Unable to process request:") + e.getMessage(), e);
        }
    }

    void setServerCnxnFactory(ServerCnxnFactory* factory) {
        serverCnxnFactory = factory;
    }

    ServerCnxnFactory* getServerCnxnFactory() {
        return serverCnxnFactory;
    }

    /**
     * return the last proceesed id from the 
     * datatree
     */
    llong getLastProcessedZxid() {
        return zkDb->getDataTreeLastProcessedZxid();
    }

    /**
     * return the outstanding requests
     * in the queue, which havent been 
     * processed yet
     */
    llong getOutstandingRequests() {
        return getInProcess();
    }

    /**
     * trunccate the log to get in sync with others 
     * if in a quorum
     * @param zxid the zxid that it needs to get in sync
     * with others
     * @throws IOException
     */
    void truncateLog(llong zxid) THROWS(EIOException) {
    	this->zkDb->truncateLog(zxid);
    }
       
    int getTickTime() {
        return tickTime;
    }

    void setTickTime(int tickTime) {
        LOG->info(EString("tickTime set to ") + tickTime);
        this->tickTime = tickTime;
    }

    int getMinSessionTimeout() {
        return minSessionTimeout == -1 ? tickTime * 2 : minSessionTimeout;
    }

    void setMinSessionTimeout(int min) {
        LOG->info(EString("minSessionTimeout set to ") + min);
        this->minSessionTimeout = min;
    }

    int getMaxSessionTimeout() {
        return maxSessionTimeout == -1 ? tickTime * 20 : maxSessionTimeout;
    }

    void setMaxSessionTimeout(int max) {
        LOG->info(EString("maxSessionTimeout set to ") + max);
        this->maxSessionTimeout = max;
    }

    int getClientPort() {
        return serverCnxnFactory != null ? serverCnxnFactory->getLocalPort() : -1;
    }

    void setTxnLogFactory(sp<FileTxnSnapLog> txnLog) {
    	this->txnLogFactory = txnLog;
    }
    
    sp<FileTxnSnapLog> getTxnLogFactory() {
        return this->txnLogFactory;
    }

    virtual EString getState() {
        return "standalone";
    }

    void dumpEphemerals(EPrintStream* pwriter) {
    	zkDb->dumpEphemerals(pwriter);
    }
    
    /**
     * return the total number of client connections that are alive
     * to this server
     */
    int getNumAliveConnections() {
        return serverCnxnFactory->getNumAliveConnections();
    }
    
    void processConnectRequest(sp<ServerCnxn> cnxn, sp<EIOByteBuffer> incomingBuffer) THROWS(EIOException);

    boolean shouldThrottle(llong outStandingCount) {
        if (getGlobalOutstandingLimit() < getInProcess()) {
            return outStandingCount > 0;
        }
        return false; 
    }

    void processPacket(sp<ServerCnxn> cnxn, sp<EIOByteBuffer> incomingBuffer) THROWS(EIOException);

    sp<DataTree::ProcessTxnResult> processTxn(TxnHeader* hdr, ERecord* txn) {
        int opCode = hdr->getType();
        llong sessionId = hdr->getClientId();
        sp<DataTree::ProcessTxnResult> rc = getZKDatabase()->processTxn(hdr, txn);
        if (opCode == ZooDefs::OpCode::createSession) {
        	CreateSessionTxn* cst = dynamic_cast<CreateSessionTxn*>(txn);
            if (cst) {
                sessionTracker->addSession(sessionId, cst->getTimeOut());
            } else {
                LOG->warn("*****>>>>> Got "
                        + txn->toString());
            }
        } else if (opCode == ZooDefs::OpCode::closeSession) {
            sessionTracker->removeSession(sessionId);
        }
        return rc;
    }

    /**
     * This method is used to register the ZooKeeperServerShutdownHandler to get
     * server's error or shutdown state change notifications.
     * {@link ZooKeeperServerShutdownHandler#handle(State)} will be called for
     * every server state changes {@link #setState(State)}.
     *
     * @param zkShutdownHandler shutdown handler
     */
    void registerServerShutdownHandler(ZooKeeperServerShutdownHandler* zkShutdownHandler) {
        this->zkShutdownHandler = zkShutdownHandler;
    }

    static int getSnapCount() {
        EString sc = ESystem::getProperty("zookeeper.snapCount");
        try {
            int snapCount = EInteger::parseInt(sc.c_str());

            // snapCount must be 2 or more. See org.apache.zookeeper.server.SyncRequestProcessor
            if( snapCount < 2 ) {
                LOG->warn("SnapCount should be 2 or more. Now, snapCount is reset to 2");
                snapCount = 2;
            }
            return snapCount;
        } catch (EException& e) {
            //@see: return 100000;
        }
        return 100000;
    }

    static int getGlobalOutstandingLimit() {
        EString sc = ESystem::getProperty("zookeeper.globalOutstandingLimit");
        int limit;
        try {
            limit = EInteger::parseInt(sc.c_str());
        } catch (EException& e) {
            limit = 1000;
        }
        return limit;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZooKeeperServer_HH_ */
