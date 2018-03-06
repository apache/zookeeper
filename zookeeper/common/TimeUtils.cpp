/*
 * TimeUtils.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./TimeUtils.hh"

namespace efc {
namespace ezk {

llong TimeUtils::currentElapsedTime() {
	return ESystem::nanoTime() / 1000000;
}

llong TimeUtils::currentWallTime() {
	return ESystem::currentTimeMillis();
}

EDate TimeUtils::elapsedTimeToDate(llong elapsedTime) {
	llong wallTime = currentWallTime() + elapsedTime - currentElapsedTime();
	return EDate(wallTime);
}

} /* namespace ezk */
} /* namespace efc */
