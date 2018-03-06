/*
 * StatsTrack.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef Version_HH_
#define Version_HH_

#include "Efc.hh"
#include "./version/Info.hh"

namespace efc {
namespace ezk {

class Version : public Info {
public:

    /*
     * Since the SVN to Git port this field doesn't return the revision anymore
     * TODO: remove this method and associated field declaration in VerGen
     * @see {@link #getHashRevision()}
     * @return the default value -1
     */
    static int getRevision() {
        return -1; //REVISION;
    }

    static EString getRevisionHash() {
        return "-1"; //REVISION_HASH;
    }

    static EString getBuildDate() {
        return __DATE__; //BUILD_DATE;
    }

    static EString getVersion() {
        return EString(MAJOR) + "." + MINOR + "." + MICRO;
    }

    static EString getVersionRevision() {
        return getVersion() + "-" + getRevisionHash();
    }

    static EString getFullVersion() {
        return getVersionRevision() + ", built on " + getBuildDate();
    }

    static void printUsage() {
        ESystem::out->print("Usage:\tjava -cp ... org.apache.zookeeper.Version "
                            "[--full | --short | --revision],\n\tPrints --full version "
                            "info if no arg specified.");
        ESystem::exit(1);
    }

};

} /* namespace ezk */
} /* namespace efc */
#endif /* Version_HH_ */
