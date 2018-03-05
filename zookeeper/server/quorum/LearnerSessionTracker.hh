/*
 * LearnerSessionTracker.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef LearnerSessionTracker_HH_
#define LearnerSessionTracker_HH_

#include "Efc.hh"

#include "../SessionTracker.hh"
#include "../SessionTrackerImpl.hh"
#include "../ZooKeeperServerListener.hh"

namespace efc {
namespace ezk {

/**
 * This is really just a shell of a SessionTracker that tracks session activity
 * to be forwarded to the Leader using a PING.
 */
class LearnerSessionTracker : public ESynchronizeable, virtual public SessionTracker {
private:
	EConcurrentHashMap<llong, EInteger>* sessionsWithTimeouts;

public:
    sp<SessionExpirer> expirer;

    sp<EHashMap<llong, EInteger*> > touchTable;// = new HashMap<Long, Integer>();
    llong serverId;// = 1;
    llong nextSessionId;//=0;
    

    LearnerSessionTracker(sp<SessionExpirer> expirer,
    		EConcurrentHashMap<llong, EInteger>* sessionsWithTimeouts, llong id,
            ZooKeeperServerListener* listener) {
        this->expirer = expirer;
        this->sessionsWithTimeouts = sessionsWithTimeouts;
        this->serverId = id;
        nextSessionId = SessionTrackerImpl::initializeNextSession(this->serverId);
        
        touchTable = new EHashMap<llong, EInteger*>();
    }

    synchronized
    void removeSession(llong sessionId) {
    	SYNCHRONIZED(this) {
    		sessionsWithTimeouts->remove(sessionId);
    		delete touchTable->remove(sessionId);
    	}}
    }

    virtual void shutdown() {
    }

    synchronized
    virtual void addSession(llong sessionId, int sessionTimeout) {
    	SYNCHRONIZED(this) {
			sessionsWithTimeouts->put(sessionId, new EInteger(sessionTimeout));
			delete touchTable->put(sessionId, new EInteger(sessionTimeout));
    	}}
    }

    synchronized
    virtual boolean touchSession(llong sessionId, int sessionTimeout) {
    	SYNCHRONIZED(this) {
    		delete touchTable->put(sessionId, new EInteger(sessionTimeout));
			return true;
    	}}
    }

    synchronized
    sp<EHashMap<llong, EInteger*> > snapshot() {
    	SYNCHRONIZED(this) {
			sp<EHashMap<llong, EInteger*> > oldTouchTable = touchTable;
			touchTable = new EHashMap<llong, EInteger*>();
			return oldTouchTable;
    	}}
    }

    synchronized
    virtual llong createSession(int sessionTimeout) {
    	SYNCHRONIZED(this) {
    		return (nextSessionId++);
    	}}
    }

    virtual void checkSession(llong sessionId, sp<EObject> owner)  {
        // Nothing to do here. Sessions are checked at the Leader
    }
    
    virtual void setOwner(llong sessionId, sp<EObject> owner) {
        // Nothing to do here. Sessions are checked at the Leader
    }

    virtual void dumpSessions(EPrintStream* pwriter) {
    	// the original class didn't have tostring impl, so just
    	// dup what we had before
    	pwriter->println(toString().c_str());
    }

    virtual void setSessionClosing(llong sessionId) {
        // Nothing to do here.
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* LearnerSessionTracker_HH_ */

