/*
 * ZooTrace.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./ZooTrace.hh"
#include "./quorum/LearnerHandler.hh"

namespace efc {
namespace ezk {

sp<ELogger> ZooTrace::LOG = ELoggerManager::getLogger("ZooTrace");

long ZooTrace::traceMask = CLIENT_REQUEST_TRACE_MASK
            | SERVER_PACKET_TRACE_MASK | SESSION_TRACE_MASK
            | WARNING_TRACE_MASK;

void ZooTrace::logQuorumPacket(sp<ELogger>& log, long mask,
		char direction, sp<QuorumPacket> qp)
{
	if (isTraceEnabled(log, mask)) {
		logTraceMessage(log, mask, EString(direction) +
				" " + LearnerHandler::packetToString(qp));
	 }
}

} /* namespace ezk */
} /* namespace efc */
