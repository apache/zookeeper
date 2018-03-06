/*
 * ServerCnxnFactory.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./ServerCnxnFactory.hh"
#include "./ZooKeeperServer.hh"
#include "./NIOServerCnxnFactory.hh"

namespace efc {
namespace ezk {

sp<ELogger> ServerCnxnFactory::LOG = ELoggerManager::getLogger("ServerCnxnFactory");

sp<EIOByteBuffer> ServerCnxnFactory::closeConn = EIOByteBuffer::allocate(0);

//=============================================================================

ServerCnxnFactory::ServerCnxnFactory() {
	zkServer = null;
}

sp<ZooKeeperServer> ServerCnxnFactory::getZooKeeperServer() {
	return zkServer;
}

void ServerCnxnFactory::setZooKeeperServer(sp<ZooKeeperServer> zk) {
	this->zkServer = zk;
	if (zk != null) {
		zk->setServerCnxnFactory(this);
	}
}

ServerCnxnFactory* ServerCnxnFactory::createFactory() THROWS(EIOException) {
	return new NIOServerCnxnFactory();
}

} /* namespace ezk */
} /* namespace efc */
