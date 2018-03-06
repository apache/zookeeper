/*
 * QuorumVerifier.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef QuorumVerifier_HH_
#define QuorumVerifier_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

interface QuorumVerifier: virtual public EObject {
	virtual ~QuorumVerifier(){}

	virtual llong getWeight(llong id) = 0;
	virtual boolean containsQuorum(EHashSet<llong>* set) = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumVerifier_HH_ */
