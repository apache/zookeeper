/*
 * QuorumAuthPacket.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef QuorumAuthPacket_HH_
#define QuorumAuthPacket_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "../../../jute/inc/ERecord.hh"
#include "../../../jute/inc/EBinaryInputArchive.hh"
#include "../../../jute/inc/EBinaryOutputArchive.hh"
#include "../../../jute/inc/ECsvOutputArchive.hh"

namespace efc {
namespace ezk {

class QuorumAuthPacket : public ERecord {
private:
	llong magic;
	int status;
	sp<EA<byte> > token;

public:
	QuorumAuthPacket() {
	}
	QuorumAuthPacket(llong magic, int status, sp<EA<byte> > token) {
		this->magic = magic;
		this->status = status;
		this->token = token;
	}
	llong getMagic() {
		return magic;
	}
	void setMagic(llong m_) {
		magic = m_;
	}
	int getStatus() {
		return status;
	}
	void setStatus(int m_) {
		status = m_;
	}
	sp<EA<byte> > getToken() {
		return token;
	}
	void setToken(sp<EA<byte> > m_) {
		token = m_;
	}
	virtual void serialize(EOutputArchive* a_, const char* tag)
			THROWS(EIOException) {
		a_->startRecord(this, tag);
		a_->writeLLong(magic, "magic");
		a_->writeInt(status, "status");
		a_->writeBuffer(token.get(), "token");
		a_->endRecord(this, tag);
	}
	virtual void deserialize(EInputArchive* a_, const char* tag)
			THROWS(EIOException) {
		a_->startRecord(tag);
		magic = a_->readLLong("magic");
		status = a_->readInt("status");
		token = a_->readBuffer("token");
		a_->endRecord(tag);
	}
	virtual EString toString() {
		try {
			EByteArrayOutputStream s;
			ECsvOutputArchive a_(&s);
			a_.startRecord(this, "");
			a_.writeLLong(magic, "magic");
			a_.writeInt(status, "status");
			a_.writeBuffer(token.get(), "token");
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
		QuorumAuthPacket* peer = dynamic_cast<QuorumAuthPacket*>(peer_);
		if (!peer) {
			throw EClassCastException(__FILE__, __LINE__,
					"Comparing different types of records.");
		}
		int ret = 0;
		ret = (magic == peer->magic) ? 0 : ((magic < peer->magic) ? -1 : 1);
		if (ret != 0)
			return ret;
		ret = (status == peer->status) ? 0 : ((status < peer->status) ? -1 : 1);
		if (ret != 0)
			return ret;
		{
			sp<EA<byte> > my = token;
			sp<EA<byte> > ur = peer->token;
			ret = compareBytes(my.get(), 0, my->length(), ur.get(), 0,
					ur->length());
		}
		if (ret != 0)
			return ret;
		return ret;
	}
	virtual boolean equals(EObject* peer_) {
		QuorumAuthPacket* peer = dynamic_cast<QuorumAuthPacket*>(peer_);
		if (!peer) {
			return false;
		}
		if (peer_ == this) {
			return true;
		}
		boolean ret = false;
		ret = (magic == peer->magic);
		if (!ret)
			return ret;
		ret = (status == peer->status);
		if (!ret)
			return ret;
		ret = bufEquals(token.get(), peer->token.get());
		if (!ret)
			return ret;
		return ret;
	}
	virtual int hashCode() {
		int result = 17;
		int ret;
		ret = (int) (magic ^ (((ullong) magic) >> 32));
		result = 37 * result + ret;
		ret = (int) status;
		result = 37 * result + ret;
        ret = EArrays::toString(token.get()).hashCode();
		result = 37 * result + ret;
		return result;
	}
	static EString signature() {
		return "LQuorumAuthPacket(liB)";
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumAuthPacket_HH_ */
