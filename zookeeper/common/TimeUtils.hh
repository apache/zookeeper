/*
 * TimeUtils.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef TIMEUTILS_HH_
#define TIMEUTILS_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

class TimeUtils {
public:
	/**
	 * Returns time in milliseconds as does System.currentTimeMillis(),
	 * but uses elapsed time from an arbitrary epoch more like System.nanoTime().
	 * The difference is that if somebody changes the system clock,
	 * Time.currentElapsedTime will change but nanoTime won't. On the other hand,
	 * all of ZK assumes that time is measured in milliseconds.
	 * @return  The time in milliseconds from some arbitrary point in time.
	 */
	static llong currentElapsedTime();

	/**
	 * Explicitly returns system dependent current wall time.
	 * @return Current time in msec.
	 */
	static llong currentWallTime();

	/**
	 * This is to convert the elapsedTime to a Date.
	 * @return A date object indicated by the elapsedTime.
	 */
	static EDate elapsedTimeToDate(llong elapsedTime);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* TIMEUTILS_HH_ */
