/*
 * Proposal.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef Proposal_HH_
#define Proposal_HH_

#include "Efc.hh"

#include "./QuorumPacket.hh"
#include "../Request.hh"

namespace efc {
namespace ezk {

//@see: Leader.java#Proposal

class Proposal : public EObject {
public:
	sp<QuorumPacket> packet;

	template<typename T>
	class HashSet: public EHashSet<T>, public ESynchronizeable {
	};
	HashSet<llong> ackSet;// = new HashSet<Long>();

	sp<Request> request;

	virtual EString toString() {
		return EString() + packet->getType() + ", " + packet->getZxid() + ", " + request->toString();
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Proposal_HH_ */
