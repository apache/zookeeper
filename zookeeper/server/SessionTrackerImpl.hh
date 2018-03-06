/*
 * SessionTrackerImpl.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef SessionTrackerImpl_HH_
#define SessionTrackerImpl_HH_

#include "Efc.hh"

#include "./ZooTrace.hh"
#include "./SessionTracker.hh"
#include "./ZooKeeperCriticalThread.hh"
#include "./ZooKeeperServerListener.hh"
#include "../KeeperException.hh"
#include "../common/TimeUtils.hh"

namespace efc {
namespace ezk {

/**
 * This is a full featured SessionTracker. It tracks session in grouped by tick
 * interval. It always rounds up the tick interval to provide a sort of grace
 * period. Sessions are thus expired in batches made up of sessions that expire
 * in a given interval.
 */

class SessionImpl : public Session {
public:
	SessionImpl(llong sessionId, int timeout, llong expireTime) {
		this->sessionId = sessionId;
		this->timeout = timeout;
		this->tickTime = expireTime;
		isClosing_ = false;
	}

	llong sessionId;
	int timeout;
	llong tickTime;
	boolean isClosing_;

	sp<EObject> owner;

	virtual llong getSessionId() { return sessionId; }
	virtual int getTimeout() { return timeout; }
	virtual boolean isClosing() { return isClosing_; }
};

class SessionSet : public EObject {
public:
	EHashSet<sp<SessionImpl> > sessions;// = new HashSet<SessionImpl>();
};

class SessionTrackerImpl : public ZooKeeperCriticalThread, virtual public SessionTracker {
public:
	SessionTrackerImpl(sp<SessionExpirer> expirer,
            EConcurrentHashMap<llong, EInteger>* sessionsWithTimeout, int tickTime,
            llong sid, ZooKeeperServerListener* listener) : ZooKeeperCriticalThread("SessionTracker", listener)
    {
        this->expirer = expirer;
        this->expirationInterval = tickTime;
        this->sessionsWithTimeout = sessionsWithTimeout;
        nextExpirationTime = roundToInterval(TimeUtils::currentElapsedTime());
        this->nextSessionId = initializeNextSession(sid);

        auto iter = sessionsWithTimeout->entrySet()->iterator();
        while (iter->hasNext()) {
        	auto e = iter->next();
            addSession(e->getKey(), e->getValue()->intValue());
        }
    }

