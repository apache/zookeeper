/*
 * ZooKeeperServerState.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ZooKeeperServerState_HH_
#define ZooKeeperServerState_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

//@see: ZooKeeperServer.java#Stat

enum State {
	INITIAL, RUNNING, SHUTDOWN, ERROR
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZooKeeperServerState_HH_ */
