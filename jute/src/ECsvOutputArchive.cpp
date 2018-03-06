/*
 * ECsvOutputArchive.cpp
 *
 *  Created on: 2017-11-16

 */

#include "../inc/ECsvOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 *
 * @param s
 * @return
 */
static EString toCSVString(EString* s) {
	if (s == null)
		return "";

	EString sb;
	sb.append('\'');
	int len = s->length();
	for (int i = 0; i < len; i++) {
		char c = s->charAt(i);
		switch(c) {
			case '\0':
				sb.append("%00");
				break;
			case '\n':
				sb.append("%0A");
				break;
			case '\r':
				sb.append("%0D");
				break;
			case ',':
				sb.append("%2C");
				break;
			case '}':
				sb.append("%7D");
				break;
			case '%':
				sb.append("%25");
				break;
			default:
				sb.append(c);
		}
	}
	return sb;
}

/**
 *
 * @param buf
 * @return
 */
static EString toCSVBuffer(EA<byte>* barr) {
	if (barr == null || barr->length() == 0) {
		return "";
	}
	EString sb;
	sb.append('#');
	for(int idx = 0; idx < barr->length(); idx++) {
		sb.append(EInteger::toHexString((*barr)[idx]));
	}
	return sb;
}

//=============================================================================

sp<ECsvOutputArchive> ECsvOutputArchive::getArchive(EOutputStream* strm) {
	return new ECsvOutputArchive(strm);
}

ECsvOutputArchive::~ECsvOutputArchive() {
	//
}

ECsvOutputArchive::ECsvOutputArchive(EOutputStream* out, bool owned) :
		out(out), stream(new EBufferedOutputStream(out, owned), true, true), isFirst(true) {
}

void ECsvOutputArchive::writeByte(byte b, const char* tag) {
	writeLLong((llong)b, tag);
}

void ECsvOutputArchive::writeBool(boolean b, const char* tag) {
	printCommaUnlessFirst();
	const char* val = b ? "T" : "F";
	stream.write(val, 1);
	throwExceptionOnError(tag);
}

void ECsvOutputArchive::writeInt(int i, const char* tag) {
	writeLLong((llong)i, tag);
}

void ECsvOutputArchive::writeLLong(llong l, const char* tag) {
	printCommaUnlessFirst();
	stream.print(l);
	throwExceptionOnError(tag);
}

void ECsvOutputArchive::writeFloat(float f, const char* tag) {
	writeDouble((double)f, tag);
}

void ECsvOutputArchive::writeDouble(double d, const char* tag) {
	printCommaUnlessFirst();
	stream.print(d);
	throwExceptionOnError(tag);
}

void ECsvOutputArchive::writeString(EString s, const char* tag) {
	printCommaUnlessFirst();
	stream.print(toCSVString(&s).c_str());
	throwExceptionOnError(tag);
}

void ECsvOutputArchive::writeBuffer(EA<byte>* buf, const char* tag) {
	printCommaUnlessFirst();
	stream.print(toCSVBuffer(buf).c_str());
	throwExceptionOnError(tag);
}

void ECsvOutputArchive::writeRecord(ERecord* r, const char* tag) {
	if (r == null) {
		return;
	}
	r->serialize(this, tag);
}

void ECsvOutputArchive::startRecord(ERecord* r, const char* tag) {
	if (tag && *tag) {
		printCommaUnlessFirst();
		stream.print("s{");
		isFirst = true;
	}
}

void ECsvOutputArchive::endRecord(ERecord* r, const char* tag) {
	if (!tag || !*tag) {
		stream.print("\n");
		isFirst = true;
	} else {
		stream.print("}");
		isFirst = false;
	}
}

void ECsvOutputArchive::startVector(EObject* v, int size, const char* tag) {
	printCommaUnlessFirst();
	stream.print("v{");
	isFirst = true;
}

void ECsvOutputArchive::endVector(EObject* v, const char* tag) {
	stream.print("}");
	isFirst = false;
}

void ECsvOutputArchive::startMap(EObject* v, int size, const char* tag) {
	printCommaUnlessFirst();
	stream.print("m{");
	isFirst = true;
}

void ECsvOutputArchive::endMap(EObject* v, const char* tag) {
	stream.print("}");
	isFirst = false;
}

void ECsvOutputArchive::printCommaUnlessFirst() {
	if (!isFirst) {
		stream.print(",");
	}
	isFirst = false;
}

void ECsvOutputArchive::throwExceptionOnError(const char* tag) {
	if (stream.checkError()) {
		throw EIOException(__FILE__, __LINE__, EString::formatOf("Error serializing %s", tag).c_str());
	}
}

} /* namespace ezk */
} /* namespace efc */
