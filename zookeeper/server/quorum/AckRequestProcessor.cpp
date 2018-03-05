/*
 * AckRequestProcessor.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./AckRequestProcessor.hh"
#include "./QuorumPeer.hh"
#include "./Leader.hh"

namespace efc {
namespace ezk {

sp<ELogger> AckRequestProcessor::LOG = ELoggerManager::getLogger("AckRequestProcessor");

void AckRequestProcessor::processRequest(sp<Request> request) {
	QuorumPeer* self = leader->self;
	if(self != null)
		leader->processAck(self->getId(), request->zxid, null);
	else
		LOG->error("Null QuorumPeer");
}

} /* namespace ezk */
} /* namespace efc */
