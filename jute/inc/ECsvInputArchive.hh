/*
 * ECsvInputArchive.hh
 *
 *  Created on: 2017-11-16

 */

#ifndef ECSVINPUTARCHIVE_HH_
#define ECSVINPUTARCHIVE_HH_

#include "./EInputArchive.hh"

namespace efc {
namespace ezk {

/**
 *
 */

class ECsvInputArchive: public EInputArchive {
public:
	sp<ECsvInputArchive> getArchive(EInputStream* strm);

public:
	virtual ~ECsvInputArchive();

	ECsvInputArchive(EInputStream* in, boolean owned=false);

	virtual byte readByte(const char* tag) THROWS(EIOException);
	virtual boolean readBool(const char* tag) THROWS(EIOException);
	virtual int readInt(const char* tag) THROWS(EIOException);
	virtual llong readLLong(const char* tag) THROWS(EIOException);
	virtual float readFloat(const char* tag) THROWS(EIOException);
	virtual double readDouble(const char* tag) THROWS(EIOException);
	virtual EString readString(const char* tag) THROWS(EIOException);
	virtual sp<EA<byte> > readBuffer(const char* tag) THROWS(EIOException);
	virtual void readRecord(ERecord* r, const char* tag) THROWS(EIOException);
	virtual void startRecord(const char* tag) THROWS(EIOException);
	virtual void endRecord(const char* tag) THROWS(EIOException);
	virtual sp<EIndex> startVector(const char* tag) THROWS(EIOException);
	virtual void endVector(const char* tag) THROWS(EIOException);
	virtual sp<EIndex> startMap(const char* tag) THROWS(EIOException);
	virtual void endMap(const char* tag) THROWS(EIOException);

private:
	EPushbackInputStream* stream;

	// Since this is a rough sanity check, add some padding to maxBuffer to
	// make up for extra fields, etc. (otherwise e.g. clients may be able to
	// write buffers larger than we can read from disk!)
	EString readField(const char* tag) THROWS(EIOException);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ECSVINPUTARCHIVE_HH_ */
