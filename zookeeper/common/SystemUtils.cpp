/*
 * SystemUtils.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./SystemUtils.hh"

namespace efc {
namespace ezk {

sp<EA<EString*> > SystemUtils::cargs2jargs(int argc, const char** argv) {
	sp<EA<EString*> > args;
	if (argc > 1) {
		args = new EA<EString*>(argc - 1);
		for (int i=1; i<argc; i++) {
			(*args)[i - 1] = new EString(argv[i]);
		}
	}
	return args;
}

} /* namespace ezk */
} /* namespace efc */
