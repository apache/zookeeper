/*
 * ECsvInputArchive.cpp
 *
 *  Created on: 2017-11-16
 *      Author: cxxjava@163.com
 */

#include "../inc/ECsvInputArchive.hh"

namespace efc {
namespace ezk {

static EString fromCSVString(EString& s) THROWS(EIOException) {
    if (s.charAt(0) != '\'') {
        throw EIOException(__FILE__, __LINE__, "Error deserializing string.");
    }
    int len = s.length();
    EString sb;// = new StringBuilder(len-1);
    for (int i = 1; i < len; i++) {
        char c = s.charAt(i);
        if (c == '%') {
            char ch1 = s.charAt(i+1);
            char ch2 = s.charAt(i+2);
            i += 2;
            if (ch1 == '0' && ch2 == '0') { sb.append('\0'); }
            else if (ch1 == '0' && ch2 == 'A') { sb.append('\n'); }
            else if (ch1 == '0' && ch2 == 'D') { sb.append('\r'); }
            else if (ch1 == '2' && ch2 == 'C') { sb.append(','); }
            else if (ch1 == '7' && ch2 == 'D') { sb.append('}'); }
            else if (ch1 == '2' && ch2 == '5') { sb.append('%'); }
            else {throw EIOException(__FILE__, __LINE__, "Error deserializing string.");}
        } else {
        	sb.append(c);
        }
    }
    return sb;
}

static sp<EA<byte> > fromCSVBuffer(EString& s) THROWS(EIOException) {
	if (s.charAt(0) != '#') {
		throw EIOException(__FILE__, __LINE__, "Error deserializing buffer.");
	}
	/*
	ByteArrayOutputStream stream =  new ByteArrayOutputStream();
	if (s.length() == 1) { return stream.toByteArray(); }
	*/
	if (s.length() == 1) { return new EA<byte>(0); }
	EByteArrayOutputStream stream;
	int blen = (s.length()-1)/2;
	EA<byte> barr(blen);
	for (int idx = 0; idx < blen; idx++) {
		/*
		 char c1 = s.charAt(2*idx+1);
		 char c2 = s.charAt(2*idx+2);
		 barr[idx] = Byte.parseByte(""+c1+c2, 16);
		 */
		char cc[3] = {0};
		eso_memcpy(cc, s.c_str() + 2*idx+1, 2);
		barr[idx] = EByte::parseByte(cc, 16);
	}
	stream.write(barr.address(), barr.length());
	return stream.toByteArray();
}

//=============================================================================

class CsvIndex: public EIndex {
private:
	EPushbackInputStream* stream;
public:
	CsvIndex(EPushbackInputStream* stream): stream(stream) {}
	virtual ~CsvIndex() {}
	virtual boolean done() {
		char c = '\0';
		try {
			c = (char) stream->read();
			stream->unread(c);
		} catch (EIOException& ex) {
		}
		return (c == '}') ? true : false;
	}

