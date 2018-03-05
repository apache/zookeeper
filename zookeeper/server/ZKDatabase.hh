/*
 * ZKDatabase.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ZKDatabase_HH_
#define ZKDatabase_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./DataTree.hh"
#include "./quorum/Proposal.hh"
#include "./quorum/PacketType.hh"
#include "./quorum/QuorumPacket.hh"
#include "./persistence/FileTxnSnapLog.hh"
#include "./util/SerializeUtils.hh"
#include "../Watcher.hh"
#include "../KeeperException.hh"
#include "../txn/TxnHeader.hh"
#include "../data/ACL.hh"
#include "../data/Stat.hh"
#include "../../jute/inc/ERecord.hh"
#include "../../jute/inc/EBinaryInputArchive.hh"
#include "../../jute/inc/EBinaryOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 * This class maintains the in memory database of zookeeper
 * server states that includes the sessions, datatree and the
 * committed logs. It is booted up  after reading the logs
 * and snapshots from the disk.
 */
class ZKDatabase : public ESynchronizeable {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(ZKDatabase.class);

	static const int commitLogCount = 500;

	volatile boolean initialized;// = false;

public:
    /**
     * make sure on a clear you take care of 
     * all these members.
     */
    EConcurrentHashMap<llong, EInteger> sessionsWithTimeouts;
    sp<DataTree> dataTree;
    sp<FileTxnSnapLog> snapLog;
    llong minCommittedLog, maxCommittedLog;
    sp<ELinkedList<sp<Proposal> > > committedLog;// = new LinkedList<Proposal>();
    EReentrantReadWriteLock logLock;// = new ReentrantReadWriteLock();

    /**
     * the filetxnsnaplog that this zk database
     * maps to. There is a one to one relationship
     * between a filetxnsnaplog and zkdatabase.
     * @param snapLog the FileTxnSnapLog mapping this zkdatabase
     */
    ZKDatabase(sp<FileTxnSnapLog> snapLog) {
        this->snapLog = snapLog;
        this->initialized = false;
        dataTree = new DataTree();
        committedLog = new ELinkedList<sp<Proposal> >();
    }
    
    /**
     * checks to see if the zk database has been
     * initialized or not.
     * @return true if zk database is initialized and false if not
     */
    boolean isInitialized() {
        return initialized;
    }
    
