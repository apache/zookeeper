/*
 * ReadOnlyZooKeeperServer.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ReadOnlyZooKeeperServer_HH_
#define ReadOnlyZooKeeperServer_HH_

#include "./QuorumZooKeeperServer.hh"
#include "../ZKDatabase.hh"
#include "../FinalRequestProcessor.hh"
#include "../PrepRequestProcessor.hh"
#include "../persistence/FileTxnSnapLog.hh"

namespace efc {
namespace ezk {

/**
 * A ZooKeeperServer which comes into play when peer is partitioned from the
 * majority. Handles read-only clients, but drops connections from not-read-only
 * ones.
 * <p>
 * The very first processor in the chain of request processors is a
 * ReadOnlyRequestProcessor which drops state-changing requests.
 */
class ReadOnlyZooKeeperServer : public QuorumZooKeeperServer {
public:
	volatile boolean shutdown_;// = false;

    ReadOnlyZooKeeperServer(sp<FileTxnSnapLog> logFactory, QuorumPeer* self,
            sp<ZKDatabase> zkDb);

    virtual void setupRequestProcessors();

    synchronized
    virtual void startup();

    virtual void setState(State state) {
        this->state = state;
    }

    virtual EString getState() {
        return "read-only";
    }

    /**
     * Returns the id of the associated QuorumPeer, which will do for a unique
     * id of this server.
     */
    virtual llong getServerId();

    synchronized
    virtual void shutdown();
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ReadOnlyZooKeeperServer_HH_ */
