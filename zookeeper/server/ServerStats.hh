/*
 * ServerStats.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef ServerStats_HH_
#define ServerStats_HH_

#include "Efc.hh"

#include "../common/TimeUtils.hh"

namespace efc {
namespace ezk {

interface Provider : virtual public EObject {
	virtual ~Provider() {}
	virtual llong getOutstandingRequests() = 0;
	virtual llong getLastProcessedZxid() = 0;
	virtual EString getState() = 0;
	virtual int getNumAliveConnections() = 0;
};

/**
 * Basic Server Statistics
 */
class ServerStats : public ESynchronizeable {
private:
	llong packetsSent;
	llong packetsReceived;
    llong maxLatency;
    llong minLatency;// = Long.MAX_VALUE;
    llong totalLatency;// = 0;
    llong count;// = 0;

    Provider* provider;
    
public:
    ServerStats(Provider* provider) :
    	packetsSent(0),
    	packetsReceived(0),
    	maxLatency(0),
    	minLatency(ELLong::MAX_VALUE),
    	totalLatency(0),
    	count(0) {
        this->provider = provider;
    }
    
    // getters
    synchronized llong getMinLatency() {
    	SYNCHRONIZED(this) {
    		return minLatency == ELLong::MAX_VALUE ? 0 : minLatency;
    	}}
    }

    synchronized llong getAvgLatency() {
    	SYNCHRONIZED(this) {
			if (count != 0) {
				return totalLatency / count;
			}
			return 0;
    	}}
    }

    synchronized llong getMaxLatency() {
    	SYNCHRONIZED(this) {
    		return maxLatency;
    	}}
    }

    llong getOutstandingRequests() {
        return provider->getOutstandingRequests();
    }
    
    llong getLastProcessedZxid(){
        return provider->getLastProcessedZxid();
    }
    
    synchronized llong getPacketsReceived() {
    	SYNCHRONIZED(this) {
    		return packetsReceived;
    	}}
    }

    synchronized llong getPacketsSent() {
    	SYNCHRONIZED(this) {
    		return packetsSent;
    	}}
    }

    EString getServerState() {
        return provider->getState();
    }
    
    /** The number of client connections alive to this server */
    int getNumAliveClientConnections() {
    	return provider->getNumAliveConnections();
    }

    virtual EString toString(){
    	EString sb;// = new StringBuilder();
        sb.append(EString("Latency min/avg/max: ") + getMinLatency() + "/"
                + getAvgLatency() + "/" + getMaxLatency() + "\n");
        sb.append(EString("Received: ") + getPacketsReceived() + "\n");
        sb.append(EString("Sent: ") + getPacketsSent() + "\n");
        sb.append(EString("Connections: ") + getNumAliveClientConnections() + "\n");

        if (provider != null) {
            sb.append(EString("Outstanding: ") + getOutstandingRequests() + "\n");
            sb.append(EString("Zxid: 0x")+ ELLong::toHexString(getLastProcessedZxid())+ "\n");
        }
        sb.append("Mode: " + getServerState() + "\n");
        return sb;
    }
    // mutators
    synchronized void updateLatency(long requestCreateTime) {
    	SYNCHRONIZED(this) {
			llong latency = TimeUtils::currentElapsedTime() - requestCreateTime;
			totalLatency += latency;
			count++;
			if (latency < minLatency) {
				minLatency = latency;
			}
			if (latency > maxLatency) {
				maxLatency = latency;
			}
    	}}
    }
    synchronized void resetLatency(){
    	SYNCHRONIZED(this) {
			totalLatency = 0;
			count = 0;
			maxLatency = 0;
			minLatency = ELLong::MAX_VALUE;
    	}}
    }
    synchronized void resetMaxLatency(){
    	SYNCHRONIZED(this) {
    		maxLatency = getMinLatency();
    	}}
    }
    synchronized void incrementPacketsReceived() {
    	SYNCHRONIZED(this) {
    		packetsReceived++;
    	}}
    }
    synchronized void incrementPacketsSent() {
    	SYNCHRONIZED(this) {
    		packetsSent++;
    	}}
    }
    synchronized void resetRequestCounters(){
    	SYNCHRONIZED(this) {
    		packetsReceived = 0;
    		packetsSent = 0;
    	}}
    }
    synchronized void reset() {
    	SYNCHRONIZED(this) {
    		resetLatency();
    		resetRequestCounters();
    	}}
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ServerStats_HH_ */