    virtual void dumpSessions(EPrintStream* pwriter) {
    	SYNCHRONIZED(this) {
			pwriter->print("Session Sets (");
			pwriter->print(sessionSets.size());
			pwriter->println("):");
			/*
			ArrayList<Long> keys = new ArrayList<Long>(sessionSets.keySet());
			Collections.sort(keys);
			*/
			auto iter = sessionSets.keySet()->iterator();
			while (iter->hasNext()) {
				llong time = iter->next();
				pwriter->print(sessionSets.get(time)->sessions.size());
				pwriter->print(" expire at ");
				EDate d(time);
				pwriter->print(d.toString().c_str());
				pwriter->println(":");
				auto i = sessionSets.get(time)->sessions.iterator();
				while (i->hasNext()) {
					sp<SessionImpl> s = i->next();
					pwriter->print("\t0x");
					pwriter->println(ELLong::toHexString(s->sessionId).c_str());
				}
			}
    	}}
    }

    virtual EString toString() {
    	SYNCHRONIZED(this) {
    		EByteArrayOutputStream baos;
			EPrintStream pwriter(&baos);
			dumpSessions(&pwriter);
			pwriter.flush();
			pwriter.close();
			return EString((char*)baos.data(), 0, baos.size());
    	}}
    }

	virtual void run() {
		SYNCHRONIZED(this) {
			try {
				while (running) {
					currentTime = TimeUtils::currentElapsedTime();
					if (nextExpirationTime > currentTime) {
						this->wait(nextExpirationTime - currentTime);
						continue;
					}
					SessionSet* set = sessionSets.remove(nextExpirationTime);
					if (set != null) {
						auto i = set->sessions.iterator();
						while (i->hasNext()) {
							sp<SessionImpl> s = i->next();
							setSessionClosing(s->sessionId);
							expirer->expire(s.get());
						}
                        delete set; //!
					}
					nextExpirationTime += expirationInterval;
				}
			} catch (EInterruptedException& e) {
				handleException(this->getName(), e);
			}
		}}
        LOG->info("SessionTrackerImpl exited loop!");
    }

    boolean touchSession(llong sessionId, int timeout) {
    	SYNCHRONIZED(this) {
			if (LOG->isTraceEnabled()) {
				ZooTrace::logTraceMessage(LOG,
										 ZooTrace::CLIENT_PING_TRACE_MASK,
										 "SessionTrackerImpl --- Touch session: 0x"
						+ ELLong::toHexString(sessionId) + " with timeout " + timeout);
			}
			sp<SessionImpl> s = sessionsById.get(sessionId);
			// Return false, if the session doesn't exists or marked as closing
			if (s == null || s->isClosing()) {
				return false;
			}
            llong expireTime = roundToInterval(TimeUtils::currentElapsedTime() + timeout);
			if (s->tickTime >= expireTime) {
				// Nothing needs to be done
				return true;
			}
			SessionSet* set = sessionSets.get(s->tickTime);
			if (set != null) {
				set->sessions.remove(s.get());
			}
			s->tickTime = expireTime;
			set = sessionSets.get(s->tickTime);
			if (set == null) {
				set = new SessionSet();
				sessionSets.put(expireTime, set);
			}
			set->sessions.add(s);
			return true;
    	}}
    }

    void setSessionClosing(llong sessionId) {
    	SYNCHRONIZED(this) {
			if (LOG->isTraceEnabled()) {
				LOG->info(("Session closing: 0x" + ELLong::toHexString(sessionId)).c_str());
			}
			sp<SessionImpl> s = sessionsById.get(sessionId);
			if (s == null) {
				return;
			}
			s->isClosing_ = true;
    	}}
    }

    void removeSession(llong sessionId) {
    	SYNCHRONIZED(this) {
    		sp<SessionImpl> s = sessionsById.remove(sessionId);
			sessionsWithTimeout->remove(sessionId);
			if (LOG->isTraceEnabled()) {
                ZooTrace::logTraceMessage(LOG, ZooTrace::SESSION_TRACE_MASK,
						"SessionTrackerImpl --- Removing session 0x"
						+ ELLong::toHexString(sessionId));
			}
			if (s != null) {
				SessionSet* set = sessionSets.get(s->tickTime);
				// Session expiration has been removing the sessions
				if(set != null){
					set->sessions.remove(s.get());
				}
			}
    	}}
    }

    void shutdown() {
        LOG->info("Shutting down");

        running = false;
        if (LOG->isTraceEnabled()) {
            ZooTrace::logTraceMessage(LOG, ZooTrace::getTextTraceLevel(),
                                     "Shutdown SessionTrackerImpl!");
        }
    }

    llong createSession(int sessionTimeout) {
    	SYNCHRONIZED(this) {
			addSession(nextSessionId, sessionTimeout);
			return nextSessionId++;
    	}}
    }

    void addSession(llong id, int sessionTimeout) {
    	SYNCHRONIZED(this) {
			sessionsWithTimeout->put(id, new EInteger(sessionTimeout));
			if (sessionsById.get(id) == null) {
				sp<SessionImpl> s = new SessionImpl(id, sessionTimeout, 0);
				sessionsById.put(id, s);
				if (LOG->isTraceEnabled()) {
                    ZooTrace::logTraceMessage(LOG, ZooTrace::SESSION_TRACE_MASK,
							"SessionTrackerImpl --- Adding session 0x"
							+ ELLong::toHexString(id) + " " + sessionTimeout);
				}
			} else {
				if (LOG->isTraceEnabled()) {
                    ZooTrace::logTraceMessage(LOG, ZooTrace::SESSION_TRACE_MASK,
							"SessionTrackerImpl --- Existing session 0x"
							+ ELLong::toHexString(id) + " " + sessionTimeout);
				}
			}
			touchSession(id, sessionTimeout);
    	}}
    }

    void checkSession(llong sessionId, sp<EObject> owner) THROWS2(SessionExpiredException, SessionMovedException) {
    	SYNCHRONIZED(this) {
    		sp<SessionImpl> session = sessionsById.get(sessionId);
			if (session == null || session->isClosing()) {
				throw SessionExpiredException(__FILE__, __LINE__);
			}
			if (session->owner == null) {
				session->owner = owner;
			} else if (session->owner != owner) {
				throw SessionMovedException(__FILE__, __LINE__);
			}
    	}}
    }

    void setOwner(llong id, sp<EObject> owner) THROWS(SessionExpiredException) {
    	SYNCHRONIZED(this) {
    		sp<SessionImpl> session = sessionsById.get(id);
			if (session == null || session->isClosing()) {
				throw SessionExpiredException(__FILE__, __LINE__);
			}
			session->owner = owner;
    	}}
    }

    static llong initializeNextSession(llong id) {
        llong nextSid = 0;
        nextSid = ((ullong)(TimeUtils::currentElapsedTime() << 24)) >> 8;
        nextSid =  nextSid | (id <<56);
        return nextSid;
    }


private:

    static sp<ELogger> LOG;// = LoggerFactory.getLogger(SessionTrackerImpl.class);

    EHashMap<llong, sp<SessionImpl> > sessionsById;// = new HashMap<Long, SessionImpl>();

    EHashMap<llong, SessionSet*> sessionSets;// = new HashMap<Long, SessionSet>();

    EConcurrentHashMap<llong, EInteger>* sessionsWithTimeout;
    llong nextSessionId;// = 0;
    llong nextExpirationTime;

    int expirationInterval;

    sp<SessionExpirer> expirer;

    volatile boolean running = true;

    volatile llong currentTime;

    llong roundToInterval(llong time) {
        // We give a one interval grace period
        return (time / expirationInterval + 1) * expirationInterval;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* SessionTrackerImpl_HH_ */
