/*
 * LearnerSyncRequest.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef LearnerSyncRequest_HH_
#define LearnerSyncRequest_HH_

#include "Efc.hh"

#include "./LearnerHandler.hh"
#include "../Request.hh"

namespace efc {
namespace ezk {

class LearnerSyncRequest : public Request {
public:
	sp<LearnerHandler> fh;

	LearnerSyncRequest(sp<LearnerHandler> fh, llong sessionId, int xid, int type,
			sp<EIOByteBuffer> bb, sp<EList<sp<Id> > > authInfo) :
				Request(null, sessionId, xid, type, bb, authInfo) {
		this->fh = fh;
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* LearnerSyncRequest_HH_ */
