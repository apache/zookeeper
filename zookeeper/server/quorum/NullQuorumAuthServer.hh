/*
 * NullQuorumAuthServer.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef NullQuorumAuthServer_HH_
#define NullQuorumAuthServer_HH_

#include "Efc.hh"
#include "./QuorumAuthServer.hh"

namespace efc {
namespace ezk {

/**
 * This class represents no authentication server, it just return
 * without performing any authentication.
 */
class NullQuorumAuthServer : public QuorumAuthServer {
public:
	virtual ~NullQuorumAuthServer() {}

	virtual void authenticate(sp<ESocket> sock, sp<EDataInputStream> din) {
        return; // simply return don't require auth
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* NullQuorumAuthServer_HH_ */
