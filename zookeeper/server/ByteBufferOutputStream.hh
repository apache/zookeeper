/*
 * ByteBufferOutputStream.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef ByteBufferOutputStream_HH_
#define ByteBufferOutputStream_HH_

#include "Efc.hh"
#include "../../jute/inc/ERecord.hh"
#include "../../jute/inc/EBinaryInputArchive.hh"
#include "../../jute/inc/EBinaryOutputArchive.hh"

namespace efc {
namespace ezk {

class ByteBufferOutputStream : public EOutputStream {
public:
	virtual ~ByteBufferOutputStream() {
		if (owner) {
			delete bb;
		}
	}

	ByteBufferOutputStream(EIOByteBuffer* bb, boolean owner=false) {
		this->bb = bb;
		this->owner = owner;
	}

	virtual void write(int b) THROWS(EIOException) {
        bb->put((byte)b);
    }

    virtual void write(const void *b, int len) THROWS(EIOException) {
        bb->put(b, len);
    }

    static void record2ByteBuffer(ERecord* record, EIOByteBuffer* bb) THROWS(EIOException) {
    	ByteBufferOutputStream bbos(bb);
        sp<EBinaryOutputArchive> oa = EBinaryOutputArchive::getArchive(&bbos);
        record->serialize(oa.get(), "request");
    }

protected:
    EIOByteBuffer* bb;
	boolean owner;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ByteBufferOutputStream_HH_ */
