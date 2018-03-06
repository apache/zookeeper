/*
 * SessionTracker.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef SessionTracker_HH_
#define SessionTracker_HH_

#include "Efc.hh"

#include "../KeeperException.hh"

namespace efc {
namespace ezk {

/**
 * This is the basic interface that ZooKeeperServer uses to track sessions. The
 * standalone and leader ZooKeeperServer use the same SessionTracker. The
 * FollowerZooKeeperServer uses a SessionTracker which is basically a simple
 * shell to track information to be forwarded to the leader.
 */

interface Session : virtual public EObject {
    virtual llong getSessionId() = 0;
    virtual int getTimeout() = 0;
    virtual boolean isClosing() = 0;
};

interface SessionExpirer : virtual public EObject {
	virtual void expire(Session* session) = 0;
	virtual llong getServerId() = 0;
};

interface SessionTracker : virtual public EObject {
	virtual ~SessionTracker() {}

	virtual llong createSession(int sessionTimeout) = 0;

	virtual void addSession(llong id, int sessionTimeout) = 0;

    /**
     * @param sessionId
     * @param sessionTimeout
     * @return false if session is no longer active
     */
	virtual boolean touchSession(llong sessionId, int sessionTimeout) = 0;

    /**
     * Mark that the session is in the process of closing.
     * @param sessionId
     */
	virtual void setSessionClosing(llong sessionId) = 0;

    /**
     * 
     */
	virtual void shutdown() = 0;

    /**
     * @param sessionId
     */
	virtual void removeSession(llong sessionId) = 0;

	virtual void checkSession(llong sessionId, sp<EObject> owner) THROWS2(SessionExpiredException, SessionMovedException) = 0;

	virtual void setOwner(llong id, sp<EObject> owner) THROWS(SessionExpiredException) = 0;

    /**
     * Text dump of session information, suitable for debugging.
     * @param pwriter the output writer
     */
	virtual void dumpSessions(EPrintStream* pwriter) = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* SessionTracker_HH_ */
