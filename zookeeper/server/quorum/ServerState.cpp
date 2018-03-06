/*
 * ServerState.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./ServerState.hh"

namespace efc {
namespace ezk {

const char* getStateName(ServerState ss) {
	switch (ss) {
	case ServerState::FOLLOWING:
		return "FOLLOWING";
	case ServerState::LEADING:
		return "LEADING";
	case ServerState::LOOKING:
		return "LOOKING";
	case ServerState::OBSERVING:
		return "OBSERVING";
	default:
		ES_ASSERT(false);
		return "unknown";
	}
}

} /* namespace ezk */
} /* namespace efc */