    /**
     * clear the zkdatabase. 
     * Note to developers - be careful to see that 
     * the clear method does clear out all the
     * data structures in zkdatabase.
     */
    void clear() {
        minCommittedLog = 0;
        maxCommittedLog = 0;
        /* to be safe we just create a new 
         * datatree.
         */
        dataTree = new DataTree();
        sessionsWithTimeouts.clear();
        EReentrantReadWriteLock::WriteLock* lock = logLock.writeLock();
        SYNCBLOCK(lock) {
            committedLog->clear();
        }}
        initialized = false;
    }
    
    /**
     * the datatree for this zkdatabase
     * @return the datatree for this zkdatabase
     */
    sp<DataTree> getDataTree() {
        return this->dataTree;
    }
 
    /**
     * the committed log for this zk database
     * @return the committed log for this zkdatabase
     */
    llong getmaxCommittedLog() {
        return maxCommittedLog;
    }
    
    /**
     * the minimum committed transaction log
     * available in memory
     * @return the minimum committed transaction
     * log available in memory
     */
    llong getminCommittedLog() {
        return minCommittedLog;
    }

    /**
     * Get the lock that controls the committedLog. If you want to get the pointer to the committedLog, you need
     * to use this lock to acquire a read lock before calling getCommittedLog()
     * @return the lock that controls the committed log
     */
    EReentrantReadWriteLock* getLogLock() {
        return &logLock;
    }
    
    synchronized
    sp<ELinkedList<sp<Proposal> > > getCommittedLog() {
    	EReentrantReadWriteLock::ReadLock* lock = logLock.readLock();
        // only make a copy if this thread isn't already holding a lock
        if (logLock.getReadHoldCount() <=0) {
        	SYNCBLOCK(lock) {
        		return new ELinkedList<sp<Proposal> >(*this->committedLog);
            }}
        } 
        return this->committedLog;
    }      
    
    /**
     * get the last processed zxid from a datatree
     * @return the last processed zxid of a datatree
     */
    llong getDataTreeLastProcessedZxid() {
        return dataTree->lastProcessedZxid;
    }
    
    /**
     * set the datatree initialized or not
     * @param b set the datatree initialized to b
     */
    void setDataTreeInit(boolean b) {
        dataTree->initialized = b;
    }
    
    /**
     * return the sessions in the datatree
     * @return the data tree sessions
     */
    ESet<llong>* getSessions() {
        return dataTree->getSessions();
    }
    
    /**
     * get sessions with timeouts
     * @return the hashmap of sessions with timeouts
     */
    EConcurrentHashMap<llong, EInteger>* getSessionWithTimeOuts() {
        return &sessionsWithTimeouts;
    }
    
    /**
     * load the database from the disk onto memory and also add 
     * the transactions to the committedlog in memory.
     * @return the last valid zxid on disk
     * @throws IOException
     */
    llong loadDataBase() THROWS(EIOException) {
    	class ZKPlayBackListener : public FileTxnSnapLog::PlayBackListener {
    	private:
    		ZKDatabase* db;
    	public:
    		ZKPlayBackListener(ZKDatabase* db) : db(db) {
    		}
    		virtual void onTxnLoaded(sp<TxnHeader> hdr, sp<ERecord> txn) {
    			sp<Request> r = new Request(null, 0, hdr->getCxid(), hdr->getType(),
    			                        null, null);
				r->txn = txn;
				r->hdr = hdr;
				r->zxid = hdr->getZxid();
				db->addCommittedProposal(r);
    		}
    	};
    	ZKPlayBackListener listener(this);
        llong zxid = snapLog->restore(dataTree, &sessionsWithTimeouts, &listener);
        initialized = true;
        return zxid;
    }
    
    /**
     * maintains a list of last <i>committedLog</i>
     *  or so committed requests. This is used for
     * fast follower synchronization.
     * @param request committed request
     */
    void addCommittedProposal(sp<Request> request) {
        EReentrantReadWriteLock::WriteLock* wl = logLock.writeLock();
        SYNCBLOCK(wl) {
            if (committedLog->size() > commitLogCount) {
            	committedLog->removeFirst();
                minCommittedLog = committedLog->getFirst()->packet->getZxid();
            }
            if (committedLog->size() == 0) {
                minCommittedLog = request->zxid;
                maxCommittedLog = request->zxid;
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
                LOG->error(__FILE__, __LINE__, "This really should be impossible", e);
            }
            sp<QuorumPacket> pp = new QuorumPacket(PacketType::PROPOSAL, request->zxid,
                    baos.toByteArray(), null);
            sp<Proposal> p = new Proposal();
            p->packet = pp;
            p->request = request;
            committedLog->add(p);
            maxCommittedLog = p->packet->getZxid();
        }}
    }

	sp<EList<sp<ACL> > > aclForNode(DataNode* n) {
        return dataTree->getACL(n);
    }

    /**
     * remove a cnxn from the datatree
     * @param cnxn the cnxn to remove from the datatree
     */
    void removeCnxn(ServerCnxn* cnxn) {
        dataTree->removeCnxn(cnxn);
    }

    /**
     * kill a given session in the datatree
     * @param sessionId the session id to be killed
     * @param zxid the zxid of kill session transaction
     */
    void killSession(llong sessionId, llong zxid) {
        dataTree->killSession(sessionId, zxid);
    }

    /**
     * write a text dump of all the ephemerals in the datatree
     * @param pwriter the output to write to
     */
    void dumpEphemerals(EPrintStream* pwriter) {
        dataTree->dumpEphemerals(pwriter);
    }

    /**
     * the node count of the datatree
     * @return the node count of datatree
     */
    int getNodeCount() {
        return dataTree->getNodeCount();
    }

    /**
     * the paths for  ephemeral session id 
     * @param sessionId the session id for which paths match to 
     * @return the paths for a session id
     */
    sp<EHashSet<EString*> > getEphemerals(llong sessionId) {
        return dataTree->getEphemerals(sessionId);
    }

    /**
     * the last processed zxid in the datatree
     * @param zxid the last processed zxid in the datatree
     */
    void setlastProcessedZxid(llong zxid) {
        dataTree->lastProcessedZxid = zxid;
    }

    /**
     * the process txn on the data
     * @param hdr the txnheader for the txn
     * @param txn the transaction that needs to be processed
     * @return the result of processing the transaction on this
     * datatree/zkdatabase
     */
    sp<DataTree::ProcessTxnResult> processTxn(TxnHeader* hdr, ERecord* txn) {
        return dataTree->processTxn(hdr, txn);
    }

    /**
     * stat the path 
     * @param path the path for which stat is to be done
     * @param serverCnxn the servercnxn attached to this request
     * @return the stat of this node
     * @throws KeeperException.NoNodeException
     */
    sp<Stat> statNode(EString path, sp<ServerCnxn> serverCnxn) THROWS(NoNodeException) {
        return dataTree->statNode(path, serverCnxn);
    }
    
    /**
     * get the datanode for this path
     * @param path the path to lookup
     * @return the datanode for getting the path
     */
    sp<DataNode> getNode(EString path) {
      return dataTree->getNode(path);
    }

    /**
     * get data and stat for a path 
     * @param path the path being queried
     * @param stat the stat for this path
     * @param watcher the watcher function
     * @return
     * @throws KeeperException.NoNodeException
     */
    sp<EA<byte> > getData(EString path, sp<Stat> stat, sp<Watcher> watcher)
    	THROWS(NoNodeException) {
        return dataTree->getData(path, stat, watcher);
    }

    /**
     * set watches on the datatree
     * @param relativeZxid the relative zxid that client has seen
     * @param dataWatches the data watches the client wants to reset
     * @param existWatches the exists watches the client wants to reset
     * @param childWatches the child watches the client wants to reset
     * @param watcher the watcher function
     */
    void setWatches(llong relativeZxid, sp<EList<EString*> > dataWatches,
    		sp<EList<EString*> > existWatches, sp<EList<EString*> > childWatches,
    		sp<Watcher> watcher) {
        dataTree->setWatches(relativeZxid, dataWatches, existWatches, childWatches, watcher);
    }
    
    /**
     * get acl for a path
     * @param path the path to query for acl
     * @param stat the stat for the node
     * @return the acl list for this path
     * @throws NoNodeException
     */
    sp<EList<sp<ACL> > > getACL(EString path, sp<Stat> stat) THROWS(NoNodeException) {
        return dataTree->getACL(path, stat);
    }

    /**
     * get children list for this path
     * @param path the path of the node
     * @param stat the stat of the node
     * @param watcher the watcher function for this path
     * @return the list of children for this path
     * @throws KeeperException.NoNodeException
     */
    sp<EList<EString*> > getChildren(EString path, sp<Stat> stat, sp<Watcher> watcher)
		THROWS(NoNodeException) {
        return dataTree->getChildren(path, stat, watcher);
    }

    /**
     * check if the path is special or not
     * @param path the input path
     * @return true if path is special and false if not
     */
    boolean isSpecialPath(EString path) {
        return dataTree->isSpecialPath(path);
    }

    /**
     * get the acl size of the datatree
     * @return the acl size of the datatree
     */
    int getAclSize() {
        return dataTree->aclCacheSize();
    }

    /**
     * Truncate the ZKDatabase to the specified zxid
     * @param zxid the zxid to truncate zk database to
     * @return true if the truncate is successful and false if not
     * @throws IOException
     */
    boolean truncateLog(llong zxid) THROWS(EIOException) {
        clear();

        // truncate the log
        boolean truncated = snapLog->truncateLog(zxid);

        if (!truncated) {
            return false;
        }

        loadDataBase();
        return true;
    }
    
    /**
     * deserialize a snapshot from an input archive 
     * @param ia the input archive you want to deserialize from
     * @throws IOException
     */
    void deserializeSnapshot(EInputArchive* ia) THROWS(EIOException) {
        clear();
        SerializeUtils::deserializeSnapshot(getDataTree(), ia, getSessionWithTimeOuts());
        initialized = true;
    }   
    
    /**
     * serialize the snapshot
     * @param oa the output archive to which the snapshot needs to be serialized
     * @throws IOException
     * @throws InterruptedException
     */
    void serializeSnapshot(EOutputArchive* oa) THROWS2(EIOException, EInterruptedException) {
        SerializeUtils::serializeSnapshot(getDataTree(), oa, getSessionWithTimeOuts());
    }

    /**
     * append to the underlying transaction log 
     * @param si the request to append
     * @return true if the append was succesfull and false if not
     */
    boolean append(sp<Request> si) THROWS(EIOException) {
        return this->snapLog->append(si);
    }

    /**
     * roll the underlying log
     */
    void rollLog() THROWS(EIOException) {
    	this->snapLog->rollLog();
    }

    /**
     * commit to the underlying transaction log
     * @throws IOException
     */
    void commit() THROWS(EIOException) {
    	this->snapLog->commit();
    }
    
    /**
     * close this database. free the resources
     * @throws IOException
     */
    void close() THROWS(EIOException) {
    	this->snapLog->close();
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZKDatabase_HH_ */
