/*
 * ByteBufferInputStream.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ByteBufferInputStream_HH_
#define ByteBufferInputStream_HH_

#include "Efc.hh"
#include "../../jute/inc/ERecord.hh"
#include "../../jute/inc/EBinaryInputArchive.hh"

namespace efc {
namespace ezk {

class ByteBufferInputStream : public EInputStream {
public:
	virtual ~ByteBufferInputStream() {
		if (owner) {
			delete bb;
		}
	}

    ByteBufferInputStream(EIOByteBuffer* bb, boolean owner=false) {
        this->bb = bb;
        this->owner = owner;
    }

    virtual int read() THROWS(EIOException) {
        if (bb->remaining() == 0) {
            return -1;
        }
        return bb->get() & 0xff;
    }

    virtual long available() THROWS(EIOException) {
        return bb->remaining();
    }

    virtual int read(void *b, int len) THROWS(EIOException) {
        if (bb->remaining() == 0) {
            return -1;
        }
        if (len > bb->remaining()) {
            len = bb->remaining();
        }
        bb->get(b, len, len);
        return len;
    }

    virtual long skip(long n) THROWS(EIOException) {
        long newPos = bb->position() + n;
        if (newPos > bb->remaining()) {
            n = bb->remaining();
        }
        bb->position(bb->position() + (int) n);
        return n;
    }

    static void byteBuffer2Record(EIOByteBuffer* bb, ERecord* record) THROWS(EIOException) {
    	ByteBufferInputStream bbis(bb);
    	sp<EBinaryInputArchive> ia = EBinaryInputArchive::getArchive(&bbis);
        record->deserialize(ia.get(), "request");
    }

protected:
	EIOByteBuffer* bb;
	boolean owner;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ByteBufferInputStream_HH_ */
