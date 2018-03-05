/*
 * Vote.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef Vote_HH_
#define Vote_HH_

#include "Efc.hh"
#include "ELog.hh"
#include "./ServerState.hh"
#include "./Vote.hh"

namespace efc {
namespace ezk {

class Vote: public EObject {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(Vote.class);
    
    int version;
    llong id;
    llong zxid;
    llong electionEpoch;
    llong peerEpoch;
    ServerState state;

public:
	Vote(llong id, llong zxid) {
        this->version = 0x0;
        this->id = id;
        this->zxid = zxid;
        this->electionEpoch = -1;
        this->peerEpoch = -1;
        this->state = LOOKING;
    }
    
    Vote(llong id, llong zxid, llong peerEpoch) {
    	this->version = 0x0;
    	this->id = id;
    	this->zxid = zxid;
    	this->electionEpoch = -1;
    	this->peerEpoch = peerEpoch;
    	this->state = LOOKING;
    }

    Vote(llong id,
    		llong zxid,
    		llong electionEpoch,
    		llong peerEpoch) {
    	this->version = 0x0;
    	this->id = id;
    	this->zxid = zxid;
    	this->electionEpoch = electionEpoch;
    	this->peerEpoch = peerEpoch;
    	this->state = LOOKING;
    }
    
    Vote(int version,
    		llong id,
    		llong zxid,
    		llong electionEpoch,
    		llong peerEpoch,
                    ServerState state) {
    	this->version = version;
    	this->id = id;
    	this->zxid = zxid;
    	this->electionEpoch = electionEpoch;
    	this->state = state;
    	this->peerEpoch = peerEpoch;
    }
    
    Vote(llong id,
    		llong zxid,
    		llong electionEpoch,
    		llong peerEpoch,
                    ServerState state) {
    	this->id = id;
    	this->zxid = zxid;
    	this->electionEpoch = electionEpoch;
    	this->state = state;
    	this->peerEpoch = peerEpoch;
    	this->version = 0x0;
    }

    int getVersion() {
        return version;
    }
    
    llong getId() {
        return id;
    }

    llong getZxid() {
        return zxid;
    }

    llong getElectionEpoch() {
        return electionEpoch;
    }

    llong getPeerEpoch() {
        return peerEpoch;
    }

    ServerState getState() {
        return state;
    }

    
    virtual boolean equals(EObject* o) {
    	Vote* other = dynamic_cast<Vote*>(o);
        if (!other) {
            return false;
        }
        
        /*
         * There are two things going on in the logic below.
         * First, we compare votes of servers out of election
         * using only id and peer epoch. Second, if one version
         * is 0x0 and the other isn't, then we only use the
         * leader id. This case is here to enable rolling upgrades.
         * 
         * {@see https://issues.apache.org/jira/browse/ZOOKEEPER-1805}
         */
        if ((state == LOOKING) ||
                (other->state == LOOKING)) {
            return (id == other->id
                    && zxid == other->zxid
                    && electionEpoch == other->electionEpoch
                    && peerEpoch == other->peerEpoch);
        } else {
            if ((version > 0x0) ^ (other->version > 0x0)) {
                return id == other->id;
            } else {
                return (id == other->id
                        && peerEpoch == other->peerEpoch);
            }
        } 
    }

    virtual int hashCode() {
        return (int) (id & zxid);
    }

    virtual EString toString() {
        return EString::formatOf("(%d, %s, %s)",
                                id,
                                ELLong::toHexString(zxid).c_str(),
                                ELLong::toHexString(peerEpoch).c_str());
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Vote_HH_ */
