/*
 * Election.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef Election_HH_
#define Election_HH_

#include "Efc.hh"
#include "./Vote.hh"

namespace efc {
namespace ezk {

interface Election: public virtual ESynchronizeable {
	virtual ~Election(){}
	virtual sp<Vote> lookForLeader() THROWS(EInterruptedException) = 0;
	virtual void shutdown() = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Election_HH_ */
