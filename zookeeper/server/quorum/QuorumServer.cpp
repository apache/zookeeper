/*
 * QuorumServer.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./QuorumServer.hh"

namespace efc {
namespace ezk {

sp<ELogger> QuorumServer::LOG = ELoggerManager::getLogger("QuorumServer");

void QuorumServer::recreateSocketAddresses() {
	EInetAddress address;// = null;
	try {
		// the time, in milliseconds, before {@link InetAddress#isReachable} aborts
		// in {@link #getReachableAddress}.
		int ipReachableTimeout = 0;
		EString ipReachableValue = ESystem::getProperty("zookeeper.ipReachableTimeout");
		if (!ipReachableValue.isEmpty()) {
			try {
				ipReachableTimeout = EInteger::parseInt(ipReachableValue.c_str());
			} catch (ENumberFormatException& e) {
				LOG->error(EString::formatOf("%s is not a valid number", ipReachableValue.c_str()));
			}
		}
		// zookeeper.ipReachableTimeout is not defined
		if (ipReachableTimeout <= 0) {
			address = EInetAddress::getByName(this->hostname.c_str());
		} else {
			address = getReachableAddress(this->hostname, ipReachableTimeout);
		}
		LOG->info(EString::formatOf("Resolved hostname: %s to address: %s", this->hostname.c_str(), address.toString().c_str()));
		this->addr = new EInetSocketAddress(&address, this->port);
		if (this->electionPort > 0){
			this->electionAddr = new EInetSocketAddress(&address, this->electionPort);
		}
	} catch (EUnknownHostException& ex) {
		LOG->warn("Failed to resolve address: " + this->hostname, ex);
		// Have we succeeded in the past?
		if (this->addr != null) {
			// Yes, previously the lookup succeeded. Leave things as they are
			return;
		}
		// The hostname has never resolved. Create our InetSocketAddress(es) as unresolved
		this->addr = new EInetSocketAddress(EInetSocketAddress::createUnresolved(this->hostname.c_str(), this->port));
		if (this->electionPort > 0){
			this->electionAddr = new EInetSocketAddress(EInetSocketAddress::createUnresolved(this->hostname.c_str(),
					this->electionPort));
		}
	}
}

} /* namespace ezk */
} /* namespace efc */
