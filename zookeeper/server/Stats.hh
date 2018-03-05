/*
 * Stats.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef Stats_HH_
#define Stats_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

/**
 * Statistics on the ServerCnxn
 */
interface Stats : virtual public EObject {
	virtual ~Stats() {}

    /** Date/time the connection was established
     * @since 3.3.0 */
    virtual EDate getEstablished() = 0;

    /**
     * The number of requests that have been submitted but not yet
     * responded to.
     */
    virtual llong getOutstandingRequests() = 0;
    /** Number of packets received */
    virtual llong getPacketsReceived() = 0;
    /** Number of packets sent (incl notifications) */
    virtual llong getPacketsSent() = 0;
    /** Min latency in ms
     * @since 3.3.0 */
    virtual llong getMinLatency() = 0;
    /** Average latency in ms
     * @since 3.3.0 */
    virtual llong getAvgLatency() = 0;
    /** Max latency in ms
     * @since 3.3.0 */
    virtual llong getMaxLatency() = 0;
    /** Last operation performed by this connection
     * @since 3.3.0 */
    virtual EString getLastOperation() = 0;
    /** Last cxid of this connection
     * @since 3.3.0 */
    virtual llong getLastCxid() = 0;
    /** Last zxid of this connection
     * @since 3.3.0 */
    virtual llong getLastZxid() = 0;
    /** Last time server sent a response to client on this connection
     * @since 3.3.0 */
    virtual llong getLastResponseTime() = 0;
    /** Latency of last response to client on this connection in ms
     * @since 3.3.0 */
    virtual llong getLastLatency() = 0;

    /** Reset counters
     * @since 3.3.0 */
    virtual void resetStats() = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Stats_HH_ */
