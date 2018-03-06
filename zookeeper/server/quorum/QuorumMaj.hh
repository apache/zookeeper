/*
 * QuorumMaj.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef QuorumMaj_HH_
#define QuorumMaj_HH_

#include "Efc.hh"
#include "ELog.hh"
#include "./QuorumVerifier.hh"

namespace efc {
namespace ezk {

/**
 * This class implements a validator for majority quorums. The 
 * implementation is straightforward.
 *
 */
class QuorumMaj : public QuorumVerifier {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(QuorumMaj.class);
    
protected:
    int half;
    
public:
    /**
     * Defines a majority to avoid computing it every time.
     * 
     * @param n number of servers
     */
    QuorumMaj(int n){
        this->half = n/2;
    }
    
    /**
     * Returns weight of 1 by default.
     * 
     * @param id 
     */
    virtual llong getWeight(llong id){
        return (llong) 1;
    }
    
    /**
     * Verifies if a set is a majority.
     */
    virtual boolean containsQuorum(EHashSet<llong>* set){
        return (set->size() > half);
    }
    
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumMaj_HH_ */
