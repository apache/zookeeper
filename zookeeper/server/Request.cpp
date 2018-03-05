/*
 * Request.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./Request.hh"

namespace efc {
namespace ezk {

sp<ELogger> Request::LOG = ELoggerManager::getLogger("Request");

sp<Request> Request::requestOfDeath = new Request(null, 0, 0, 0, null, null);

} /* namespace ezk */
} /* namespace efc */
