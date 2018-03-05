/*
 * NullQuorumAuthLearner.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef NullQuorumAuthLearner_HH_
#define NullQuorumAuthLearner_HH_

#include "Efc.hh"
#include "./QuorumAuthLearner.hh"

namespace efc {
namespace ezk {

/**
 * This class represents no authentication learner, it just return
 * without performing any authentication.
 */
class NullQuorumAuthLearner : public QuorumAuthLearner {
public:
	virtual ~NullQuorumAuthLearner(){}

    virtual void authenticate(sp<ESocket> sock, EString hostname) THROWS(EIOException) {
        return; // simply return don't require auth
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* NullQuorumAuthLearner_HH_ */
