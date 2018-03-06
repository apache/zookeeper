/*
 * ERecord.hh
 *
 *  Created on: 2017-11-16

 */

#ifndef ERECORD_HH_
#define ERECORD_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

/**
 * Interface that is implemented by generated classes.
 *
 */

class EOutputArchive;
class EInputArchive;

interface ERecord: public virtual efc::EObject {
	virtual ~ERecord() {}

	virtual void serialize(EOutputArchive* archive, const char* tag) THROWS(EIOException) = 0;
	virtual void deserialize(EInputArchive* archive, const char* tag) THROWS(EIOException) = 0;

	//@see: Utils.java
	/**
	 * equals function that actually compares two buffers.
	 *
	 * @param onearray First buffer
	 * @param twoarray Second buffer
	 * @return true if one and two contain exactly the same content, else false.
	 */
	static boolean bufEquals(EA<byte>* onearray, EA<byte>* twoarray) {
		if (onearray == twoarray) return true;
		if (!onearray || !twoarray) return false;
		boolean ret = (onearray->length() == twoarray->length());
		if (!ret) {
			return ret;
		}
		for (int idx = 0; idx < onearray->length(); idx++) {
			if ((*onearray)[idx] != (*twoarray)[idx]) {
				return false;
			}
		}
		return true;
	}

	static int compareBytes(EA<byte>* b1, int off1, int len1, EA<byte>* b2, int off2,
			int len2) {
		int i;
		for (i = 0; i < len1 && i < len2; i++) {
			if ((*b1)[off1 + i] != (*b2)[off2 + i]) {
				return (*b1)[off1 + i] < (*b2)[off2 + i] ? -1 : 1;
			}
		}
		if (len1 != len2) {
			return len1 < len2 ? -1 : 1;
		}
		return 0;
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ERECORD_HH_ */
