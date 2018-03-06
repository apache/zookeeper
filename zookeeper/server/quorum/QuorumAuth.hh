/*
 * QuorumAuth.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef QuorumAuth_HH_
#define QuorumAuth_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./QuorumAuthPacket.hh"

namespace efc {
namespace ezk {

class QuorumAuth {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(QuorumAuth.class);

public:
	constexpr static const char* QUORUM_SASL_AUTH_ENABLED = "quorum.auth.enableSasl";
	constexpr static const char* QUORUM_SERVER_SASL_AUTH_REQUIRED = "quorum.auth.serverRequireSasl";
	constexpr static const char* QUORUM_LEARNER_SASL_AUTH_REQUIRED = "quorum.auth.learnerRequireSasl";

	constexpr static const char* QUORUM_KERBEROS_SERVICE_PRINCIPAL = "quorum.auth.kerberos.servicePrincipal";
	constexpr static const char* QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE = "zkquorum/localhost";

	constexpr static const char* QUORUM_LEARNER_SASL_LOGIN_CONTEXT = "quorum.auth.learner.saslLoginContext";
	constexpr static const char* QUORUM_LEARNER_SASL_LOGIN_CONTEXT_DFAULT_VALUE = "QuorumLearner";

	constexpr static const char* QUORUM_SERVER_SASL_LOGIN_CONTEXT = "quorum.auth.server.saslLoginContext";
	constexpr static const char* QUORUM_SERVER_SASL_LOGIN_CONTEXT_DFAULT_VALUE = "QuorumServer";

	constexpr static const char* QUORUM_SERVER_PROTOCOL_NAME = "zookeeper-quorum";
	constexpr static const char* QUORUM_SERVER_SASL_DIGEST = "zk-quorum-sasl-md5";
	constexpr static const char* QUORUM_AUTH_MESSAGE_TAG = "qpconnect";

    // this is negative, so that if a learner that does auth, connects to a
    // server, it'll think the received packet is an authentication packet
    static const llong QUORUM_AUTH_MAGIC_NUMBER = -0xa0dbcafecafe1234L;

    enum Status {
         IN_PROGRESS = 0,
         SUCCESS = 1,
         ERROR = -1
    };

    static Status getStatus(int status) {
		switch (status) {
		case 0:
			return IN_PROGRESS;
		case 1:
			return SUCCESS;
		case -1:
			return ERROR;
		default:
			LOG->error(EString("Unknown status:") + status);
			return ERROR;
		}
	}

    static sp<QuorumAuthPacket> createPacket(Status status, sp<EA<byte> > response) {
        return new QuorumAuthPacket(QUORUM_AUTH_MAGIC_NUMBER,
                                    status, response);
    }

    static boolean nextPacketIsAuth(EDataInputStream* din)
            THROWS(EIOException) {
        din->mark(32);
        EBinaryInputArchive bia(din);
        boolean firstIsAuth = (bia.readLLong("NO_TAG")
                               == QuorumAuth::QUORUM_AUTH_MAGIC_NUMBER);
        din->reset();
        return firstIsAuth;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumAuth_HH_ */
