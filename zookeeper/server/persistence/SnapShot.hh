/*
 * SnapShot.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef SnapShot_HH_
#define SnapShot_HH_

#include "Efc.hh"

#include "../DataTree.hh"
#include "../../txn/TxnHeader.hh"
#include "../../../jute/inc/ERecord.hh"

namespace efc {
namespace ezk {

/**
 * snapshot interface for the persistence layer.
 * implement this interface for implementing 
 * snapshots.
 */

interface SnapShot : virtual public EObject {
	virtual ~SnapShot() {}
    
    /**
     * deserialize a data tree from the last valid snapshot and 
     * return the last zxid that was deserialized
     * @param dt the datatree to be deserialized into
     * @param sessions the sessions to be deserialized into
     * @return the last zxid that was deserialized from the snapshot
     * @throws IOException
     */
    virtual llong deserialize(sp<DataTree> dt, EConcurrentHashMap<llong, EInteger>* sessions)
        THROWS(EIOException) = 0;
    
    /**
     * persist the datatree and the sessions into a persistence storage
     * @param dt the datatree to be serialized
     * @param sessions 
     * @throws IOException
     */
    virtual void serialize(sp<DataTree> dt, EConcurrentHashMap<llong, EInteger>* sessions,
            EFile* name) THROWS(EIOException) = 0;
    
    /**
     * find the most recent snapshot file
     * @return the most recent snapshot file
     * @throws IOException
     */
    virtual sp<EFile> findMostRecentSnapshot() THROWS(EIOException) = 0;
    
    /**
     * free resources from this snapshot immediately
     * @throws IOException
     */
    virtual void close() THROWS(EIOException) = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* SnapShot_HH_ */
