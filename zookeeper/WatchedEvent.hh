/*
 * WatchedEvent.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef WatchedEvent_HH_
#define WatchedEvent_HH_

#include "Efc.hh"
#include "./Watcher.hh"
#include "./proto/WatcherEvent.hh"

namespace efc {
namespace ezk {

/**
 *  A WatchedEvent represents a change on the ZooKeeper that a Watcher
 *  is able to respond to.  The WatchedEvent includes exactly what happened,
 *  the current state of the ZooKeeper, and the path of the znode that
 *  was involved in the event.
 */
class WatchedEvent : public EObject {
public:
	typedef Watcher::Event::KeeperState KeeperState;
	typedef Watcher::Event::EventType EventType;

private:
	KeeperState keeperState;
	EventType eventType;
	EString path;
    
public:
    /**
     * Create a WatchedEvent with specified type, state and path
     */
    WatchedEvent(EventType eventType, KeeperState keeperState, EString path) {
        this->keeperState = keeperState;
        this->eventType = eventType;
        this->path = path;
    }
    
    /**
     * Convert a WatcherEvent sent over the wire into a full-fledged WatcherEvent
     */
    WatchedEvent(WatcherEvent* eventMessage) {
        keeperState = Watcher::Event::getKeeperStatefromInt(eventMessage->getState());
        eventType = Watcher::Event::getEventTypefromInt(eventMessage->getType());
        path = eventMessage->getPath();
    }
    
    KeeperState getState() {
        return keeperState;
    }
    
    EventType getType() {
        return eventType;
    }
    
    EString getPath() {
        return path;
    }

    virtual EString toString() {
        return EString("WatchedEvent state:") + (int)keeperState
            + " type:" + (int)eventType + " path:" + path;
    }

    /**
     *  Convert WatchedEvent to type that can be sent over network
     */
    sp<WatcherEvent> getWrapper() {
        return new WatcherEvent(eventType,
                                keeperState,
                                path);
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* WatchedEvent_HH_ */
