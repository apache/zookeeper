/*
 * FinalRequestProcessor.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef FinalRequestProcessor_HH_
#define FinalRequestProcessor_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./DataTree.hh"
#include "./RequestProcessor.hh"
#include "./ZooKeeperServer.hh"
#include "./PrepRequestProcessor.hh"
#include "../Op.hh"
#include "../OpResult.hh"
#include "../MultiTransactionRecord.hh"
#include "../KeeperException.hh"
#include "../MultiResponse.hh"
#include "../ZooDefs.hh"
#include "../common/TimeUtils.hh"
#include "../data/ACL.hh"
#include "../data/Stat.hh"
#include "../txn/CreateSessionTxn.hh"
#include "../txn/ErrorTxn.hh"
#include "../txn/TxnHeader.hh"
#include "../proto/CreateResponse.hh"
#include "../proto/ExistsRequest.hh"
#include "../proto/ExistsResponse.hh"
#include "../proto/GetACLRequest.hh"
#include "../proto/GetACLResponse.hh"
#include "../proto/GetChildren2Request.hh"
#include "../proto/GetChildren2Response.hh"
#include "../proto/GetChildrenRequest.hh"
#include "../proto/GetChildrenResponse.hh"
#include "../proto/GetDataRequest.hh"
#include "../proto/GetDataResponse.hh"
#include "../proto/ReplyHeader.hh"
#include "../proto/SetACLResponse.hh"
#include "../proto/SetDataResponse.hh"
#include "../proto/SetWatches.hh"
#include "../proto/SyncRequest.hh"
#include "../proto/SyncResponse.hh"
#include "../../jute/inc/ERecord.hh"

namespace efc {
namespace ezk {

/**
 * This Request processor actually applies any transaction associated with a
 * request and services any queries. It is always at the end of a
 * RequestProcessor chain (hence the name), so it does not have a nextProcessor
 * member.
 *
 * This RequestProcessor counts on ZooKeeperServer to populate the
 * outstandingRequests member of ZooKeeperServer.
 */
class FinalRequestProcessor : virtual public RequestProcessor {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(FinalRequestProcessor.class);

public:
    sp<ZooKeeperServer> zks;

    virtual ~FinalRequestProcessor() {
    }

    FinalRequestProcessor(sp<ZooKeeperServer> zks) {
        this->zks = zks;
    }

    virtual void processRequest(sp<Request> request) THROWS(RequestProcessorException);

    virtual void shutdown() {
        // we are the final link in the chain
        LOG->info("shutdown of request processor complete");
    }

};

} /* namespace ezk */
} /* namespace efc */
#endif /* FinalRequestProcessor_HH_ */
