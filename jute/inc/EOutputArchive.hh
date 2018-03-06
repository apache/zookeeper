/*
 * EOutputArchive.hh
 *
 *  Created on: 2017-11-16

 */

#ifndef EOUTPUTARCHIVE_HH_
#define EOUTPUTARCHIVE_HH_

#include "Efc.hh"
#include "./EIndex.hh"
#include "./ERecord.hh"

namespace efc {
namespace ezk {

/**
 * Interface that alll the serializers have to implement.
 *
 */

#define JUTE_SIZE(v) ((v!=null) ? v->size() : -1)

interface EOutputArchive: public ESynchronizeable {
	virtual ~EOutputArchive(){}

	virtual void writeByte(byte b, const char* tag) THROWS(EIOException) = 0;
	virtual void writeBool(boolean b, const char* tag) THROWS(EIOException) = 0;
	virtual void writeInt(int i, const char* tag) THROWS(EIOException) = 0;
	virtual void writeLLong(llong l, const char* tag) THROWS(EIOException) = 0;
	virtual void writeFloat(float f, const char* tag) THROWS(EIOException) = 0;
	virtual void writeDouble(double d, const char* tag) THROWS(EIOException) = 0;
	virtual void writeString(EString s, const char* tag) THROWS(EIOException) = 0;
	virtual void writeBuffer(EA<byte>* buf, const char* tag) THROWS(EIOException) = 0;
	virtual void writeRecord(ERecord* r, const char* tag) THROWS(EIOException) = 0;
	virtual void startRecord(ERecord* r, const char* tag) THROWS(EIOException) = 0;
	virtual void endRecord(ERecord* r, const char* tag) THROWS(EIOException) = 0;
	virtual void startVector(EObject* ignore, int size, const char* tag) THROWS(EIOException) = 0;
	virtual void endVector(EObject* ignore, const char* tag) THROWS(EIOException) = 0;
	virtual void startMap(EObject* ignore, int size, const char* tag) THROWS(EIOException) = 0;
	virtual void endMap(EObject* ignore, const char* tag) THROWS(EIOException) = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* EOUTPUTARCHIVE_HH_ */
