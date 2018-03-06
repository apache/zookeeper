/*
 * EIndex.hh
 *
 *  Created on: 2017-11-16

 */

#ifndef EINDEX_HH_
#define EINDEX_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

/**
 * Interface that acts as an iterator for deserializing maps.
 * The deserializer returns an instance that the record uses to
 * read vectors and maps. An example of usage is as follows:
 *
 * <code>
 * Index idx = startVector(...);
 * while (!idx.done()) {
 *   .... // read element of a vector
 *   idx.incr();
 * }
 * </code>
 *
 */

interface EIndex: public virtual efc::EObject {
	virtual ~EIndex() {}

	virtual boolean done() = 0;
	virtual void incr() = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* EINDEX_HH_ */
