/*
 * QuorumZooKeeperServer.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./QuorumZooKeeperServer.hh"
#include "./QuorumPeer.hh"

namespace efc {
namespace ezk {

void QuorumZooKeeperServer::dumpConf(EPrintStream* pwriter) {
	ZooKeeperServer::dumpConf(pwriter);

	pwriter->print("initLimit=");
	pwriter->println(self->getInitLimit());
	pwriter->print("syncLimit=");
	pwriter->println(self->getSyncLimit());
	pwriter->print("electionAlg=");
	pwriter->println(self->getElectionType());
	pwriter->print("electionPort=");
	pwriter->println(self->quorumPeers->get(self->getId())->electionAddr
			->getPort());
	pwriter->print("quorumPort=");
	pwriter->println(self->quorumPeers->get(self->getId())->addr->getPort());
	pwriter->print("peerType=");
	pwriter->println(self->getLearnerType());
}

} /* namespace ezk */
} /* namespace efc */
