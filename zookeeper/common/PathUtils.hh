/*
 * PathUtils.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef PATHUTILS_HH_
#define PATHUTILS_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

/**
 * Path related utilities
 */

class PathUtils {
public:
	/** validate the provided znode path string
	 * @param path znode path string
	 * @param isSequential if the path is being created
	 * with a sequential flag
	 * @throws IllegalArgumentException if the path is invalid
	 */
	static void validatePath(EString path, boolean isSequential) THROWS(EIllegalArgumentException);

	/**
	 * Validate the provided znode path string
	 * @param path znode path string
	 * @throws IllegalArgumentException if the path is invalid
	 */
	static void validatePath(EString path) THROWS(EIllegalArgumentException);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* PATHUTILS_HH_ */
