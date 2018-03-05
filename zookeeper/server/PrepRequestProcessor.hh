/*
 * PrepRequestProcessor.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef PrepRequestProcessor_HH_
#define PrepRequestProcessor_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "../Op.hh"
#include "../CreateMode.hh"
#include "../ZooDefs.hh"
#include "../MultiTransactionRecord.hh"
#include "../KeeperException.hh"
#include "../common/TimeUtils.hh"
#include "../common/PathUtils.hh"
#include "../data/ACL.hh"
#include "../data/Id.hh"
#include "../data/StatPersisted.hh"
#include "../txn/CreateSessionTxn.hh"
#include "../txn/CreateTxn.hh"
#include "../txn/DeleteTxn.hh"
#include "../txn/ErrorTxn.hh"
#include "../txn/SetACLTxn.hh"
#include "../txn/SetDataTxn.hh"
#include "../txn/CheckVersionTxn.hh"
#include "../txn/Txn.hh"
#include "../txn/MultiTxn.hh"
#include "../txn/TxnHeader.hh"
#include "../proto/CreateRequest.hh"
#include "../proto/DeleteRequest.hh"
#include "../proto/SetACLRequest.hh"
#include "../proto/SetDataRequest.hh"
#include "../proto/CheckVersionRequest.hh"
#include "./ZooKeeperServer.hh"
#include "./auth/AuthenticationProvider.hh"
#include "./auth/ProviderRegistry.hh"
#include "../../jute/inc/ERecord.hh"
#include "../../jute/inc/EBinaryOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 * This request processor is generally at the start of a RequestProcessor
 * change. It sets up any transactions associated with requests that change the
 * state of the system. It counts on ZooKeeperServer to update
 * outstandingRequests, so that it can take into account transactions that are
 * in the queue to be applied when generating a transaction.
 */
class PrepRequestProcessor : public ZooKeeperCriticalThread, virtual public RequestProcessor {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(PrepRequestProcessor.class);

	static boolean skipACL;

	void validatePath(EString path, llong sessionId) THROWS(BadArgumentsException);

    /**
     * This method checks out the acl making sure it isn't null or empty,
     * it has valid schemes and ids, and expanding any relative ids that
     * depend on the requestor's authentication information.
     *
     * @param authInfo list of ACL IDs associated with the client connection
     * @param acl list of ACLs being assigned to the node (create or setACL operation)
     * @return
     */
    boolean fixupACL(sp<EList<sp<Id> > > authInfo, sp<EList<sp<ACL> > > acl);

	sp<EList<sp<ACL> > > removeDuplicates(sp<EList<sp<ACL> > > acl);

public:
	DECLARE_STATIC_INITZZ;

public:
    ELinkedBlockingQueue<Request> submittedRequests;// = new LinkedBlockingQueue<Request>();

    sp<RequestProcessor> nextProcessor;

    sp<ZooKeeperServer> zks;

    PrepRequestProcessor(sp<ZooKeeperServer> zks,
            sp<RequestProcessor> nextProcessor);

    virtual void run();

    sp<ZooKeeperServer::ChangeRecord> getRecordForPath(EString path) THROWS(NoNodeException);

	sp<ZooKeeperServer::ChangeRecord> getOutstandingChange(EString path);

    void addChangeRecord(sp<ZooKeeperServer::ChangeRecord> c);

    /**
     * Grab current pending change records for each op in a multi-op.
     * 
     * This is used inside MultiOp error code path to rollback in the event
     * of a failed multi-op.
     *
     * @param multiRequest
     * @return a map that contains previously existed records that probably need to be
     *         rolled back in any failure.
     */
    sp<EHashMap<sp<EString>, sp<ZooKeeperServer::ChangeRecord> > > getPendingChanges(MultiTransactionRecord* multiRequest);

    /**
     * Rollback pending changes records from a failed multi-op.
     *
     * If a multi-op fails, we can't leave any invalid change records we created
     * around. We also need to restore their prior value (if any) if their prior
     * value is still valid.
     *
     * @param zxid
     * @param pendingChangeRecords
     */
	void rollbackPendingChanges(llong zxid,
			sp<EHashMap<sp<EString>, sp<ZooKeeperServer::ChangeRecord> > > pendingChangeRecords);

    /**
     * This method will be called inside the ProcessRequestThread, which is a
     * singleton, so there will be a single thread calling this code.
     *
     * @param type
     * @param zxid
     * @param request
     * @param record
     */
    void pRequest2Txn(int type, llong zxid, sp<Request> request, ERecord* record, boolean deserialize)
        THROWS3(KeeperException, EIOException, RequestProcessorException);

    /**
     * This method will be called inside the ProcessRequestThread, which is a
     * singleton, so there will be a single thread calling this code.
     *
     * @param request
     */
    void pRequest(sp<Request> request) THROWS(RequestProcessorException);

    void processRequest(sp<Request> request);

    void shutdown();

    static void checkACL(sp<ZooKeeperServer> zks, sp<EList<sp<ACL> > > acl, int perm,
            sp<EList<sp<Id> > > ids) THROWS(NoAuthException);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* PrepRequestProcessor_HH_ */
