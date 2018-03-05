/*
 * EBinaryOutputArchive.hh
 *
 *  Created on: 2017-11-16
 *      Author: cxxjava@163.com
 */

#ifndef EBINARYOUTPUTARCHIVE_HH_
#define EBINARYOUTPUTARCHIVE_HH_

#include "./EOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 *
 */

class EBinaryOutputArchive: public EOutputArchive {
public:
	static sp<EBinaryOutputArchive> getArchive(EOutputStream* strm);

public:
	virtual ~EBinaryOutputArchive();

	EBinaryOutputArchive(EDataOutput* out, boolean owned=false);

	virtual void writeByte(byte b, const char* tag) THROWS(EIOException);
	virtual void writeBool(boolean b, const char* tag) THROWS(EIOException);
	virtual void writeInt(int i, const char* tag) THROWS(EIOException);
	virtual void writeLLong(llong l, const char* tag) THROWS(EIOException);
	virtual void writeFloat(float f, const char* tag) THROWS(EIOException);
	virtual void writeDouble(double d, const char* tag) THROWS(EIOException);
	virtual void writeString(EString s, const char* tag) THROWS(EIOException);
	virtual void writeBuffer(EA<byte>* buf, const char* tag) THROWS(EIOException);
	virtual void writeRecord(ERecord* r, const char* tag) THROWS(EIOException);
	virtual void startRecord(ERecord* r, const char* tag) THROWS(EIOException);
	virtual void endRecord(ERecord* r, const char* tag) THROWS(EIOException);
	virtual void startVector(EObject* v, int size, const char* tag) THROWS(EIOException);
	virtual void endVector(EObject* v, const char* tag) THROWS(EIOException);
	virtual void startMap(EObject* v, int size, const char* tag) THROWS(EIOException);
	virtual void endMap(EObject* v, const char* tag) THROWS(EIOException);

private:
	EDataOutput* out;
	boolean owned;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* EBINARYOUTPUTARCHIVE_HH_ */
