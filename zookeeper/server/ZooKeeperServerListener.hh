/*
 * ZooKeeperServerListener.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ZooKeeperServerListener_HH_
#define ZooKeeperServerListener_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

/**
 * Listener for the critical resource events.
 */
interface ZooKeeperServerListener : virtual public EObject {
	virtual ~ZooKeeperServerListener() {}

    /**
     * This will notify the server that some critical thread has stopped. It
     * usually takes place when fatal error occurred.
     * 
     * @param threadName
     *            - name of the thread
     * @param errorCode
     *            - error code
     */
	virtual void notifyStopping(EString threadName, int errorCode) = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZooKeeperServerListener_HH_ */
