/*
 * ZxidUtils.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef ZxidUtils_HH_
#define ZxidUtils_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

class ZxidUtils {
public:
	static llong getEpochFromZxid(llong zxid) {
		return zxid >> 32L;
	}
	static llong getCounterFromZxid(llong zxid) {
		return zxid & 0xffffffffL;
	}
	static llong makeZxid(llong epoch, llong counter) {
		return (epoch << 32L) | (counter & 0xffffffffL);
	}
	static EString zxidToString(llong zxid) {
		return ELLong::toHexString(zxid);
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZxidUtils_HH_ */
