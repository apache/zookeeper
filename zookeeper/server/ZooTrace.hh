/*
 * ZooTrace.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ZooTrace_HH_
#define ZooTrace_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./Request.hh"
#include "./quorum/QuorumPacket.hh"

namespace efc {
namespace ezk {

/**
 * This class encapsulates and centralizes tracing for the ZooKeeper server.
 * Trace messages go to the log with TRACE level.
 * <p>
 * Log4j must be correctly configured to capture the TRACE messages.
 */
class ZooTrace {
private:
	static sp<ELogger> LOG;
    static long traceMask;

public:
    const static long CLIENT_REQUEST_TRACE_MASK = 1 << 1;

    const static long CLIENT_DATA_PACKET_TRACE_MASK = 1 << 2;

    const static long CLIENT_PING_TRACE_MASK = 1 << 3;

    const static long SERVER_PACKET_TRACE_MASK = 1 << 4;

    const static long SESSION_TRACE_MASK = 1 << 5;

    const static long EVENT_DELIVERY_TRACE_MASK = 1 << 6;

    const static long SERVER_PING_TRACE_MASK = 1 << 7;

    const static long WARNING_TRACE_MASK = 1 << 8;

    const static long JMX_TRACE_MASK = 1 << 9;

    static long getTextTraceLevel() {
        return traceMask;
    }

    static void setTextTraceLevel(long mask) {
        traceMask = mask;
        LOG->info(("Set text trace mask to 0x" + ELLong::toHexString(mask)).c_str());
    }

    static boolean isTraceEnabled(sp<ELogger>& log, long mask) {
        return log->isTraceEnabled() && (mask & traceMask) != 0;
    }

    static void logTraceMessage(sp<ELogger>& log, long mask, EString msg) {
        if (isTraceEnabled(log, mask)) {
            log->trace(msg.c_str());
        }
    }

    static void logQuorumPacket(sp<ELogger>& log, long mask,
            char direction, sp<QuorumPacket> qp);

    static void logRequest(sp<ELogger>& log, long mask,
            char rp, Request* request, EString header)
    {
        if (isTraceEnabled(log, mask)) {
            log->trace((header + ":" + rp + request->toString()).c_str());
        }
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ZooTrace_HH_ */
