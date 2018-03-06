/*
 * NIOServerCnxnFactory.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef NIOServerCnxnFactory_HH_
#define NIOServerCnxnFactory_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./NIOServerCnxn.hh"
#include "./ServerCnxnFactory.hh"
#include "./ZooKeeperThread.hh"
#include "./ZooKeeperServer.hh"

namespace efc {
namespace ezk {


class NIOServerCnxnFactory : public ServerCnxnFactory, virtual public ERunnable {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(NIOServerCnxnFactory.class);

    sp<ESelector> selector;// = Selector.open();

    sp<EServerSocketChannel> ss;

    int maxClientCnxns;// = 60;

    sp<EThread> thread;

    int getClientCnxnCount(EInetAddress* cl) {
		// The ipMap lock covers both the map, and its contents
		// (that is, the cnxn sets shouldn't be modified outside of
		// this lock)
		SYNCBLOCK(&ipMapLock) {
			auto s = ipMap.get(cl->getAddress());
			if (s == null) return 0;
			return s->size();
		}}
	}

	void closeSessionWithoutWakeup(llong sessionId) {
        sp<NIOServerCnxn> cnxn = dynamic_pointer_cast<NIOServerCnxn>(sessionMap.remove(sessionId));
        if (cnxn != null) {
            try {
                cnxn->close();
            } catch (EException& e) {
                LOG->warn("exception during session close", e);
            }
        }
    }

public:
    /**
     * We use this buffer to do efficient socket I/O. Since there is a single
     * sender thread per NIOServerCnxn instance, we can use a member variable to
     * only allocate it once.
    */
    sp<EIOByteBuffer> directBuffer;// = ByteBuffer.allocateDirect(64 * 1024);

    EHashMap<int, sp<EHashSet<sp<NIOServerCnxn> > > > ipMap;// = new HashMap<InetAddress, Set<NIOServerCnxn>>( );
    EReentrantLock ipMapLock;

    /**
     * Construct a new server connection factory which will accept an unlimited number
     * of concurrent connections from each client (up to the file descriptor
     * limits of the operating system). startup(zks) must be called subsequently.
     * @throws IOException
     */
    NIOServerCnxnFactory() THROWS(EIOException) {
    	selector = ESelector::open();
    	maxClientCnxns = 60;
    	directBuffer = EIOByteBuffer::allocate(64 * 1024);
    }

    virtual void configure(sp<ServerCnxnFactory> self, sp<EInetSocketAddress> addr, int maxcc) THROWS(EIOException) {
		thread = new ZooKeeperThread(dynamic_pointer_cast<ERunnable>(self),
				"NIOServerCxn.Factory:" + addr->toString());
        maxClientCnxns = maxcc;
        this->ss = EServerSocketChannel::open();
        ss->socket()->setReuseAddress(true);
        LOG->info("binding to port " + addr->toString());
        ss->socket()->bind(addr.get());
        ss->configureBlocking(false);
        ss->register_(selector.get(), ESelectionKey::OP_ACCEPT);
    }

    /** {@inheritDoc} */
    int getMaxClientCnxnsPerHost() {
        return maxClientCnxns;
    }

    /** {@inheritDoc} */
    void setMaxClientCnxnsPerHost(int max) {
        maxClientCnxns = max;
    }

    virtual void start() {
        // ensure thread is started once and only once
        if (thread->getState() == EThread::NEW) {
            EThread::setDaemon(thread, true); //!!!
            thread->start();
        }
    }

    virtual void startup(sp<ZooKeeperServer> zks) THROWS2(EIOException, EInterruptedException) {
        start();
        setZooKeeperServer(zks);
        zks->startdata();
        zks->startup();
    }

    virtual EInetSocketAddress* getLocalAddress(){
        return ss->socket()->getLocalSocketAddress();
    }

    virtual int getLocalPort(){
        return ss->socket()->getLocalPort();
    }

    void addCnxn(sp<NIOServerCnxn> cnxn);

    void removeCnxn(NIOServerCnxn* cnxn);

    sp<NIOServerCnxn> createConnection(sp<ESocketChannel> sock,
            sp<ESelectionKey> sk) THROWS(EIOException);

    virtual void run();

    /**
     * clear all the connections in the selector
     *
     */
    virtual void closeAll();

    virtual void shutdown();

    synchronized
    virtual void closeSession(llong sessionId);

    virtual void join() THROWS(EInterruptedException) {
        thread->join();
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* NIOServerCnxnFactory_HH_ */
