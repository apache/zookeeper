/*
 * UnimplementedRequestProcessor.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef UnimplementedRequestProcessor_HH_
#define UnimplementedRequestProcessor_HH_

#include "Efc.hh"

#include "./RequestProcessor.hh"
#include "../KeeperException.hh"
#include "../proto/ReplyHeader.hh"

namespace efc {
namespace ezk {

/**
 * Manages the unknown requests (i.e. unknown OpCode), by:
 * - sending back the KeeperException.UnimplementedException() error code to the client
 * - closing the connection.
 */
class UnimplementedRequestProcessor : public RequestProcessor {
public:
	virtual void processRequest(sp<Request> request) THROWS(RequestProcessorException) {
		sp<KeeperException> ke = new UnimplementedException(__FILE__, __LINE__);
        request->setException(ke);
        sp<ReplyHeader> rh = new ReplyHeader(request->cxid, request->zxid, ke->code());
        try {
            request->cnxn->sendResponse(rh, null, "response");
        } catch (EIOException& e) {
            throw RequestProcessorException(__FILE__, __LINE__, "Can't send the response", &e);
        }

        request->cnxn->sendCloseSession();
    }

	virtual void shutdown() {
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* UnimplementedRequestProcessor_HH_ */
