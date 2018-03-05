/*
 * Op.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ZKOp_HH_
#define ZKOp_HH_

#include "Efc.hh"
#include "./ZooDefs.hh"
#include "./CreateMode.hh"
#include "./data/ACL.hh"
#include "./common/PathUtils.hh"
#include "./proto/CheckVersionRequest.hh"
#include "./proto/CreateRequest.hh"
#include "./proto/DeleteRequest.hh"
#include "./proto/SetDataRequest.hh"
#include "../jute/inc/ERecord.hh"

namespace efc {
namespace ezk {

/**
 * Represents a single operation in a multi-operation transaction.  Each operation can be a create, update
 * or delete or can just be a version check.
 *
 * Sub-classes of Op each represent each detailed type but should not normally be referenced except via
 * the provided factory methods.
 *
 * @see ZooKeeper#create(String, byte[], java.util.List, CreateMode)
 * @see ZooKeeper#create(String, byte[], java.util.List, CreateMode, org.apache.zookeeper.AsyncCallback.StringCallback, Object)
 * @see ZooKeeper#delete(String, int)
 * @see ZooKeeper#setData(String, byte[], int)
 */

abstract class Op: public EObject {
private:
	int type;
    EString path;

protected:
    // prevent untyped construction
    Op(int type, EString path) {
        this->type = type;
        this->path = path;
    }

public:

    /**
     * Constructs a create operation.  Arguments are as for the ZooKeeper method of the same name.
     * @see ZooKeeper#create(String, byte[], java.util.List, CreateMode)
     * @see CreateMode#fromFlag(int)
     *
     * @param path
     *                the path for the node
     * @param data
     *                the initial data for the node
     * @param acl
     *                the acl for the node
     * @param flags
     *                specifying whether the node to be created is ephemeral
     *                and/or sequential but using the integer encoding.
     */
    static sp<Op> create(EString path, sp<EA<byte> > data, sp<EList<sp<ACL> > > acl, int flags);

    /**
     * Constructs a create operation.  Arguments are as for the ZooKeeper method of the same name.
     * @see ZooKeeper#create(String, byte[], java.util.List, CreateMode)
     *
     * @param path
     *                the path for the node
     * @param data
     *                the initial data for the node
     * @param acl
     *                the acl for the node
     * @param createMode
     *                specifying whether the node to be created is ephemeral
     *                and/or sequential
     */
    static sp<Op> create(EString path, sp<EA<byte> > data, sp<EList<sp<ACL> > > acl, CreateMode::Mode createMode);


    /**
     * Constructs a delete operation.  Arguments are as for the ZooKeeper method of the same name.
     * @see ZooKeeper#delete(String, int)
     *
     * @param path
     *                the path of the node to be deleted.
     * @param version
     *                the expected node version.
     */
    static sp<Op> delete_(EString path, int version);

    /**
     * Constructs an update operation.  Arguments are as for the ZooKeeper method of the same name.
     * @see ZooKeeper#setData(String, byte[], int)
     *
     * @param path
     *                the path of the node
     * @param data
     *                the data to set
     * @param version
     *                the expected matching version
     */
    static sp<Op> setData(EString path, sp<EA<byte> > data, int version);

    /**
     * Constructs an version check operation.  Arguments are as for the ZooKeeper.setData method except that
     * no data is provided since no update is intended.  The purpose for this is to allow read-modify-write
     * operations that apply to multiple znodes, but where some of the znodes are involved only in the read,
     * not the write.  A similar effect could be achieved by writing the same data back, but that leads to
     * way more version updates than are necessary and more writing in general.
     *
     * @param path
     *                the path of the node
     * @param version
     *                the expected matching version
     */
    static sp<Op> check(EString path, int version);

    /**
     * Gets the integer type code for an Op.  This code should be as from ZooDefs.OpCode
     * @see ZooDefs.OpCode
     * @return  The type code.
     */
    int getType() {
        return type;
    }

    /**
     * Gets the path for an Op.
     * @return  The path.
     */
    EString getPath() {
        return path;
    }

    /**
     * Encodes an op for wire transmission.
     * @return An appropriate Record structure.
     */
    virtual sp<ERecord> toRequestRecord() = 0;

    /**
     * Reconstructs the transaction with the chroot prefix.
     * 
     * @return transaction with chroot.
     */
    virtual sp<Op> withChroot(EString addRootPrefix) = 0;

    /**
     * Performs client path validations.
     * 
     * @throws IllegalArgumentException
     *             if an invalid path is specified
     * @throws KeeperException.BadArgumentsException
     *             if an invalid create mode flag is specified
     */
    virtual void validate() THROWS(KeeperException) {
        PathUtils::validatePath(path);
    }
};


