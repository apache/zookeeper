/*
 * ProviderRegistry.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef ProviderRegistry_HH_
#define ProviderRegistry_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./AuthenticationProvider.hh"

namespace efc {
namespace ezk {

class ProviderRegistry {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(ProviderRegistry.class);

    static EHashMap<EString*, AuthenticationProvider*>* authenticationProviders;

public:
    DECLARE_STATIC_INITZZ;

    static AuthenticationProvider* getProvider(EString scheme) {
        return authenticationProviders->get(&scheme);
    }

	static EString listProviders() {
		EString sb;
		auto iter = authenticationProviders->keySet()->iterator();
		while (iter->hasNext()) {
			EString* s = iter->next();
			sb.append(s);
			sb.append(" ");
		}
		return sb;
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ProviderRegistry_HH_ */
