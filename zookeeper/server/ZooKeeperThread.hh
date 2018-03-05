/*
 * ZooKeeperThread.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ZooKeeperThread_HH_
#define ZooKeeperThread_HH_

#include "Efc.hh"
#include "ELog.hh"

namespace efc {
namespace ezk {


/**
 * This is the main class for catching all the uncaught exceptions thrown by the
 * threads.
 */
class ZooKeeperUncaughtExceptionHandler : public EThread::UncaughtExceptionHandler {
public:
	ZooKeeperUncaughtExceptionHandler(sp<ELogger> log) : LOG(log) {
	}

	/**
	 * This will be used by the uncaught exception handler and just log a
	 * warning message and return.
	 *
	 * @param thName
	 *            - thread name
	 * @param e
	 *            - exception object
	 */
	virtual void uncaughtException(EThread* t, EThrowable* e) {
		if (e) {
			LOG->warn((EString("Exception occurred from thread ") + t->getName()).c_str(), *e);
		} else {
			LOG->warn((EString("Exception occurred from thread ") + t->getName()).c_str());
		}
	}

private:
	sp<ELogger> LOG;
};

class ZooKeeperThread : public EThread, public ESynchronizeable {
public:
    ZooKeeperThread(sp<ERunnable> thread, EString threadName) :
    	EThread(thread, threadName.c_str()), uncaughtExceptionalHandler(LOG) {
        setUncaughtExceptionHandler(&uncaughtExceptionalHandler);
    }

    ZooKeeperThread(EString threadName) :
    	EThread(threadName.c_str()), uncaughtExceptionalHandler(LOG) {
        setUncaughtExceptionHandler(&uncaughtExceptionalHandler);
    }

protected:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(ZooKeeperThread.class);

    /**
     * This will be used by the uncaught exception handler and just log a
     * warning message and return.
     * 
     * @param thName
     *            - thread name
     * @param e
     *            - exception object
     */
    virtual void handleException(EString thName, EThrowable& e) {
        LOG->warn(("Exception occurred from thread " + thName).c_str(), e);
    }

private:
    friend class ZooKeeperUncaughtExceptionHandler;

    ZooKeeperUncaughtExceptionHandler uncaughtExceptionalHandler;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZooKeeperThread_HH_ */
