/*
 * ServerCnxnFactory.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef ServerCnxnFactory_HH_
#define ServerCnxnFactory_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./ServerCnxn.hh"

namespace efc {
namespace ezk {

class ZooKeeperServer;

abstract class ServerCnxnFactory : public ESynchronizeable {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(ServerCnxnFactory.class);

public:
	// sessionMap is used to speed up closeSession()
	EConcurrentHashMap<llong, ServerCnxn> sessionMap;// = new ConcurrentHashMap<Long, ServerCnxn>();

	EHashSet<sp<ServerCnxn> > cnxns;// = new HashSet<ServerCnxn>();
	EReentrantLock cnxnsLock;

	sp<ZooKeeperServer> zkServer;

	/**
	 * The buffer will cause the connection to be close when we do a send.
	 */
	static sp<EIOByteBuffer> closeConn;// = ByteBuffer.allocate(0);

public:
    interface PacketProcessor : virtual public EObject {
    	virtual ~PacketProcessor() {}
    	virtual void processPacket(EIOByteBuffer* packet, sp<ServerCnxn> src) = 0;
    };
    
    virtual int getLocalPort() = 0;

    virtual void closeSession(llong sessionId) = 0;

    virtual void configure(sp<ServerCnxnFactory> self, sp<EInetSocketAddress> addr,
                                   int maxClientCnxns) THROWS(EIOException) = 0;

    /** Maximum number of connections allowed from particular host (ip) */
    virtual int getMaxClientCnxnsPerHost() = 0;

    /** Maximum number of connections allowed from particular host (ip) */
    virtual void setMaxClientCnxnsPerHost(int max) = 0;

    virtual void startup(sp<ZooKeeperServer> zkServer) THROWS2(EIOException, EInterruptedException) = 0;

    virtual void join() THROWS(EInterruptedException) = 0;

    virtual void shutdown() = 0;

    virtual void start() = 0;

    virtual void closeAll() = 0;

    virtual EInetSocketAddress* getLocalAddress() = 0;

public:
    ServerCnxnFactory();

    sp<ZooKeeperServer> getZooKeeperServer();

    void setZooKeeperServer(sp<ZooKeeperServer> zk);

    int getNumAliveConnections() {
        SYNCBLOCK(&cnxnsLock) {
            return cnxns.size();
        }}
    }

	void addSession(llong sessionId, sp<ServerCnxn> cnxn) {
		sessionMap.put(sessionId, cnxn);
	}

    static ServerCnxnFactory* createFactory() THROWS(EIOException);
};

} /* namespace ezk */
} /* namespace efc */
#endif /* ServerCnxnFactory_HH_ */
