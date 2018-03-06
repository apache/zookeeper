/*
 * StateSummary.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef StateSummary_HH_
#define StateSummary_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

/**
 * This class encapsulates the state comparison logic. Specifically,
 * how two different states are compared.
 */
class StateSummary: public EObject {
private:
	llong currentEpoch;
	llong lastZxid;

public:
	StateSummary(llong currentEpoch, llong lastZxid) {
		this->currentEpoch = currentEpoch;
		this->lastZxid = lastZxid;
	}
	
	llong getCurrentEpoch() {
		return currentEpoch;
	}
	
	llong getLastZxid() {
		return lastZxid;
	}
	
	boolean isMoreRecentThan(sp<StateSummary> ss) {
		return (currentEpoch > ss->currentEpoch) || (currentEpoch == ss->currentEpoch && lastZxid > ss->lastZxid);
	}

	virtual boolean equals(EObject* obj) {
		StateSummary* ss = dynamic_cast<StateSummary*>(obj);
		if (!ss) {
			return false;
		}
		return currentEpoch == ss->currentEpoch && lastZxid == ss->lastZxid;
	}
	
	virtual int hashCode() {
		return (int)(currentEpoch ^ lastZxid);
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* StateSummary_HH_ */
