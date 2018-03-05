/*
 * DigestAuthenticationProvider.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef DigestAuthenticationProvider_HH_
#define DigestAuthenticationProvider_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./AuthenticationProvider.hh"
#include "../ServerCnxn.hh"
#include "../../data/Id.hh"
#include "../../KeeperException.hh"

namespace efc {
namespace ezk {

class DigestAuthenticationProvider : public AuthenticationProvider {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(DigestAuthenticationProvider.class);

public:
    EString getScheme() {
        return "digest";
    }

    static EString generateDigest(EString idPassword) {
    	EArray<EString*> parts = EPattern::split(":", idPassword.c_str(), 2);

        char digest[ES_SHA1_BASE64SIZE];
    	eso_sha1_base64(idPassword.c_str(), idPassword.length(), digest);

        return parts[0]->toString() + ":" + (char*)digest;
    }

    virtual KeeperException::Code handleAuthentication(sp<ServerCnxn> cnxn, byte* authData, int dataLen) {
    	/** specify a command line property with key of
		 * "zookeeper.DigestAuthenticationProvider.superDigest"
		 * and value of "super:<base64encoded(SHA1(password))>" to enable
		 * super user access (i.e. acls disabled)
		 */
    	EString superDigest = ESystem::getProperty(
    	        "zookeeper.DigestAuthenticationProvider.superDigest");

        EString id((char*)authData, 0, dataLen);
		EString digest = generateDigest(id);
		if (digest.equals(superDigest)) {
			cnxn->addAuthInfo(new Id("super", ""));
		}
		cnxn->addAuthInfo(new Id(getScheme(), digest));
		return KeeperException::Code::OK;
    }

    virtual boolean matches(EString id, EString aclExpr) {
        return id.equals(aclExpr);
    }

    virtual boolean isAuthenticated() {
        return true;
    }

    virtual boolean isValid(EString id) {
    	EArray<EString*> parts = EPattern::split(":", id.c_str(), 0);
        return parts.length() == 2;
    }

#if 0
    /** Call with a single argument of user:pass to generate authdata.
     * Authdata output can be used when setting superDigest for example. 
     * @param args single argument of user:pass
     * @throws NoSuchAlgorithmException
     */
    public static void main(String args[]) throws NoSuchAlgorithmException {
        for (int i = 0; i < args.length; i++) {
            System.out.println(args[i] + "->" + generateDigest(args[i]));
        }
    }
#endif
};

} /* namespace ezk */
} /* namespace efc */
#endif /* DigestAuthenticationProvider_HH_ */