	virtual void incr() {
	}
};

sp<ECsvInputArchive> ECsvInputArchive::getArchive(EInputStream* strm) {
	return new ECsvInputArchive(new EPushbackInputStream(strm), true);
}

ECsvInputArchive::~ECsvInputArchive() {
	delete stream;
}

ECsvInputArchive::ECsvInputArchive(EInputStream* in, boolean owned) {
	stream = new EPushbackInputStream(in, owned);
}

byte ECsvInputArchive::readByte(const char* tag) {
	return (byte) readLLong(tag);
}

boolean ECsvInputArchive::readBool(const char* tag) {
	EString sval = readField(tag);
	return sval.equals("T") ? true : false;
}

int ECsvInputArchive::readInt(const char* tag) {
	return (int) readLLong(tag);
}

llong ECsvInputArchive::readLLong(const char* tag) {
	EString sval = readField(tag);
	try {
		llong lval = ELLong::parseLLong(sval.c_str());
		return lval;
	} catch (ENumberFormatException& ex) {
		EString msg("Error deserializing ");
		msg << tag;
		throw EIOException(__FILE__, __LINE__, msg.c_str());
	}
}

float ECsvInputArchive::readFloat(const char* tag) {
	return (float) readDouble(tag);
}

double ECsvInputArchive::readDouble(const char* tag) {
	EString sval = readField(tag);
	try {
		double dval = EDouble::parseDouble(sval.c_str());
		return dval;
	} catch (ENumberFormatException& ex) {
		EString msg("Error deserializing ");
		msg << tag;
		throw EIOException(__FILE__, __LINE__, msg.c_str());
	}
}

EString ECsvInputArchive::readString(const char* tag) {
	EString sval = readField(tag);
	return fromCSVString(sval);
}

sp<EA<byte> > ECsvInputArchive::readBuffer(const char* tag) {
	EString sval = readField(tag);
	return fromCSVBuffer(sval);
}

void ECsvInputArchive::readRecord(ERecord* r, const char* tag) {
	ES_ASSERT(r);
	r->deserialize(this, tag);
}

void ECsvInputArchive::startRecord(const char* tag) {
	if (tag && *tag) {
		char c1 = (char) stream->read();
		char c2 = (char) stream->read();
		if (c1 != 's' || c2 != '{') {
			EString msg("Error deserializing ");
			msg << tag;
			throw EIOException(__FILE__, __LINE__, msg.c_str());
		}
	}
}

void ECsvInputArchive::endRecord(const char* tag) {
	char c = (char) stream->read();
	if (!tag || !*tag) {
		if (c != '\n' && c != '\r') {
			throw EIOException(__FILE__, __LINE__, "Error deserializing record.");
		} else {
			return;
		}
	}

	if (c != '}') {
		EString msg("Error deserializing ");
		msg << tag;
		throw EIOException(__FILE__, __LINE__, msg.c_str());
	}
	c = (char) stream->read();
	if (c != ',') {
		stream->unread(c);
	}
}

sp<EIndex> ECsvInputArchive::startVector(const char* tag) {
	char c1 = (char) stream->read();
	char c2 = (char) stream->read();
	if (c1 != 'v' || c2 != '{') {
		EString msg("Error deserializing ");
		msg << tag;
		throw EIOException(__FILE__, __LINE__, msg.c_str());
	}
	return new CsvIndex(stream);
}

void ECsvInputArchive::endVector(const char* tag) {
	char c = (char) stream->read();
	if (c != '}') {
		EString msg("Error deserializing ");
		msg << tag;
		throw EIOException(__FILE__, __LINE__, msg.c_str());
	}
	c = (char) stream->read();
	if (c != ',') {
		stream->unread(c);
	}
}

sp<EIndex> ECsvInputArchive::startMap(const char* tag) {
	char c1 = (char) stream->read();
	char c2 = (char) stream->read();
	if (c1 != 'm' || c2 != '{') {
		EString msg("Error deserializing ");
		msg << tag;
		throw EIOException(__FILE__, __LINE__, msg.c_str());
	}
	return new CsvIndex(stream);
}

void ECsvInputArchive::endMap(const char* tag) {
	char c = (char) stream->read();
	if (c != '}') {
		EString msg("Error deserializing ");
		msg << tag;
		throw EIOException(__FILE__, __LINE__, msg.c_str());
	}
	c = (char) stream->read();
	if (c != ',') {
		stream->unread(c);
	}
}

EString ECsvInputArchive::readField(const char* tag) {
	try {
		EString buf;
		while (true) {
			char c = (char) stream->read();
			switch (c) {
				case ',':
					return buf;
				case '}':
				case '\n':
				case '\r':
					stream->unread(c);
					return buf;
				default:
					buf.append(c);
					continue;
			}
		}
	} catch (EIOException& ex) {
		EString msg("Error reading ");
		msg << tag;
		throw EIOException(__FILE__, __LINE__, msg.c_str());
	}
}

} /* namespace ezk */
} /* namespace efc */
