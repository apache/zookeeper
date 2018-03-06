/*
 * ExitCode.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef ExitCode_HH_
#define ExitCode_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

/**
 * Exit code used to exit server
 */
class ExitCode {
public:

    /* Represents unexpected error */
    static const int UNEXPECTED_ERROR = 1;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ExitCode_HH_ */
