/*
 * LearnerInfo.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef LearnerInfo_HH_
#define LearnerInfo_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "../../../jute/inc/ERecord.hh"
#include "../../../jute/inc/EBinaryInputArchive.hh"
#include "../../../jute/inc/EBinaryOutputArchive.hh"
#include "../../../jute/inc/ECsvOutputArchive.hh"

namespace efc {
namespace ezk {

class LearnerInfo : public ERecord {
private:
	llong serverid;
	int protocolVersion;

public:
	LearnerInfo() {
	}
	LearnerInfo(llong serverid, int protocolVersion) {
		this->serverid = serverid;
		this->protocolVersion = protocolVersion;
	}
	llong getServerid() {
		return serverid;
	}
	void setServerid(llong m_) {
		serverid = m_;
	}
	int getProtocolVersion() {
		return protocolVersion;
	}
	void setProtocolVersion(int m_) {
		protocolVersion = m_;
	}
	virtual void serialize(EOutputArchive* a_, const char* tag)
			THROWS(EIOException) {
		a_->startRecord(this, tag);
		a_->writeLLong(serverid, "serverid");
		a_->writeInt(protocolVersion, "protocolVersion");
		a_->endRecord(this, tag);
	}
	virtual void deserialize(EInputArchive* a_, const char* tag)
			THROWS(EIOException) {
		a_->startRecord(tag);
		serverid = a_->readLLong("serverid");
		protocolVersion = a_->readInt("protocolVersion");
		a_->endRecord(tag);
	}
	virtual EString toString() {
		try {
			EByteArrayOutputStream s;
			ECsvOutputArchive a_(&s);
			a_.startRecord(this, "");
			a_.writeLLong(serverid, "serverid");
			a_.writeInt(protocolVersion, "protocolVersion");
			a_.endRecord(this, "");
			s.write('\0');
			return (char*) s.data();
		} catch (EThrowable& ex) {
			ex.printStackTrace();
		}
		return "ERROR";
	}
	void write(EDataOutput* out) THROWS(EIOException) {
		EBinaryOutputArchive archive(out);
		serialize(&archive, "");
	}
	void readFields(EDataInput* in) THROWS(EIOException) {
		EBinaryInputArchive archive(in);
		deserialize(&archive, "");
	}
	virtual int compareTo(EObject* peer_) THROWS(EClassCastException) {
		LearnerInfo* peer = dynamic_cast<LearnerInfo*>(peer_);
		if (!peer) {
			throw EClassCastException(__FILE__, __LINE__,
					"Comparing different types of records.");
		}
		int ret = 0;
		ret = (serverid == peer->serverid) ?
				0 : ((serverid < peer->serverid) ? -1 : 1);
		if (ret != 0)
			return ret;
		ret = (protocolVersion == peer->protocolVersion) ?
				0 : ((protocolVersion < peer->protocolVersion) ? -1 : 1);
		if (ret != 0)
			return ret;
		return ret;
	}
	virtual boolean equals(EObject* peer_) {
		LearnerInfo* peer = dynamic_cast<LearnerInfo*>(peer_);
		if (!peer) {
			return false;
		}
		if (peer_ == this) {
			return true;
		}
		boolean ret = false;
		ret = (serverid == peer->serverid);
		if (!ret)
			return ret;
		ret = (protocolVersion == peer->protocolVersion);
		if (!ret)
			return ret;
		return ret;
	}
	virtual int hashCode() {
		int result = 17;
		int ret;
		ret = (int) (serverid ^ (((ullong) serverid) >> 32));
		result = 37 * result + ret;
		ret = (int) protocolVersion;
		result = 37 * result + ret;
		return result;
	}
	static EString signature() {
		return "LLearnerInfo(li)";
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* LearnerInfo_HH_ */
