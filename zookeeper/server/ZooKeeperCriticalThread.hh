/*
 * ZooKeeperCriticalThread.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef ZooKeeperCriticalThread_HH_
#define ZooKeeperCriticalThread_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./ExitCode.hh"
#include "./ZooKeeperThread.hh"
#include "./ZooKeeperServerListener.hh"

namespace efc {
namespace ezk {

/**
 * Represents critical thread. When there is an uncaught exception thrown by the
 * thread this will exit the system.
 */
class ZooKeeperCriticalThread : public ZooKeeperThread {
public:
	ZooKeeperCriticalThread(EString threadName,
			ZooKeeperServerListener* listener) :
			ZooKeeperThread(threadName) {
		this->listener = listener;
	}

protected:
	/**
     * This will be used by the uncaught exception handler and make the system
     * exit.
     * 
     * @param threadName
     *            - thread name
     * @param e
     *            - exception object
     */
    virtual void handleException(EString threadName, EThrowable& e) {
        LOG->error(("Severe unrecoverable error, from thread : " + threadName).c_str(), e);
        listener->notifyStopping(threadName, ExitCode::UNEXPECTED_ERROR);
    }

private:
    static sp<ELogger> LOG;// = LoggerFactory.getLogger(ZooKeeperCriticalThread.class);

    ZooKeeperServerListener* listener;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZooKeeperCriticalThread_HH_ */
