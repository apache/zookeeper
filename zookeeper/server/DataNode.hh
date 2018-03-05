/*
 * DataNode.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef DataNode_HH_
#define DataNode_HH_

#include "Efc.hh"

#include "../data/Stat.hh"
#include "../data/StatPersisted.hh"
#include "../../jute/inc/ERecord.hh"
#include "../../jute/inc/EBinaryInputArchive.hh"
#include "../../jute/inc/EBinaryOutputArchive.hh"
#include "../../jute/inc/ECsvOutputArchive.hh"

namespace efc {
namespace ezk {


/**
 * This class contains the data for a node in the data tree.
 * <p>
 * A data node contains a reference to its parent, a byte array as its data, an
 * array of ACLs, a stat object, and a set of its children's paths.
 * 
 */
class DataNode : public ERecord, public ESynchronizeable {
public:

    /** the parent of this datanode */
    sp<DataNode> parent;

    /** the data for this datanode */
    sp<EA<byte> > data;

    /**
     * the acl map long for this datanode. the datatree has the map
     */
    llong acl;

    /**
     * the stat for this node that is persisted to disk.
     */
    sp<StatPersisted> stat;

    /**
     * the list of children for this node. note that the list of children string
     * does not contain the parent path -- just the last part of the path. This
     * should be synchronized on except deserializing (for speed up issues).
     */
    sp<EHashSet<EString*> > children;// = null;

    /**
     * default constructor for the datanode
     */
    DataNode() {
        // default constructor
    }

    /**
     * create a DataNode with parent, data, acls and stat
     * 
     * @param parent
     *            the parent of this DataNode
     * @param data
     *            the data to be set
     * @param acl
     *            the acls for this node
     * @param stat
     *            the stat for this node.
     */
    DataNode(sp<DataNode> parent, sp<EA<byte> > data, llong acl, sp<StatPersisted> stat) {
        this->parent = parent;
        this->data = data;
        this->acl = acl;
        this->stat = stat;
    }

    /**
     * Method that inserts a child into the children set
     * 
     * @param child
     *            to be inserted
     * @return true if this set did not already contain the specified element
     */
    synchronized
    boolean addChild(EString child) {
    	SYNCHRONIZED(this) {
			if (children == null) {
				// let's be conservative on the typical number of children
				children = new EHashSet<EString*>(8);
			}
			return children->add(new EString(child));
    	}}
    }

    /**
     * Method that removes a child from the children set
     * 
     * @param child
     * @return true if this set contained the specified element
     */
    synchronized
    boolean removeChild(EString child) {
    	SYNCHRONIZED(this) {
			if (children == null) {
				return false;
			}
			return children->remove(&child);
    	}}
    }

    /**
     * convenience method for setting the children for this datanode
     * 
     * @param children
     */
    synchronized
    void setChildren(sp<EHashSet<EString*> > children) {
    	SYNCHRONIZED(this) {
    		this->children = children;
    	}}
    }

    /**
     * convenience methods to get the children
     * 
     * @return the children of this datanode
     */
    synchronized
    sp<EHashSet<EString*> > getChildren() {
    	SYNCHRONIZED(this) {
			if (children == null) {
				return new EHashSet<EString*>();
			}

			return children;
    	}}
    }

    synchronized
    void copyStat(sp<Stat> to) {
    	SYNCHRONIZED(this) {
			to->setAversion(stat->getAversion());
			to->setCtime(stat->getCtime());
			to->setCzxid(stat->getCzxid());
			to->setMtime(stat->getMtime());
			to->setMzxid(stat->getMzxid());
			to->setPzxid(stat->getPzxid());
			to->setVersion(stat->getVersion());
			to->setEphemeralOwner(stat->getEphemeralOwner());
			to->setDataLength(data == null ? 0 : data->length());
			int numChildren = 0;
			if (this->children != null) {
				numChildren = children->size();
			}
			// when we do the Cversion we need to translate from the count of the creates
			// to the count of the changes (v3 semantics)
			// for every create there is a delete except for the children still present
			to->setCversion(stat->getCversion()*2 - numChildren);
			to->setNumChildren(numChildren);
    	}}
    }

    synchronized
    virtual void deserialize(EInputArchive* a_, const char* tag) THROWS(EIOException) {
    	SYNCHRONIZED(this) {
			a_->startRecord("node");
			data =a_->readBuffer("data");
			acl =a_->readLLong("acl");
			stat = new StatPersisted();
			stat->deserialize(a_, "statpersisted");
			a_->endRecord("node");
    	}}
    }

    synchronized
    virtual void serialize(EOutputArchive* a_, const char* tag) THROWS(EIOException) {
		SYNCHRONIZED(this) {
			a_->startRecord(this, "node");
			a_->writeBuffer(data.get(), "data");
			a_->writeLLong(acl, "acl");
			stat->serialize(a_, "statpersisted");
			a_->endRecord(this, "node");
		}}
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* DataNode_HH_ */
