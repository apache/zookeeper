/*
 * EInputArchive.hh
 *
 *  Created on: 2017-11-16

 */

#ifndef EINPUTARCHIVE_HH_
#define EINPUTARCHIVE_HH_

#include "Efc.hh"
#include "./EIndex.hh"
#include "./ERecord.hh"

namespace efc {
namespace ezk {

/**
 * Interface that all the Deserializers have to implement.
 *
 */

interface EInputArchive: public ESynchronizeable {
	virtual ~EInputArchive(){}

	virtual byte readByte(const char* tag) THROWS(EIOException) = 0;
	virtual boolean readBool(const char* tag) THROWS(EIOException) = 0;
	virtual int readInt(const char* tag) THROWS(EIOException) = 0;
	virtual llong readLLong(const char* tag) THROWS(EIOException) = 0;
	virtual float readFloat(const char* tag) THROWS(EIOException) = 0;
	virtual double readDouble(const char* tag) THROWS(EIOException) = 0;
	virtual EString readString(const char* tag) THROWS(EIOException) = 0;
	virtual sp<EA<byte> > readBuffer(const char* tag) THROWS(EIOException) = 0;
	virtual void readRecord(ERecord* r, const char* tag) THROWS(EIOException) = 0;
	virtual void startRecord(const char* tag) THROWS(EIOException) = 0;
	virtual void endRecord(const char* tag) THROWS(EIOException) = 0;
	virtual sp<EIndex> startVector(const char* tag) THROWS(EIOException) = 0;
	virtual void endVector(const char* tag) THROWS(EIOException) = 0;
	virtual sp<EIndex> startMap(const char* tag) THROWS(EIOException) = 0;
	virtual void endMap(const char* tag) THROWS(EIOException) = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* EINPUTARCHIVE_HH_ */
