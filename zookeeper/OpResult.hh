/*
 * OpResult.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef OpResult_HH_
#define OpResult_HH_

#include "Efc.hh"
#include "./ZooDefs.hh"
#include "./data/Stat.hh"

namespace efc {
namespace ezk {

/**
 * Encodes the result of a single part of a multiple operation commit.
 */
abstract class OpResult : public EObject {
protected:
	int type;

    OpResult(int type) {
        this->type = type;
    }

public:

    /**
     * Encodes the return type as from ZooDefs.OpCode.  Can be used
     * to dispatch to the correct cast needed for getting the desired
     * additional result data.
     * @see ZooDefs.OpCode
     * @return an integer identifying what kind of operation this result came from.
     */
    int getType() {
        return type;
    }
};

//=============================================================================

/**
 * A result from a create operation.  This kind of result allows the
 * path to be retrieved since the create might have been a sequential
 * create.
 */
class CreateResult : public OpResult {
private:
	EString path;

public:
    CreateResult(EString path) : OpResult(ZooDefs::OpCode::create) {
        this->path = path;
    }

    EString getPath() {
        return path;
    }

    virtual boolean equals(EObject* o) {
        if (this == o) return true;
    	CreateResult* other = dynamic_cast<CreateResult*>(o);
        if (!other) return false;

        EString s = other->getPath();
        return getType() == other->getType() && path.equals(s);
    }

    virtual int hashCode() {
        return getType() * 35 + path.hashCode();
    }
};

/**
 * A result from a delete operation.  No special values are available.
 */
class DeleteResult : public OpResult {
public:
    DeleteResult() : OpResult(ZooDefs::OpCode::delete_) {
    }

    virtual boolean equals(EObject* o) {
        if (this == o) return true;
        DeleteResult* opResult = dynamic_cast<DeleteResult*>(o);
        if (!opResult) return false;

        return getType() == opResult->getType();
    }

    virtual int hashCode() {
        return getType();
    }
};

/**
 * A result from a setData operation.  This kind of result provides access
 * to the Stat structure from the update.
 */
class SetDataResult : public OpResult {
private:
	sp<Stat> stat;

public:
    SetDataResult(sp<Stat> stat) : OpResult(ZooDefs::OpCode::setData) {
    	this->stat = stat;
    }

    sp<Stat> getStat() {
        return stat;
    }

    virtual boolean equals(EObject* o) {
        if (this == o) return true;
        SetDataResult* other = dynamic_cast<SetDataResult*>(o);
        if (!other) return false;

        return getType() == other->getType() && stat->getMzxid() == other->stat->getMzxid();
    }

    virtual int hashCode() {
        return (int) (getType() * 35 + stat->getMzxid());
    }
};

/**
 * A result from a version check operation.  No special values are available.
 */
class CheckResult : public OpResult {
public:
    CheckResult() : OpResult(ZooDefs::OpCode::check) {
    }

    virtual boolean equals(EObject* o) {
        if (this == o) return true;
        CheckResult* other = dynamic_cast<CheckResult*>(o);
		if (!other) return false;

        return getType() == other->getType();
    }

    virtual int hashCode() {
        return getType();
    }
};

/**
 * An error result from any kind of operation.  The point of error results
 * is that they contain an error code which helps understand what happened.
 * @see KeeperException.Code
 *
 */
class ErrorResult : public OpResult {
private:
	int err;

public:
    ErrorResult(int err) : OpResult(ZooDefs::OpCode::error) {
        this->err = err;
    }

    int getErr() {
        return err;
    }

    virtual boolean equals(EObject* o) {
        if (this == o) return true;
        ErrorResult* other = dynamic_cast<ErrorResult*>(o);
		if (!other) return false;

        return getType() == other->getType() && err == other->getErr();
    }

    virtual int hashCode() {
        return getType() * 35 + err;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* OpResult_HH_ */
