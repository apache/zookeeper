/*
 * ReferenceCountedACLCache.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ReferenceCountedACLCache_HH_
#define ReferenceCountedACLCache_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "../ZooDefs.hh"
#include "../data/ACL.hh"
#include "../../jute/inc/ERecord.hh"
#include "../../jute/inc/EBinaryInputArchive.hh"
#include "../../jute/inc/EBinaryOutputArchive.hh"
#include "../../jute/inc/ECsvOutputArchive.hh"

namespace efc {
namespace ezk {

class AtomicLongWithEquals : public EAtomicLLong {
public:
	AtomicLongWithEquals(llong i) : EAtomicLLong(i) {
	}

	virtual boolean equals(EObject* o) {
		if (this == o) return true;

		AtomicLongWithEquals* that = dynamic_cast<AtomicLongWithEquals*>(o);
		if (that == null) return false;

		return this->get() == that->get();
	}

	virtual int hashCode() {
		return 31 * ELLong::valueOf(get()).hashCode();
	}
};

//=============================================================================

class ReferenceCountedACLCache : public ESynchronizeable {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(ReferenceCountedACLCache.class);

    static const llong OPEN_UNSAFE_ACL_ID = -1L;

    EHashMap<llong, sp<EList<sp<ACL> > > > longKeyMap;

    EHashMap<sp<EList<sp<ACL> > >, sp<ELLong> > aclKeyMap;

    EHashMap<llong, AtomicLongWithEquals*> referenceCounter;

    /**
     * these are the number of acls that we have in the datatree
     */
    llong aclIndex;// = 0;


    llong incrementIndex() {
		return ++aclIndex;
	}

	void clear() {
		aclKeyMap.clear();
		longKeyMap.clear();
		referenceCounter.clear();
	}

public:

    ReferenceCountedACLCache() : aclIndex(0) {
    	//
    }

    /**
     * converts the list of acls to a long.
     * Increments the reference counter for this ACL.
     * @param acls
     * @return a long that map to the acls
     */
    synchronized llong convertAcls(sp<EList<sp<ACL> > > acls) {
    	SYNCHRONIZED(this) {
			if (acls == null)
				return OPEN_UNSAFE_ACL_ID;

			// get the value from the map
			sp<ELLong> ret = aclKeyMap.get(acls.get());
			llong r = 0;
			if (ret == null) {
				r = incrementIndex();
				longKeyMap.put(r, acls);
				aclKeyMap.put(acls, ret);
			}

			addUsage(r);

			return r;
    	}}
    }

    /**
     * converts a long to a list of acls.
     *
     * @param longVal
     * @return a list of ACLs that map to the long
     */
    synchronized
    sp<EList<sp<ACL> > > convertLong(llong longVal) {
    	SYNCHRONIZED(this) {
			//if (longVal == null)
			//    return null;
			if (longVal == OPEN_UNSAFE_ACL_ID) {
				sp<ACL> acl = new ACL(ZooDefs::Perms::ALL, new Id("world", "anyone"));
				EArrayList<sp<ACL> >* al = new EArrayList<sp<ACL> >();
				al->add(acl);
				return al; //ZooDefs::Ids::OPEN_ACL_UNSAFE;
			}
			sp<EList<sp<ACL> > > acls = longKeyMap.get(longVal);
			if (acls == null) {
				LOG->error(EString("ERROR: ACL not available for long ") + longVal);
				throw ERuntimeException(__FILE__, __LINE__, (EString("Failed to fetch acls for ") + longVal).c_str());
			}
			return acls;
    	}}
    }

    synchronized
    virtual void deserialize(EInputArchive* a_) THROWS(EIOException) {
    	SYNCHRONIZED(this) {
			clear();
			int i = a_->readInt("map");
			while (i > 0) {
				llong val = a_->readLLong("long");
				if (aclIndex < val) {
					aclIndex = val;
				}
				sp<EList<sp<ACL> > > aclList = new EArrayList<sp<ACL> >();
				sp<EIndex> j = a_->startVector("acls");
				while (!j->done()) {
					sp<ACL> acl = new ACL();
					acl->deserialize(a_, "acl");
					aclList->add(acl);
					j->incr();
				}
				longKeyMap.put(val, aclList);
				aclKeyMap.put(aclList, new ELLong(val));
				delete referenceCounter.put(val, new AtomicLongWithEquals(0));
				i--;
			}
    	}}
    }

    synchronized
    virtual void serialize(EOutputArchive* a_) THROWS(EIOException) {
    	SYNCHRONIZED(this) {
			a_->writeInt(longKeyMap.size(), "map");
			auto iter = longKeyMap.entrySet()->iterator();
			while (iter->hasNext()) {
				auto val = iter->next();
				a_->writeLLong(val->getKey(), "long");
				sp<EList<sp<ACL> > > aclList = val->getValue();
				a_->startVector(aclList.get(), JUTE_SIZE(aclList), "acls");
				auto i2 = aclList->iterator();
				while (i2->hasNext()) {
					ACL* acl = dynamic_cast<ACL*>(i2->next().get());
					acl->serialize(a_, "acl");
				}
				a_->endVector(aclList.get(), "acls");
			}
    	}}
    }

    int size() {
        return aclKeyMap.size();
    }

    synchronized
    void addUsage(llong acl) {
    	SYNCHRONIZED(this) {
			if (acl == OPEN_UNSAFE_ACL_ID) {
				return;
			}

			if (!longKeyMap.containsKey(acl)) {
				LOG->info(EString("Ignoring acl ") + acl + " as it does not exist in the cache");
				return;
			}

			EAtomicLLong* count = referenceCounter.get(acl);
			if (count == null) {
				delete referenceCounter.put(acl, new AtomicLongWithEquals(1));
			} else {
				count->incrementAndGet();
			}
    	}}
    }

    synchronized
    void removeUsage(llong acl) {
    	SYNCHRONIZED(this) {
			if (acl == OPEN_UNSAFE_ACL_ID) {
				return;
			}

			if (!longKeyMap.containsKey(acl)) {
				LOG->info(EString("Ignoring acl ") + acl + " as it does not exist in the cache");
				return;
			}

			llong newCount = referenceCounter.get(acl)->decrementAndGet();
			if (newCount <= 0) {
				delete referenceCounter.remove(acl);
				aclKeyMap.remove(longKeyMap.get(acl).get());
				longKeyMap.remove(acl);
			}
    	}}
    }

    synchronized
    void purgeUnused() {
    	SYNCHRONIZED(this) {
			auto refCountIter = referenceCounter.entrySet()->iterator();
			while (refCountIter->hasNext()) {
				auto entry = refCountIter->next();
				if (entry->getValue()->get() <= 0) {
					llong acl = entry->getKey();
					aclKeyMap.remove(longKeyMap.get(acl).get());
					longKeyMap.remove(acl);
					refCountIter->remove();
				}
			}
    	}}
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ReferenceCountedACLCache_HH_ */
