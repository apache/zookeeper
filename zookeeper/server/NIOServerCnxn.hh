/*
 * NIOServerCnxn.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef NIOServerCnxn_HH_
#define NIOServerCnxn_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./quorum/Leader.hh"
#include "../Environment.hh"
#include "../Version.hh"
#include "../WatchedEvent.hh"
#include "../data/Id.hh"
#include "../proto/ReplyHeader.hh"
#include "../proto/RequestHeader.hh"

namespace efc {
namespace ezk {

/**
 * This class handles communication with clients using NIO. There is one per
 * client, but only one thread doing the communication.
 */

class NIOServerCnxnFactory;

class NIOServerCnxn : public ServerCnxn, public enable_shared_from_this<NIOServerCnxn> {
//private:
public:
    static sp<ELogger> LOG;// = LoggerFactory.getLogger(NIOServerCnxn.class);

    /** Read the request payload (everything following the length prefix) */
    void readPayload() THROWS2(EIOException, EInterruptedException);

	void readRequest() THROWS(EIOException);

	void readConnectRequest() THROWS2(EIOException, EInterruptedException);

    /**
     * clean up the socket related to a command and also make sure we flush the
     * data before we do that
     * 
     * @param pwriter
     *            the pwriter for a command socket
     */
    void cleanupWriterSocket(EPrintStream* pwriter);

    /** Return if four letter word found and responded to, otw false **/
    boolean checkFourLetterWord(sp<ESelectionKey> k, int len) THROWS(EIOException);

    /** Reads the first 4 bytes of lenBuffer, which could be true length or
     *  four letter word.
     *
     * @param k selection key
     * @return true if length read, otw false (wasn't really the length)
     * @throws IOException if buffer size exceeds maxBuffer size
     */
    boolean readLength(sp<ESelectionKey> k) THROWS(EIOException);

	/**
	 * Close resources associated with the sock of this cnxn.
	 */
    void closeSock();

public:
    NIOServerCnxnFactory* factory;

    sp<ESocketChannel> sock;

    sp<ESelectionKey> sk;

    boolean initialized;

    sp<EIOByteBuffer> lenBuffer;// = ByteBuffer.allocate(4);

    sp<EIOByteBuffer> incomingBuffer;// = lenBuffer;

    ELinkedBlockingQueue<EIOByteBuffer> outgoingBuffers;// = new LinkedBlockingQueue<ByteBuffer>();

    int sessionTimeout;

    sp<ZooKeeperServer> zkServer;

    /**
     * The number of requests that have been submitted but not yet responded to.
     */
    int outstandingRequests;

    /**
     * This is the id that uniquely identifies the session of a client. Once
     * this session is no longer active, the ephemeral nodes will go away.
     */
    llong sessionId;

    int outstandingLimit;// = 1;

    virtual ~NIOServerCnxn();

    NIOServerCnxn(sp<ZooKeeperServer> zk, sp<ESocketChannel> sock,
            sp<ESelectionKey> sk, NIOServerCnxnFactory* factory) THROWS(EIOException);

    /* Send close connection packet to the client, doIO will eventually
     * close the underlying machinery (like socket, selectorkey, etc...)
     */
    void sendCloseSession();

    /**
     * send buffer without using the asynchronous
     * calls to selector and then close the socket
     * @param bb
     */
    void sendBufferSync(sp<EIOByteBuffer> bb);

    void sendBuffer(sp<EIOByteBuffer> bb);

    /**
     * This method implements the internals of sendBuffer. We
     * have separated it from send buffer to be able to catch
     * exceptions when testing.
     *
     * @param bb Buffer to send.
     */
    void internalSendBuffer(sp<EIOByteBuffer> bb);

    /**
     * Only used in order to allow testing
     */
    boolean isSocketOpen() {
        return sock->isOpen();
    }

    virtual EInetAddress* getSocketAddress() {
        if (sock == null) {
            return null;
        }

        return sock->socket()->getInetAddress();
    }

    /**
     * Handles read/write IO on connection.
     */
    void doIO(sp<ESelectionKey> k) THROWS(EInterruptedException);

    void incrOutstandingRequests(RequestHeader* h);

    void disableRecv() {
        sk->interestOps(sk->interestOps() & (~ESelectionKey::OP_READ));
    }

    void enableRecv();

    llong getOutstandingRequests();

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionTimeout()
     */
    int getSessionTimeout() {
        return sessionTimeout;
    }

    virtual EString toString() {
        return "NIOServerCnxn object with sock = " + sock->toString() + " and sk = " + sk->toString();
    }

    /*
     * Close the cnxn and remove it from the factory cnxns list.
     * 
     * This function returns immediately if the cnxn is not on the cnxns list.
     */
    virtual void close();

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#sendResponse(org.apache.zookeeper.proto.ReplyHeader,
     *      org.apache.jute.Record, java.lang.String)
     */
    synchronized
    virtual void sendResponse(sp<ReplyHeader> h, sp<ERecord> r, EString tag) THROWS(EIOException);

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#process(org.apache.zookeeper.proto.WatcherEvent)
     */
    synchronized
    virtual void process(sp<WatchedEvent> event);

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionId()
     */
    virtual llong getSessionId() {
        return sessionId;
    }

    virtual void setSessionId(llong sessionId);

    virtual void setSessionTimeout(int sessionTimeout) {
        this->sessionTimeout = sessionTimeout;
    }

    virtual int getInterestOps() {
        return sk->isValid() ? sk->interestOps() : 0;
    }

    virtual sp<EInetSocketAddress> getRemoteSocketAddress() {
        if (sock->isOpen() == false) {
            return null;
        }
        return new EInetSocketAddress(*(sock->socket()->getRemoteSocketAddress()));
    }

    virtual ServerStats* serverStats() {
        if (!isZKServerRunning()) {
            return null;
        }
        return zkServer->serverStats();
    }

    /**
     * @return true if the server is running, false otherwise.
     */
    boolean isZKServerRunning();
};

} /* namespace ezk */
} /* namespace efc */
#endif /* NIOServerCnxn_HH_ */
