/*
 * EBinaryInputArchive.cpp
 *
 *  Created on: 2017-11-16
 *      Author: cxxjava@163.com
 */

#include "../inc/EBinaryInputArchive.hh"

namespace efc {
namespace ezk {

int EBinaryInputArchive::maxBuffer;

DEFINE_STATIC_INITZZ_BEGIN(EBinaryInputArchive)
    ESystem::_initzz_();
    maxBuffer = EInteger::parseInt(ESystem::getProperty("jute.maxbuffer", "1048575")); //0xfffff
DEFINE_STATIC_INITZZ_END

class BinaryIndex: public EIndex {
private:
	int nelems;
public:
	virtual ~BinaryIndex() {}
	BinaryIndex(int nelems) {
		this->nelems = nelems;
	}
	virtual boolean done() {
		return (nelems <= 0);
	}

	virtual void incr() {
		nelems--;
	}
};

sp<EBinaryInputArchive> EBinaryInputArchive::getArchive(EInputStream* strm) {
	return new EBinaryInputArchive(new EDataInputStream(strm), true);
}

EBinaryInputArchive::~EBinaryInputArchive() {
	if (owned) {
		delete in;
	}
}

EBinaryInputArchive::EBinaryInputArchive(EDataInput* in, boolean owned) {
	this->in = in;
	this->owned = owned;
}

byte EBinaryInputArchive::readByte(const char* tag) {
	return in->readByte();
}

boolean EBinaryInputArchive::readBool(const char* tag) {
	return in->readBoolean();
}

int EBinaryInputArchive::readInt(const char* tag) {
	return in->readInt();
}

llong EBinaryInputArchive::readLLong(const char* tag) {
	return in->readLLong();
}

float EBinaryInputArchive::readFloat(const char* tag) {
	return in->readFloat();
}

double EBinaryInputArchive::readDouble(const char* tag) {
	return in->readFloat();
}

EString EBinaryInputArchive::readString(const char* tag) {
	int len = in->readInt();
	if (len == -1) return null;
	checkLength(len);
	if (len < 8192) {
		byte b[len];
		in->readFully(b, len);
		return EString((char*)b, 0, len);
	} else {
		EA<byte> b(len);
		in->readFully(b.address(), len);
		return EString((char*)b.address(), 0, len);
	}
}

sp<EA<byte> > EBinaryInputArchive::readBuffer(const char* tag) {
	int len = readInt(tag);
	if (len == -1) return null;
	checkLength(len);
	sp<EA<byte> > arr = new EA<byte>(len);
	in->readFully(arr->address(), len);
	return arr;
}

void EBinaryInputArchive::readRecord(ERecord* r, const char* tag) {
	ES_ASSERT(r);
	r->deserialize(this, tag);
}

void EBinaryInputArchive::startRecord(const char* tag) {
	//
}

void EBinaryInputArchive::endRecord(const char* tag) {
	//
}

sp<EIndex> EBinaryInputArchive::startVector(const char* tag) {
	int len = readInt(tag);
	if (len == -1) {
		return null;
	}
	return new BinaryIndex(len);
}

void EBinaryInputArchive::endVector(const char* tag) {
	//
}

sp<EIndex> EBinaryInputArchive::startMap(const char* tag) {
	return new BinaryIndex(readInt(tag));
}

void EBinaryInputArchive::endMap(const char* tag) {
	//
}

void EBinaryInputArchive::checkLength(int len) {
	if (len < 0 || len > maxBuffer + 1024) {
		throw EIOException(__FILE__, __LINE__, (EString("Unreasonable length = ") + len).c_str());
	}
}

} /* namespace ezk */
} /* namespace efc */
