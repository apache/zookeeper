/*
 * Info.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef INFO_HH_
#define INFO_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

interface Info {
	virtual ~Info(){}

	static const int MAJOR=3;
	static const int MINOR=4;
	static const int MICRO=11;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* INFO_HH_ */
