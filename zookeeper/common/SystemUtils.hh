/*
 * SystemUtils.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef SystemUtils_HH_
#define SystemUtils_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

class SystemUtils {
public:
	/**
	 * Convert c main args to java main args array.
	 */
	static sp<EA<EString*> > cargs2jargs(int argc, const char** argv);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* SystemUtils_HH_ */
