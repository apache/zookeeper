/*
 * EBinaryOutputArchive.cpp
 *
 *  Created on: 2017-11-16
 *      Author: cxxjava@163.com
 */

#include "../inc/EBinaryOutputArchive.hh"

namespace efc {
namespace ezk {

sp<EBinaryOutputArchive> EBinaryOutputArchive::getArchive(EOutputStream* strm) {
	return new EBinaryOutputArchive(new EDataOutputStream(strm), true);
}

EBinaryOutputArchive::~EBinaryOutputArchive() {
	if (owned) {
		delete out;
	}
}

EBinaryOutputArchive::EBinaryOutputArchive(EDataOutput* out, bool owned) {
	this->out = out;
	this->owned = owned;
}

void EBinaryOutputArchive::writeByte(byte b, const char* tag) {
	out->writeByte(b);
}

void EBinaryOutputArchive::writeBool(boolean b, const char* tag) {
	out->writeBoolean(b);
}

void EBinaryOutputArchive::writeInt(int i, const char* tag) {
	out->writeInt(i);
}

void EBinaryOutputArchive::writeLLong(llong l, const char* tag) {
	out->writeLLong(l);
}

void EBinaryOutputArchive::writeFloat(float f, const char* tag) {
	out->writeFloat(f);
}

void EBinaryOutputArchive::writeDouble(double d, const char* tag) {
	out->writeDouble(d);
}

void EBinaryOutputArchive::writeString(EString s, const char* tag) {
	/* @see:
    if (s == null) {
		writeInt(-1, "len");
		return;
	}
    */
	int len = s.length();
	writeInt(len, "len");
	out->write(s.c_str(), len);
}

void EBinaryOutputArchive::writeBuffer(EA<byte>* buf, const char* tag) {
	if (buf == null) {
		out->writeInt(-1);
		return;
	}
	out->writeInt(buf->length());
	out->write(buf->address(), buf->length());
}

void EBinaryOutputArchive::writeRecord(ERecord* r, const char* tag) {
	r->serialize(this, tag);
}

void EBinaryOutputArchive::startRecord(ERecord* r, const char* tag) {
	//
}

void EBinaryOutputArchive::endRecord(ERecord* r, const char* tag) {
	//
}

void EBinaryOutputArchive::startVector(EObject* v, int size, const char* tag) {
	if (size < 0) {
		writeInt(-1, tag);
		return;
	}
	writeInt(size, tag);
}

void EBinaryOutputArchive::endVector(EObject* v, const char* tag) {
	//
}

void EBinaryOutputArchive::startMap(EObject* v, int size, const char* tag) {
	ES_ASSERT(v); //!!!

	if (size < 0) {
		writeInt(-1, tag);
		return;
	}
	writeInt(size, tag);
}

void EBinaryOutputArchive::endMap(EObject* v, const char* tag) {
	//

}

} /* namespace ezk */
} /* namespace efc */