//////////////////
// these internal classes are public, but should not generally be referenced.
//
class Create : public Op {
private:
	sp<EA<byte> > data;
    sp<EList<sp<ACL> > > acl;
    int flags;

public:
	Create(EString path, sp<EA<byte> > data, sp<EList<sp<ACL> > > acl,
			int flags) :
			Op(ZooDefs::OpCode::create, path) {
        this->data = data;
        this->acl = acl;
        this->flags = flags;
    }

    Create(EString path, sp<EA<byte> > data, sp<EList<sp<ACL> > > acl,
    		sp<CreateMode> createMode) :
			Op(ZooDefs::OpCode::create, path) {
        this->data = data;
        this->acl = acl;
        this->flags = createMode->toFlag();
    }

    virtual boolean equals(EObject* o) {
        if (this == o) return true;
        Create* op = dynamic_cast<Create*>(o);
        if (!op) return false;

        boolean aclEquals = true;
        //cxxjava: BUG FIXED???
        auto h = op->acl->iterator();
        auto i = acl->iterator();
        while (h->hasNext()) {
            boolean hasMoreData = i->hasNext();
            if (!hasMoreData) {
                aclEquals = false;
                break;
            }
        	ACL* acl = dynamic_cast<ACL*>(h->next().get());
            ACL* otherAcl = dynamic_cast<ACL*>(i->next().get());
            if (!acl->equals(otherAcl)) {
                aclEquals = false;
                break;
            }
        }
        return !i->hasNext()
        		&& getType() == op->getType()
        		&& EArrays::equals(data.get(), op->data.get())
        		&& flags == op->flags
        		&& aclEquals;
    }

    virtual int hashCode() {
        return getType() + getPath().hashCode() + EArrays::hashCode(data.get());
    }

    virtual sp<ERecord> toRequestRecord() {
        return new CreateRequest(getPath(), data, acl, flags);
    }

    virtual sp<Op> withChroot(EString path) {
        return new Create(path, data, acl, flags);
    }

    virtual void validate() THROWS(KeeperException) {
        CreateMode createMode = CreateMode::fromFlag((CreateMode::Mode)flags);
        PathUtils::validatePath(getPath(), createMode.isSequential());
    }
};

class Delete : public Op {
private:
	int version;

public:

	Delete(EString path, int version) :
    	Op(ZooDefs::OpCode::delete_, path) {
        this->version = version;
    }

    virtual boolean equals(EObject* o) {
        if (this == o) return true;

        Delete* op = dynamic_cast<Delete*>(o);
        if (!op) return false;

        return getType() == op->getType() && version == op->version
               && getPath().equals(op->getPath().c_str());
    }

    virtual int hashCode() {
        return getType() + getPath().hashCode() + version;
    }

    virtual sp<ERecord> toRequestRecord() {
        return new DeleteRequest(getPath(), version);
    }

    virtual sp<Op> withChroot(EString path) {
        return new Delete(path, version);
    }
};

class SetData : public Op {
private:
	sp<EA<byte> > data;
    int version;

public:
    SetData(EString path, sp<EA<byte> > data, int version) :
        Op(ZooDefs::OpCode::setData, path) {
        this->data = data;
        this->version = version;
    }

    virtual boolean equals(EObject* o) {
        if (this == o) return true;

        SetData* op = dynamic_cast<SetData*>(o);
        if (!op) return false;

        return getType() == op->getType() && version == op->version
               && getPath().equals(op->getPath().c_str()) && EArrays::equals(data.get(), op->data.get());
    }

    virtual int hashCode() {
        return getType() + getPath().hashCode() + EArrays::hashCode(data.get()) + version;
    }

    virtual sp<ERecord> toRequestRecord() {
        return new SetDataRequest(getPath(), data, version);
    }

    virtual sp<Op> withChroot(EString path) {
        return new SetData(path, data, version);
    }
};

class Check : public Op {
private:
	int version;

public:
	Check(EString path, int version) :
        Op(ZooDefs::OpCode::check, path) {
        this->version = version;
    }

    virtual boolean equals(EObject* o) {
        if (this == o) return true;

        Check* op = dynamic_cast<Check*>(o);
        if (!op) return false;

        return getType() == op->getType() && getPath().equals(op->getPath().c_str()) && version == op->version;
    }

    virtual int hashCode() {
        return getType() + getPath().hashCode() + version;
    }

    virtual sp<ERecord> toRequestRecord() {
        return new CheckVersionRequest(getPath(), version);
    }

    virtual sp<Op> withChroot(EString path) {
        return new Check(path, version);
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZKOp_HH_ */
