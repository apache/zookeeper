/*
 * FileTxnSnapLog.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef FileTxnSnapLog_HH_
#define FileTxnSnapLog_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./SnapShot.hh"
#include "./TxnLog.hh"
#include "./SnapShot.hh"
#include "../DataTree.hh"
#include "../Request.hh"
#include "../ZooTrace.hh"
#include "../../KeeperException.hh"
#include "../../ZooDefs.hh"
#include "../../txn/TxnHeader.hh"
#include "../../txn/CreateSessionTxn.hh"
#include "../../../jute/inc/ERecord.hh"
#include "../../../jute/inc/EBinaryInputArchive.hh"
#include "../../../jute/inc/EBinaryOutputArchive.hh"
#include "../../../jute/inc/ECsvOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 * This is a helper class 
 * above the implementations 
 * of txnlog and snapshot 
 * classes
 */
class FileTxnSnapLog : public EObject {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(FileTxnSnapLog.class);

    //the direcotry containing the 
    //the transaction logs
    EFile dataDir_;
    //the directory containing the
    //the snapshot directory
    EFile snapDir_;
    sp<TxnLog> txnLog;
    sp<SnapShot> snapLog;

public:
    static const int VERSION = 2;
    constexpr static const char* version = "version-";
    
    /**
     * This listener helps
     * the external apis calling
     * restore to gather information
     * while the data is being 
     * restored.
     */
    interface PlayBackListener : virtual EObject {
    	virtual ~PlayBackListener() {}
    	virtual void onTxnLoaded(sp<TxnHeader> hdr, sp<ERecord> rec) = 0;
    };
    
public:
    /**
     * the constructor which takes the datadir and 
     * snapdir.
     * @param dataDir the trasaction directory
     * @param snapDir the snapshot directory
     */
    FileTxnSnapLog(EFile* dataDir, EFile* snapDir) THROWS(EIOException);
    
    /**
     * get the datadir used by this filetxn
     * snap log
     * @return the data dir
     */
    EFile* getDataDir() {
        return &dataDir_;
    }
    
    /**
     * get the snap dir used by this 
     * filetxn snap log
     * @return the snap dir
     */
    EFile* getSnapDir() {
        return &snapDir_;
    }
    
    /**
     * this function restores the server 
     * database after reading from the 
     * snapshots and transaction logs
     * @param dt the datatree to be restored
     * @param sessions the sessions to be restored
     * @param listener the playback listener to run on the 
     * database restoration
     * @return the highest zxid restored
     * @throws IOException
     */
    llong restore(sp<DataTree> dt, EConcurrentHashMap<llong, EInteger>* sessions,
            PlayBackListener* listener) THROWS(EIOException);
    
    /**
     * process the transaction on the datatree
     * @param hdr the hdr of the transaction
     * @param dt the datatree to apply transaction to
     * @param sessions the sessions to be restored
     * @param txn the transaction to be applied
     */
    void processTransaction(TxnHeader* hdr, DataTree* dt,
    		EConcurrentHashMap<llong, EInteger>* sessions, ERecord* txn)
        	THROWS(NoNodeException);

    /**
     * the last logged zxid on the transaction logs
     * @return the last logged zxid
     */
    llong getLastLoggedZxid();

    /**
     * save the datatree and the sessions into a snapshot
     * @param dataTree the datatree to be serialized onto disk
     * @param sessionsWithTimeouts the sesssion timeouts to be
     * serialized onto disk
     * @throws IOException
     */
    void save(sp<DataTree> dataTree,
            EConcurrentHashMap<llong, EInteger>* sessionsWithTimeouts)
        	THROWS(EIOException);

    /**
     * truncate the transaction logs the zxid
     * specified
     * @param zxid the zxid to truncate the logs to
     * @return true if able to truncate the log, false if not
     * @throws IOException
     */
    boolean truncateLog(llong zxid) THROWS(EIOException);
    
    /**
     * the most recent snapshot in the snapshot
     * directory
     * @return the file that contains the most 
     * recent snapshot
     * @throws IOException
     */
    sp<EFile> findMostRecentSnapshot() THROWS(EIOException);
    
    /**
     * the n most recent snapshots
     * @param n the number of recent snapshots
     * @return the list of n most recent snapshots, with
     * the most recent in front
     * @throws IOException
     */
    sp<EList<EFile*> > findNRecentSnapshots(int n) THROWS(EIOException);

    /**
     * get the snapshot logs which may contain transactions newer than the given zxid.
     * This includes logs with starting zxid greater than given zxid, as well as the
     * newest transaction log with starting zxid less than given zxid.  The latter log
     * file may contain transactions beyond given zxid.
     * @param zxid the zxid that contains logs greater than
     * zxid
     * @return
     */
    sp<EArray<EFile*> > getSnapshotLogs(llong zxid);

    /**
     * append the request to the transaction logs
     * @param si the request to be appended
     * returns true iff something appended, otw false 
     * @throws IOException
     */
    boolean append(sp<Request> si) THROWS(EIOException);

    /**
     * commit the transaction of logs
     * @throws IOException
     */
    void commit() THROWS(EIOException) {
        txnLog->commit();
    }

    /**
     * roll the transaction logs
     * @throws IOException 
     */
    void rollLog() THROWS(EIOException) {
        txnLog->rollLog();
    }
    
    /**
     * close the transaction log files
     * @throws IOException
     */
    void close() THROWS(EIOException) {
        txnLog->close();
        snapLog->close();
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* FileTxnSnapLog_HH_ */
