/*
 * QuorumServer.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef QuorumServer_HH_
#define QuorumServer_HH_

#include "Efc.hh"
#include "ELog.hh"
#include "./QuorumVerifier.hh"

namespace efc {
namespace ezk {

//@see: QuorumPeer.java#QuorumServer

class QuorumServer : public EObject {
private:
	static sp<ELogger> LOG;

public:
	/*
	 * A peer can either be participating, which implies that it is willing to
	 * both vote in instances of consensus and to elect or become a Leader, or
	 * it may be observing in which case it isn't.
	 *
	 * We need this distinction to decide which ServerState to move to when
	 * conditions change (e.g. which state to become after LOOKING).
	 */
	enum LearnerType {
		PARTICIPANT, OBSERVER
	};


	sp<EInetSocketAddress> addr;

	sp<EInetSocketAddress> electionAddr;

	EString hostname;

	int port;//=2888;

	int electionPort;//=-1;

	llong id;

	LearnerType type;// = LearnerType.PARTICIPANT;

public:
	QuorumServer(llong id, EString hostname,
			int port, int electionPort,
			LearnerType type) :
				port(2888), electionPort(-1), id(0), type(PARTICIPANT) {
		this->id = id;
		this->hostname = hostname;
		if (port != -1) {
			this->port = port;
		}
		if (electionPort != -1) {
			this->electionPort = electionPort;
		}
		this->type = type;
		this->recreateSocketAddresses();
	}

	/**
	 * Performs a DNS lookup of hostname and (re)creates the this.addr and
	 * this.electionAddr InetSocketAddress objects as appropriate
	 *
	 * If the DNS lookup fails, this.addr and electionAddr remain
	 * unmodified, unless they were never set. If this.addr is null, then
	 * it is set with an unresolved InetSocketAddress object. this.electionAddr
	 * is handled similarly.
	 */
	void recreateSocketAddresses();

	/**
	 * Resolve the hostname to IP addresses, and find one reachable address.
	 *
	 * @param hostname the name of the host
	 * @param timeout the time, in milliseconds, before {@link InetAddress#isReachable}
	 *                aborts
	 * @return a reachable IP address. If no such IP address can be found,
	 *         just return the first IP address of the hostname.
	 *
	 * @exception UnknownHostException
	 */
	EInetAddress getReachableAddress(EString hostname, int timeout) THROWS(EUnknownHostException) {
		EArray<EInetAddress*> addresses = EInetAddress::getAllByName(hostname.c_str());
		auto iter = addresses.iterator();
		while (iter->hasNext()) {
			EInetAddress* a = iter->next();
			try {
				if (a->isReachable(timeout)) {
					return *a;
				}
			} catch (EIOException& e) {
				LOG->warn(EString::formatOf("IP address %s is unreachable", a->toString().c_str()));
			}
		}
		// All the IP addresses are unreachable, just return the first one.
		return *addresses[0];
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumServer_HH_ */
