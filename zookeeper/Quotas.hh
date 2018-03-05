/*
 * Quotas.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef Quotas_HH_
#define Quotas_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

/**
 * this class manages quotas
 * and has many other utils
 * for quota
 */
class Quotas {
public:

    /** the zookeeper nodes that acts as the management and status node **/
	constexpr static char const* procZookeeper =  "/zookeeper";

    /** the zookeeper quota node that acts as the quota
     * management node for zookeeper */
	constexpr static char const* quotaZookeeper = "/zookeeper/quota";

    /**
     * the limit node that has the limit of
     * a subtree
     */
	constexpr static char const* limitNode = "zookeeper_limits";

    /**
     * the stat node that monitors the limit of
     * a subtree.
     */
	constexpr static char const* statNode = "zookeeper_stats";

    /**
     * return the quota path associated with this
     * prefix
     * @param path the actual path in zookeeper.
     * @return the limit quota path
     */
    static EString quotaPath(EString path) {
        return quotaZookeeper + path +
        "/" + limitNode;
    }

    /**
     * return the stat quota path associated with this
     * prefix.
     * @param path the actual path in zookeeper
     * @return the stat quota path
     */
    static EString statPath(EString path) {
        return quotaZookeeper + path + "/" +
        statNode;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Quotas_HH_ */
