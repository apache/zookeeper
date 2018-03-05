/*
 * ZooKeeperServerShutdownHandler.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ZooKeeperServerShutdownHandler_HH_
#define ZooKeeperServerShutdownHandler_HH_

#include "Efc.hh"
#include "./ZooKeeperServerState.hh"

namespace efc {
namespace ezk {

/**
 * ZooKeeper server shutdown handler which will be used to handle ERROR or
 * SHUTDOWN server state transitions, which in turn releases the associated
 * shutdown latch.
 */
class ZooKeeperServerShutdownHandler : public EObject {
private:
	ECountDownLatch* shutdownLatch;

public:
    ZooKeeperServerShutdownHandler(ECountDownLatch* shutdownLatch) {
        this->shutdownLatch = shutdownLatch;
    }

    /**
     * This will be invoked when the server transition to a new server state.
     *
     * @param state new server state
     */
    void handle(State state) {
        if (state == State::ERROR || state == State::SHUTDOWN) {
            shutdownLatch->countDown();
        }
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZooKeeperServerShutdownHandler_HH_ */
