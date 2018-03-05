/*
 * ProviderRegistry.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./ProviderRegistry.hh"
#include "./DigestAuthenticationProvider.hh"
#include "./IPAuthenticationProvider.hh"

namespace efc {
namespace ezk {

sp<ELogger> ProviderRegistry::LOG = ELoggerManager::getLogger("ProviderRegistry");

EHashMap<EString*, AuthenticationProvider*>* ProviderRegistry::authenticationProviders;

DEFINE_STATIC_INITZZ_BEGIN(ProviderRegistry)
    ESystem::_initzz_();
    authenticationProviders = new EHashMap<EString*, AuthenticationProvider*>();

    IPAuthenticationProvider* ipp = new IPAuthenticationProvider();
    DigestAuthenticationProvider* digp = new DigestAuthenticationProvider();
    authenticationProviders->put(new EString(ipp->getScheme()), ipp);
    authenticationProviders->put(new EString(digp->getScheme()), digp);
DEFINE_STATIC_INITZZ_END

} /* namespace ezk */
} /* namespace efc */
