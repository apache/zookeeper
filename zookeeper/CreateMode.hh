/*
 * CreateMode.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef CreateMode_HH_
#define CreateMode_HH_

#include "Efc.hh"
#include "./ZooDefs.hh"
#include "./KeeperException.hh"
#include "./data/Stat.hh"

namespace efc {
namespace ezk {

/***
 *  CreateMode value determines how the znode is created on ZooKeeper.
 */
class CreateMode: public EObject {
public:
	enum Mode {
		/**
		 * The znode will not be automatically deleted upon client's disconnect.
		 */
		PERSISTENT = 0,// (0, false, false),
		/**
		* The znode will not be automatically deleted upon client's disconnect,
		* and its name will be appended with a monotonically increasing number.
		*/
		PERSISTENT_SEQUENTIAL = 2,// (2, false, true),
		/**
		 * The znode will be deleted upon the client's disconnect.
		 */
		EPHEMERAL = 1,// (1, true, false),
		/**
		 * The znode will be deleted upon the client's disconnect, and its name
		 * will be appended with a monotonically increasing number.
		 */
		EPHEMERAL_SEQUENTIAL = 3// (3, true, true);
	};

private:
    boolean ephemeral;
    boolean sequential;
    int flag;

public:
    CreateMode(int flag, boolean ephemeral, boolean sequential) {
        this->flag = flag;
        this->ephemeral = ephemeral;
        this->sequential = sequential;
    }

    boolean isEphemeral() {
        return ephemeral;
    }

    boolean isSequential() {
        return sequential;
    }

    int toFlag() {
        return flag;
    }

    virtual boolean equals(EObject* o) {
    	if (this == o) return true;
    	CreateMode* other = dynamic_cast<CreateMode*>(o);
    	if (!other) {
    		return false;
    	}
    	return (flag == other->flag && ephemeral == other->ephemeral && sequential == other->sequential);
    }

    /**
     * Map an integer value to a CreateMode value
     */
    static CreateMode fromFlag(int flag) THROWS(KeeperException) {
        switch(flag) {
        case PERSISTENT: return CreateMode(0, false, false);

        case EPHEMERAL: return CreateMode(1, true, false);

        case PERSISTENT_SEQUENTIAL: return CreateMode(2, false, true);

        case EPHEMERAL_SEQUENTIAL: return CreateMode(3, true, true);

        default:
            EString errMsg = EString("Received an invalid flag value: ") + (int)flag
                    + " to convert to a CreateMode";
            throw BadArgumentsException(__FILE__, __LINE__, errMsg);
        }
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* CreateMode_HH_ */
