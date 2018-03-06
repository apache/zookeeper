/*
 * WatchManager.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef WatchManager_HH_
#define WatchManager_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./ServerCnxn.hh"
#include "./ZooTrace.hh"
#include "../Watcher.hh"
#include "../WatchedEvent.hh"

namespace efc {
namespace ezk {

/**
 * This class manages watches. It allows watches to be associated with a string
 * and removes watchers and their watches in addition to managing triggers.
 */
class WatchManager : public ESynchronizeable {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(WatchManager.class);

    EHashMap<sp<EString>, sp<EHashSet<sp<Watcher> > > > watchTable;// = new HashMap<String, HashSet<Watcher>>();

    EHashMap<sp<Watcher>, sp<EHashSet<EString*> > > watch2Paths;// = new HashMap<Watcher, HashSet<String>>();

public:
    synchronized
    int size(){
    	SYNCHRONIZED(this) {
			int result = 0;
			auto iter = watchTable.values()->iterator();
			while (iter->hasNext()) {
				auto watches = iter->next();
				result += watches->size();
			}
			return result;
    	}}
    }

    synchronized
    void addWatch(EString path, sp<Watcher> watcher) {
    	SYNCHRONIZED(this) {
			auto list = watchTable.get(&path);
			if (list == null) {
				// don't waste memory if there are few watches on a node
				// rehash when the 4th entry is added, doubling size thereafter
				// seems like a good compromise
				list = new EHashSet<sp<Watcher> >(4, 0.75);
				watchTable.put(new EString(path), list);
			}
			list->add(watcher);

			auto paths = watch2Paths.get(watcher.get());
			if (paths == null) {
				// cnxns typically have many watches, so use default cap here
				paths = new EHashSet<EString*>();
				watch2Paths.put(watcher, paths);
			}
			paths->add(new EString(path));
    	}}
    }

    synchronized
    void removeWatcher(Watcher* watcher) {
    	SYNCHRONIZED(this) {
			auto paths = watch2Paths.remove(watcher);
			if (paths == null) {
				return;
			}
			auto iter = paths->iterator();
			while (iter->hasNext()) {
				EString* p = iter->next();
				auto list = watchTable.get(p);
				if (list != null) {
					list->remove(watcher);
					if (list->size() == 0) {
						watchTable.remove(p);
					}
				}
			}
    	}}
    }

    sp<EHashSet<sp<Watcher> > > triggerWatch(EString path, Watcher::Event::EventType type) {
        return triggerWatch(path, type, null);
    }

    sp<EHashSet<sp<Watcher> > > triggerWatch(EString path, Watcher::Event::EventType type, sp<EHashSet<sp<Watcher> > > supress) {
        sp<WatchedEvent> e = new WatchedEvent(type, Watcher::Event::KeeperState::SyncConnected, path);
        sp<EHashSet<sp<Watcher> > > watchers;
        SYNCHRONIZED(this) {
            watchers = watchTable.remove(&path);
            if (watchers == null || watchers->isEmpty()) {
                if (LOG->isTraceEnabled()) {
                    ZooTrace::logTraceMessage(LOG,
                            ZooTrace::EVENT_DELIVERY_TRACE_MASK,
                            "No watchers for " + path);
                }
                return null;
            }
            auto iter = watchers->iterator();
            while (iter->hasNext()) {
            	sp<Watcher> w = iter->next();
                auto paths = watch2Paths.get(w.get());
                if (paths != null) {
                    paths->remove(&path);
                }
            }
        }}
    	auto iter = watchers->iterator();
		while (iter->hasNext()) {
			sp<Watcher> w = iter->next();
            if (supress != null && supress->contains(w.get())) {
                continue;
            }
            w->process(e);
        }
        return watchers;
    }

    /**
     * Brief description of this object.
     */
    synchronized
    virtual EString toString() {
    	SYNCHRONIZED(this) {
    		EString sb;// = new StringBuilder();

			sb.append(watch2Paths.size()).append(" connections watching ")
				.append(watchTable.size()).append(" paths\n");

			int total = 0;
			auto iter = watch2Paths.values()->iterator();
			while (iter->hasNext()) {
				auto paths = iter->next();
				total += paths->size();
			}
			sb.append("Total watches:").append(total);

			return sb;
    	}}
    }

    /**
     * String representation of watches. Warning, may be large!
     * @param byPath iff true output watches by paths, otw output
     * watches by connection
     * @return string representation of watches
     */
    synchronized
    void dumpWatches(EPrintStream* pwriter, boolean byPath) {
    	SYNCHRONIZED(this) {
			if (byPath) {
				auto iter = watchTable.entrySet()->iterator();
				while (iter->hasNext()) {
					auto e = iter->next();
					pwriter->println(e->getKey()->c_str());
					auto i2 = e->getValue()->iterator();
					while (i2->hasNext()) {
						auto w = i2->next();
						pwriter->print("\t0x");
						pwriter->print(ELLong::toHexString((dynamic_pointer_cast<ServerCnxn>(w))->getSessionId()).c_str());
						pwriter->print("\n");
					}
				}
			} else {
				auto iter = watch2Paths.entrySet()->iterator();
				while (iter->hasNext()) {
					auto e = iter->next();
					pwriter->print("0x");
					pwriter->println(ELLong::toHexString((dynamic_pointer_cast<ServerCnxn>(e->getKey()))->getSessionId()).c_str());
					auto i2 = e->getValue()->iterator();
					while (i2->hasNext()) {
						auto path = i2->next();
						pwriter->print("\t");
						pwriter->println(path->c_str());
					}
				}
			}
    	}}
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* WatchManager_HH_ */
