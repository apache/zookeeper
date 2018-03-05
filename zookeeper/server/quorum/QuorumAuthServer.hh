/*
 * QuorumAuthServer.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef QuorumAuthServer_HH_
#define QuorumAuthServer_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

/**
 * Interface for quorum server authentication mechanisms.
 */
interface QuorumAuthServer: public virtual EObject {
	virtual ~QuorumAuthServer() {}

    /**
     * Performs an authentication step for the given socket connection.
     *
     * @param sock
     *            socket connection to other quorum peer
     * @param din
     *            stream used to read auth data send by the quorum learner
     * @throws IOException if the server fails to authenticate connecting quorum learner
     */
	virtual void authenticate(sp<ESocket> sock, sp<EDataInputStream> din) THROWS(EIOException) = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumAuthServer_HH_ */

