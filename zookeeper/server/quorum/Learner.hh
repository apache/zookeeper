/*
 * Learner.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef Learner_HH_
#define Learner_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./PacketType.hh"
#include "./QuorumServer.hh"
#include "./QuorumPacket.hh"
#include "./LearnerInfo.hh"
#include "../Request.hh"
#include "../ServerCnxn.hh"
#include "../ZooTrace.hh"
#include "../RequestProcessor.hh"
#include "../ZooKeeperThread.hh"
#include "../RequestProcessor.hh"
#include "../RequestProcessor.hh"
#include "../util/SerializeUtils.hh"
#include "../util/ZxidUtils.hh"
#include "../../txn/TxnHeader.hh"
#include "../../../jute/inc/ERecord.hh"
#include "../../../jute/inc/EBinaryInputArchive.hh"
#include "../../../jute/inc/EBinaryOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 * This class is the superclass of two of the three main actors in a ZK
 * ensemble: Followers and Observers. Both Followers and Observers share 
 * a good deal of code which is moved into Peer to avoid duplication. 
 */

class QuorumPeer;
class LearnerZooKeeperServer;

class Learner : public ESynchronizeable {
protected:
    static sp<ELogger> LOG;// = LoggerFactory.getLogger(Learner.class);

    static boolean nodelay;// = System.getProperty("follower.nodelay", "true").equals("true");

public:
    DECLARE_STATIC_INITZZ;

public:
    class PacketInFlight : public EString {
    public:
        sp<TxnHeader> hdr;
        sp<ERecord> rec;
    };
    QuorumPeer* self;
    sp<LearnerZooKeeperServer> zk;
    
    EBufferedInputStream* bufferedInput;
    EBufferedOutputStream* bufferedOutput;
    
    sp<ESocket> sock;
    
    /**
     * Socket getter
     * @return 
     */
    sp<ESocket> getSocket() {
        return sock;
    }
    
    sp<EInputArchive> leaderIs;
    sp<EOutputArchive> leaderOs;
    /** the protocol version of the leader */
    int leaderProtocolVersion;// = 0x01;
    
    EConcurrentHashMap<llong, ServerCnxn> pendingRevalidations;// = new ConcurrentHashMap<Long, ServerCnxn>();
    
public:
    virtual ~Learner();
    Learner();

    int getPendingRevalidationsCount() {
        return pendingRevalidations.size();
    }
    
    /**
     * validate a session for a client
     *
     * @param clientId
     *                the client to be revalidated
     * @param timeout
     *                the timeout for which the session is valid
     * @return
     * @throws IOException
     */
    void validateSession(sp<ServerCnxn> cnxn, llong clientId, int timeout)
            THROWS(EIOException);
    
    /**
     * write a packet to the leader
     *
     * @param pp
     *                the proposal packet to be sent to the leader
     * @throws IOException
     */
    void writePacket(sp<QuorumPacket> pp, boolean flush) THROWS(EIOException);

    /**
     * read a packet from the leader
     *
     * @param pp
     *                the packet to be instantiated
     * @throws IOException
     */
    void readPacket(sp<QuorumPacket> pp) THROWS(EIOException);
    
    /**
     * send a request packet to the leader
     *
     * @param request
     *                the request from the client
     * @throws IOException
     */
    void request(sp<Request> request) THROWS(EIOException);
    
    /**
     * Returns the address of the node we think is the leader.
     */
    QuorumServer* findLeader();
    
    /**
     * Establish a connection with the Leader found by findLeader. Retries
     * 5 times before giving up. 
     * @param addr - the address of the Leader to connect to.
     * @throws IOException <li>if the socket connection fails on the 5th attempt</li>
     * <li>if there is an authentication failure while connecting to leader</li>
     * @throws ConnectException
     * @throws InterruptedException
     */
    void connectToLeader(sp<EInetSocketAddress> addr, EString hostname)
            THROWS3(EIOException, EConnectException, EInterruptedException);
    
    /**
     * Once connected to the leader, perform the handshake protocol to
     * establish a following / observing connection. 
     * @param pktType
     * @return the zxid the Leader sends for synchronization purposes.
     * @throws IOException
     */
    llong registerWithLeader(int pktType) THROWS(EIOException);
    
    /**
     * Finally, synchronize our history with the Leader. 
     * @param newLeaderZxid
     * @throws IOException
     * @throws InterruptedException
     */
    void syncWithLeader(llong newLeaderZxid) THROWS2(EIOException, EInterruptedException);
    
    void revalidate(sp<QuorumPacket> qp) THROWS(EIOException);
        
    void ping(sp<QuorumPacket> qp) THROWS(EIOException);
    
    /**
     * Shutdown the Peer
     */
    virtual void shutdown();

    boolean isRunning();
};

} /* namespace ezk */
} /* namespace efc */
#endif /* Learner_HH_ */
