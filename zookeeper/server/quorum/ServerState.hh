/*
 * ServerState.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ServerState_HH_
#define ServerState_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

enum ServerState {
	LOOKING, FOLLOWING, LEADING, OBSERVING
};

const char* getStateName(ServerState ss);

} /* namespace ezk */
} /* namespace efc */
#endif /* ServerState_HH_ */
