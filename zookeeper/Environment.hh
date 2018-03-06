/*
 * Environment.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef Environment_HH_
#define Environment_HH_

#include "Efc.hh"
#include "./Version.hh"

namespace efc {
namespace ezk {

/**
 * Provide insight into the runtime environment.
 *
 */
class Environment : public EObject {

public:
	class Entry : public EObject {
	private:
		EString k;
        EString v;

	public:
        Entry(EString k, EString v) {
            this->k = k;
            this->v = v;
        }
        EString getKey() { return k; }
        EString getValue() { return v; }

        virtual EString toString() {
            return k + "=" + v;
        }
    };

private:
	static void put(EList<Entry*>* l, EString k, EString v) {
        l->add(new Entry(k,v));
    }

public:
	static sp<EList<Entry*> > list() {
        EList<Entry*>* l = new EArrayList<Entry*>();
        put(l, "zookeeper.version", Version::getFullVersion());

        try {
            put(l, "host.name",
                EInetAddress::getLocalHost().getCanonicalHostName());
        } catch (EUnknownHostException& e) {
            put(l, "host.name", "<NA>");
        }

        put(l, "java.version",
                ESystem::getProperty("java.version", "<NA>"));
        put(l, "java.vendor",
        		ESystem::getProperty("java.vendor", "<NA>"));
        put(l, "java.home",
        		ESystem::getProperty("java.home", "<NA>"));
        put(l, "java.class.path",
        		ESystem::getProperty("java.class.path", "<NA>"));
        put(l, "java.library.path",
        		ESystem::getProperty("java.library.path", "<NA>"));
        put(l, "java.io.tmpdir",
        		ESystem::getProperty("java.io.tmpdir", "<NA>"));
        put(l, "java.compiler",
        		ESystem::getProperty("java.compiler", "<NA>"));
        put(l, "os.name",
        		ESystem::getProperty("os.name", "<NA>"));
        put(l, "os.arch",
        		ESystem::getProperty("os.arch", "<NA>"));
        put(l, "os.version",
        		ESystem::getProperty("os.version", "<NA>"));
        put(l, "user.name",
        		ESystem::getProperty("user.name", "<NA>"));
        put(l, "user.home",
        		ESystem::getProperty("user.home", "<NA>"));
        put(l, "user.dir",
        		ESystem::getProperty("user.dir", "<NA>"));

        return l;
    }

    static void logEnv(EString msg, sp<ELogger> log) {
        sp<EList<Entry*> > env = Environment::list();
        sp<EIterator<Entry*> > iter = env->iterator();
        while (iter->hasNext()) {
        	Entry* e = iter->next();
            log->info(msg + e->toString());
        }
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Environment_HH_ */
