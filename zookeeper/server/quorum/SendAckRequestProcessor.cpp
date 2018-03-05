/*
 * SendAckRequestProcessor.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./SendAckRequestProcessor.hh"
#include "./QuorumPacket.hh"
#include "./Learner.hh"

namespace efc {
namespace ezk {

sp<ELogger> SendAckRequestProcessor::LOG = ELoggerManager::getLogger("SendAckRequestProcessor");

void SendAckRequestProcessor::processRequest(sp<Request> si) {
	if(si->type != ZooDefs::OpCode::sync){
		sp<QuorumPacket> qp = new QuorumPacket(PacketType::ACK, si->hdr->getZxid(), null,
			null);
		try {
			learner->writePacket(qp, false);
		} catch (EIOException& e) {
			LOG->warn("Closing connection to leader, exception during packet send", e);
			try {
				if (!learner->sock->isClosed()) {
					learner->sock->close();
				}
			} catch (EIOException& e1) {
				// Nothing to do, we are shutting things down, so an exception here is irrelevant
				LOG->debug("Ignoring error closing the connection", e1);
			}
		}
	}
}

void SendAckRequestProcessor::flush() THROWS(EIOException) {
	try {
		learner->writePacket(null, true);
	} catch(EIOException& e) {
		LOG->warn("Closing connection to leader, exception during packet send", e);
		try {
			if (!learner->sock->isClosed()) {
				learner->sock->close();
			}
		} catch (EIOException& e1) {
				// Nothing to do, we are shutting things down, so an exception here is irrelevant
				LOG->debug("Ignoring error closing the connection", e1);
		}
	}
}

} /* namespace ezk */
} /* namespace efc */
