/*
 * QuorumZooKeeperServer.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef QuorumZooKeeperServer_HH_
#define QuorumZooKeeperServer_HH_

#include "../ZKDatabase.hh"
#include "../ZooKeeperServer.hh"
#include "../persistence/FileTxnSnapLog.hh"

namespace efc {
namespace ezk {

/**
 * Abstract base class for all ZooKeeperServers that participate in
 * a quorum.
 */

class QuorumPeer;

abstract class QuorumZooKeeperServer : public ZooKeeperServer {
public:
    QuorumPeer* self;

    QuorumZooKeeperServer(sp<FileTxnSnapLog> logFactory, int tickTime,
            int minSessionTimeout, int maxSessionTimeout,
            sp<ZKDatabase> zkDb, QuorumPeer* self) :
			ZooKeeperServer(logFactory, tickTime, minSessionTimeout, maxSessionTimeout, zkDb)
    {
        this->self = self;
    }

    virtual void dumpConf(EPrintStream* pwriter);

    virtual void setState(State state) {
        this->state = state;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumZooKeeperServer_HH_ */
