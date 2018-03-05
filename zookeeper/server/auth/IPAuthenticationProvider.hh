/*
 * IPAuthenticationProvider.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef IPAuthenticationProvider_HH_
#define IPAuthenticationProvider_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./AuthenticationProvider.hh"
#include "../ServerCnxn.hh"
#include "../../data/Id.hh"
#include "../../KeeperException.hh"

namespace efc {
namespace ezk {

class IPAuthenticationProvider : public AuthenticationProvider {
public:
	EString getScheme() {
        return "ip";
    }

	virtual KeeperException::Code handleAuthentication(sp<ServerCnxn> cnxn, byte* authData, int dataLen) {
        EString id = cnxn->getRemoteSocketAddress()->getAddress()->getHostAddress();
        cnxn->addAuthInfo(new Id(getScheme(), id));
        return KeeperException::Code::OK;
    }

	virtual boolean matches(EString id, EString aclExpr) {
		EArray<EString*> parts = EPattern::split("/", aclExpr.c_str(), 2);
		EA<byte> aclAddr = addr2Bytes(parts[0]);
		if (aclAddr.length() == 0) {
			return false;
		}
        int bits = aclAddr.length() * 8;
        if (parts.size() == 2) {
            try {
                bits = EInteger::parseInt(parts[1]->toString().c_str());
                if (bits < 0 || bits > aclAddr.length() * 8) {
                    return false;
                }
            } catch (ENumberFormatException& e) {
                return false;
            }
        }
        mask(aclAddr, bits);
        EA<byte> remoteAddr = addr2Bytes(id);
        if (remoteAddr.length() == 0) {
            return false;
        }
        mask(remoteAddr, bits);
        for (int i = 0; i < remoteAddr.length(); i++) {
            if (remoteAddr[i] != aclAddr[i]) {
                return false;
            }
        }
        return true;
    }

	virtual boolean isAuthenticated() {
        return false;
    }

	virtual boolean isValid(EString id) {
        return addr2Bytes(id).length() != 0;
    }

private:

    // This is a bit weird but we need to return the address and the number of
    // bytes (to distinguish between IPv4 and IPv6
	EA<byte> addr2Bytes(EString addr) {
		EA<byte> b = v4addr2Bytes(addr);
        // TODO Write the v6addr2Bytes
        return b;
    }

	EA<byte> v4addr2Bytes(EString addr) {
		EArray<EString*> parts = EPattern::split("\\.", addr.c_str(), -1);
        if (parts.length() != 4) {
            return EA<byte>(0);
        }
        EA<byte> b(4);
        for (int i = 0; i < 4; i++) {
            try {
                int v = EInteger::parseInt(parts[i]->toString().c_str());
                if (v >= 0 && v <= 255) {
                    b[i] = (byte) v;
                } else {
                    return EA<byte>(0);
                }
            } catch (ENumberFormatException& e) {
            	return EA<byte>(0);
            }
        }
        return b;
    }

    void mask(EA<byte>& b, int bits) {
        int start = bits / 8;
        int startMask = (1 << (8 - (bits % 8))) - 1;
        startMask = ~startMask;
        while (start < b.length()) {
            b[start] &= startMask;
            startMask = 0;
            start++;
        }
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* IPAuthenticationProvider_HH_ */
