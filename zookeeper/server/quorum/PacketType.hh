/*
 * PacketType.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef PacketType_HH_
#define PacketType_HH_

#include "Efc.hh"

namespace efc {
namespace ezk {

//@see: Leader.java

class PacketType {
public:
    /**
     * This message is for follower to expect diff
     */
	static const int DIFF = 13;
    
    /**
     * This is for follower to truncate its logs 
     */
	static const int TRUNC = 14;
    
    /**
     * This is for follower to download the snapshots
     */
	static const int SNAP = 15;
    
    /**
     * This tells the leader that the connecting peer is actually an observer
     */
	static const int OBSERVERINFO = 16;
    
    /**
     * This message type is sent by the leader to indicate it's zxid and if
     * needed, its database.
     */
	static const int NEWLEADER = 10;

    /**
     * This message type is sent by a follower to pass the last zxid. This is here
     * for backward compatibility purposes.
     */
	static const int FOLLOWERINFO = 11;

    /**
     * This message type is sent by the leader to indicate that the follower is
     * now uptodate andt can start responding to clients.
     */
	static const int UPTODATE = 12;

    /**
     * This message is the first that a follower receives from the leader.
     * It has the protocol version and the epoch of the leader.
     */
    static const int LEADERINFO = 17;

    /**
     * This message is used by the follow to ack a proposed epoch.
     */
    static const int ACKEPOCH = 18;
    
    /**
     * This message type is sent to a leader to request and mutation operation.
     * The payload will consist of a request header followed by a request.
     */
    static const int REQUEST = 1;

    /**
     * This message type is sent by a leader to propose a mutation.
     */
    static const int PROPOSAL = 2;

    /**
     * This message type is sent by a follower after it has synced a proposal.
     */
    static const int ACK = 3;

    /**
     * This message type is sent by a leader to commit a proposal and cause
     * followers to start serving the corresponding data.
     */
    static const int COMMIT = 4;

    /**
     * This message type is enchanged between follower and leader (initiated by
     * follower) to determine liveliness.
     */
    static const int PING = 5;

    /**
     * This message type is to validate a session that should be active.
     */
    static const int REVALIDATE = 6;

    /**
     * This message is a reply to a synchronize command flushing the pipe
     * between the leader and the follower.
     */
    static const int SYNC = 7;
        
    /**
     * This message type informs observers of a committed proposal.
     */
    static const int INFORM = 8;


    /**
     * Get string representation of a given packet type
     * @param packetType
     * @return string representing the packet type
     */
    static EString getPacketType(int packetType) {
        switch (packetType) {
        case DIFF:
            return "DIFF";
        case TRUNC:
            return "TRUNC";
        case SNAP:
            return "SNAP";
        case OBSERVERINFO:
            return "OBSERVERINFO";
        case NEWLEADER:
            return "NEWLEADER";
        case FOLLOWERINFO:
            return "FOLLOWERINFO";
        case UPTODATE:
            return "UPTODATE";
        case LEADERINFO:
            return "LEADERINFO";
        case ACKEPOCH:
            return "ACKEPOCH";
        case REQUEST:
            return "REQUEST";
        case PROPOSAL:
            return "PROPOSAL";
        case ACK:
            return "ACK";
        case COMMIT:
            return "COMMIT";
        case PING:
            return "PING";
        case REVALIDATE:
            return "REVALIDATE";
        case SYNC:
            return "SYNC";
        case INFORM:
            return "INFORM";
        default:
            return "UNKNOWN";
        }
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* PacketType_HH_ */
