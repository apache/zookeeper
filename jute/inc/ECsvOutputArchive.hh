/*
 * ECsvOutputArchive.hh
 *
 *  Created on: 2017-11-16

 */

#ifndef ECSVOUTPUTARCHIVE_HH_
#define ECSVOUTPUTARCHIVE_HH_

#include "./EOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 *
 */

class ECsvOutputArchive: public EOutputArchive {
public:
	static sp<ECsvOutputArchive> getArchive(EOutputStream* strm);

public:
	virtual ~ECsvOutputArchive();

	ECsvOutputArchive(EOutputStream* out, boolean owned=false);

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
	EOutputStream* out;
	EPrintStream stream;

	boolean isFirst;// = true;

	void printCommaUnlessFirst();

	void throwExceptionOnError(const char* tag) THROWS(EIOException);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ECSVOUTPUTARCHIVE_HH_ */
