/*
 * QuorumAuthLearner.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef QuorumAuthLearner_HH_
#define QuorumAuthLearner_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

/**
 * Interface for quorum learner authentication mechanisms.
 */

interface QuorumAuthLearner: virtual public EObject {
	virtual ~QuorumAuthLearner(){}

	/**
	 * Performs an authentication step for the given socket connection.
	 *
	 * @param sock
	 *            socket connection to other quorum peer server
	 * @param hostname
	 *            host name of other quorum peer server
	 * @throws IOException
	 *             if there is an authentication failure
	 */
	virtual void authenticate(sp<ESocket> sock, EString hostname) THROWS(EIOException) = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumAuthLearner_HH_ */
