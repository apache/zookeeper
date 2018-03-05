/*
 * QuorumStats.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef QuorumStats_HH_
#define QuorumStats_HH_

#include "Efc.hh"
#include "ELog.hh"
#include "./QuorumVerifier.hh"

namespace efc {
namespace ezk {

class QuorumStats: EObject {
    
public:
	interface Provider : virtual EObject {
		virtual ~Provider() {}
		#define UNKNOWN_STATE   "unknown"
		#define LOOKING_STATE   "leaderelection"
		#define LEADING_STATE   "leading"
		#define FOLLOWING_STATE "following"
		#define OBSERVING_STATE "observing"
        virtual EA<EString*> getQuorumPeers() = 0;
        virtual EString getServerState() = 0;
    };
    
public:
	Provider* provider;

    QuorumStats(Provider* provider) {
        this->provider = provider;
    }
    
public:
    EString getServerState(){
        return provider->getServerState();
    }
    
    EA<EString*> getQuorumPeers(){
        return provider->getQuorumPeers();
    }

	virtual EString toString() {
		EString sb;
		EString state = getServerState();
		if (state.equals(LEADING_STATE)) {
			sb.append("Followers:");
			EA<EString*> qps = getQuorumPeers();
			for (int i = 0; i < qps.length(); i++) {
				sb.append(" ").append(qps[i]);
			}
			sb.append("\n");
		} else if (state.equals(FOLLOWING_STATE)
				|| state.equals(OBSERVING_STATE)) {
			sb.append("Leader: ");
			EA<EString*> ldr = getQuorumPeers();
			if (ldr.length() > 0)
				sb.append(ldr[0]);
			else
				sb.append("not connected");
			sb.append("\n");
		}
		return sb;
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumStats_HH_ */
