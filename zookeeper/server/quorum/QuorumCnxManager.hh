/*
 * QuorumCnxManager.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef QuorumCnxManager_HH_
#define QuorumCnxManager_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./QuorumServer.hh"
#include "./QuorumAuthLearner.hh"
#include "./QuorumAuthServer.hh"
#include "../ZooKeeperThread.hh"

namespace efc {
namespace ezk {

/**
 * This class implements a connection manager for leader election using TCP. It
 * maintains one connection for every pair of servers. The tricky part is to
 * guarantee that there is exactly one connection for every pair of servers that
 * are operating correctly and that can communicate over the network.
 * 
 * If two servers try to start a connection concurrently, then the connection
 * manager uses a very simple tie-breaking mechanism to decide which connection
 * to drop based on the IP addressed of the two parties. 
 * 
 * For every peer, the manager maintains a queue of messages to send. If the
 * connection to any particular peer drops, then the sender thread puts the
 * message back on the list. As this implementation currently uses a queue
 * implementation to maintain messages to send to another peer, we add the
 * message to the tail of the queue, thus changing the order of messages.
 * Although this is not a problem for the leader election, it could be a problem
 * when consolidating peer communication. This is to be verified, though.
 * 
 */

class QuorumCnxManager : public ESynchronizeable {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(QuorumCnxManager.class);

	/*
	 * Negative counter for observer server ids.
	 */

	EAtomicLLong observerCounter;// = new AtomicLong(-1);

	/*
	 * Connection time out value in milliseconds
	 */

	int cnxTO;// = 5000;

	sp<QuorumAuthServer> authServer;
	sp<QuorumAuthLearner> authLearner;

	friend class Listener;
	friend class SendWorker;
	friend class RecvWorker;

	/**
	 * Helper method to set socket options.
	 *
	 * @param sock
	 *            Reference to socket
	 */
	void setSockOpts(sp<ESocket> sock);

	/**
	 * Helper method to close a socket.
	 *
	 * @param sock
	 *            Reference to socket
	 */
	void closeSocket(sp<ESocket> sock);

    /**
     * Inserts an element in the specified queue. If the Queue is full, this
     * method removes an element from the head of the Queue and then inserts
     * the element at the tail. It can happen that the an element is removed
     * by another thread in {@link SendWorker#processMessage() processMessage}
     * method before this method attempts to remove an element from the queue.
     * This will cause {@link ArrayBlockingQueue#remove() remove} to throw an
     * exception, which is safe to ignore.
     *
     * Unlike {@link #addToRecvQueue(Message) addToRecvQueue} this method does
     * not need to be synchronized since there is only one thread that inserts
     * an element in the queue and another thread that reads from the queue.
     *
     * @param queue
     *          Reference to the Queue
     * @param buffer
     *          Reference to the buffer to be inserted in the queue
     */
    void addToSendQueue(sp<EArrayBlockingQueue<EIOByteBuffer> > queue,
          sp<EIOByteBuffer> buffer);

    /**
     * Returns true if queue is empty.
     * @param queue
     *          Reference to the queue
     * @return
     *      true if the specified queue is empty
     */
    boolean isSendQueueEmpty(sp<EArrayBlockingQueue<EIOByteBuffer> > queue) {
        return queue->isEmpty();
    }

    /**
     * Retrieves and removes buffer at the head of this queue,
     * waiting up to the specified wait time if necessary for an element to
     * become available.
     *
     * {@link ArrayBlockingQueue#poll(long, java.util.concurrent.TimeUnit)}
     */
    sp<EIOByteBuffer> pollSendQueue(sp<EArrayBlockingQueue<EIOByteBuffer> > queue,
          llong timeout, ETimeUnit* unit) THROWS(EInterruptedException) {
       return queue->poll(timeout, unit);
    }

public:
    class Message : public EObject {
    public:
		Message(sp<EIOByteBuffer> buffer, llong sid) {
			this->buffer = buffer;
			this->sid = sid;
		}

		sp<EIOByteBuffer> buffer;
		llong sid;
	};

    /**
	 * Thread to listen on some port
	 */
	class Listener : public ZooKeeperThread {
	private:
		QuorumCnxManager* mgr;
	public:
		EServerSocket* volatile ss;// = null;

		virtual ~Listener() {
			delete ss;
		}

		Listener(QuorumCnxManager* mgr) :
			ZooKeeperThread("ListenerThread"), mgr(mgr), ss(null) {
			// During startup of thread, thread name will be overridden to
			// specific election address
		}

		/**
		 * Sleeps on accept().
		 */
		virtual void run();

		/**
		 * Halts this listener thread.
		 */
		void halt(){
			try{
				LOG->debug("Trying to close listener: " + ss->toString());
				if(ss != null) {
					LOG->debug(EString("Closing listener: ") + mgr->mySid);
					ss->close();
				}
			} catch (EIOException& e){
				LOG->warn(__FILE__, __LINE__, "Exception when shutting down listener: ", e);
			}
		}
	};

	/**
	 * Thread to send messages. Instance waits on a queue, and send a message as
	 * soon as there is one available. If connection breaks, then opens a new
	 * one.
	 */
    class RecvWorker;
	class SendWorker : public ZooKeeperThread {
	private:
		QuorumCnxManager* mgr;
	public:
		llong sid;
		sp<ESocket> sock;
		wp<RecvWorker> recvWorker;
		volatile boolean running;// = true;
		EDataOutputStream* dout;

		virtual ~SendWorker() {
			delete dout;
		}

		/**
		 * An instance of this thread receives messages to send
		 * through a queue and sends them to the server sid.
		 *
		 * @param sock
		 *            Socket to remote peer
		 * @param sid
		 *            Server identifier of remote peer
		 */
		SendWorker(QuorumCnxManager* mgr, sp<ESocket> sock, llong sid) :
			ZooKeeperThread(EString("SendWorker:") + sid), mgr(mgr), running(true) {
			this->sid = sid;
			this->sock = sock;
			recvWorker = null;
			try {
				dout = new EDataOutputStream(sock->getOutputStream());
			} catch (EIOException& e) {
				LOG->error("Unable to access socket output stream", e);
				mgr->closeSocket(sock);
				running = false;
			}
			LOG->debug(EString("Address of remote peer: ") + sid);
		}

		synchronized
		void setRecv(sp<RecvWorker> recvWorker) {
			SYNCHRONIZED(this) {
				this->recvWorker = recvWorker;
			}}
		}

		/**
		 * Returns RecvWorker that pairs up with this SendWorker.
		 *
		 * @return RecvWorker
		 */
		synchronized
		sp<RecvWorker> getRecvWorker(){
			SYNCHRONIZED(this) {
				return recvWorker.lock();
			}}
		}

		synchronized
		boolean finish();

		synchronized
		void send(sp<EIOByteBuffer> b) THROWS(EIOException);

		virtual void run();
	};

	/**
	 * Thread to receive messages. Instance waits on a socket read. If the
	 * channel breaks, then removes itself from the pool of receivers.
	 */
	class RecvWorker : public ZooKeeperThread {
	private:
		QuorumCnxManager* mgr;
	public:
		llong sid;
		sp<ESocket> sock;
		volatile boolean running;// = true;
		sp<EDataInputStream> din;
		sp<SendWorker> sw;

		RecvWorker(QuorumCnxManager* mgr, sp<ESocket> sock, sp<EDataInputStream> din, llong sid, sp<SendWorker> sw) :
			ZooKeeperThread(EString("RecvWorker:") + sid), mgr(mgr), running(true) {
			this->sid = sid;
			this->sock = sock;
			this->sw = sw;
			this->din = din;
			try {
				// OK to wait until socket disconnects while reading.
				sock->setSoTimeout(0);
			} catch (EIOException& e) {
				LOG->error(__FILE__, __LINE__, EString("Error while accessing socket for ") + sid, e);
				mgr->closeSocket(sock);
				running = false;
			}
		}

		/**
		 * Shuts down this worker
		 *
		 * @return boolean  Value of variable running
		 */
		synchronized
		boolean finish();

		virtual void run();
	};

public:
	/*
	 * Maximum capacity of thread queues
	 */
	static const int RECV_CAPACITY = 100;
	// Initialized to 1 to prevent sending
	// stale notifications to peers
	static const int SEND_CAPACITY = 1;

	static const int PACKETMAXSIZE = 1024 * 512;

	/*
	 * Max buffer size to be read from the network.
	 */
	static const int maxBuffer = 2048;

	/*
	 * Local IP address
	 */
	llong mySid;
	int socketTimeout;
	EHashMap<llong, QuorumServer*>* view;
	boolean tcpKeepAlive;// = Boolean.getBoolean("zookeeper.tcpKeepAlive");
	boolean listenOnAllIPs;

	/*
	 * Mapping from Peer to Thread number
	 */
	EConcurrentHashMap<llong, SendWorker> senderWorkerMap;
	EConcurrentHashMap<llong, EArrayBlockingQueue<EIOByteBuffer> > queueSendMap;
	EConcurrentHashMap<llong, EIOByteBuffer> lastMessageSent;

    /*
     * Reception queue
     */
	EArrayBlockingQueue<Message> recvQueue;
    /*
     * Object to synchronize access to recvQueue
     */
    EReentrantLock recvQLock;// = new Object();

    /*
     * Shutdown flag
     */

    volatile boolean shutdown;// = false;

    /*
     * Listener thread
     */
    Listener listener;

public:
    QuorumCnxManager(llong mySid,
					EHashMap<llong,QuorumServer*>* view,
					sp<QuorumAuthServer> authServer,
					sp<QuorumAuthLearner> authLearner,
					int socketTimeout,
					boolean listenOnAllIPs,
					int quorumCnxnThreadsSize) :
						recvQueue(RECV_CAPACITY),
						cnxTO(5000),
                        observerCounter(-1),
                        shutdown(false),
                        listener(this) {
		const char* cnxToValue = ESystem::getProperty("zookeeper.cnxTimeout");
		if (cnxToValue){
			this->cnxTO = EInteger::parseInt(cnxToValue);
		}

		this->mySid = mySid;
		this->socketTimeout = socketTimeout;
		this->view = view;
		this->listenOnAllIPs = listenOnAllIPs;

		//@see: initializeAuth(mySid, authServer, authLearner, quorumCnxnThreadsSize/*, quorumSaslAuthEnabled*/);
		this->authServer = authServer;
		this->authLearner = authLearner;

		// Starts listener thread that waits for connection requests
		//@see: listener = new Listener();

		tcpKeepAlive = EBoolean::parseBoolean(ESystem::getProperty("zookeeper.tcpKeepAlive", "false"));
    }

    /**
     * If this server has initiated the connection, then it gives up on the
     * connection if it loses challenge. Otherwise, it keeps the connection.
     */
    void initiateConnection(sp<ESocket> sock, llong sid) {
        try {
            startConnection(sock, sid);
        } catch (EIOException& e) {
            LOG->error(__FILE__, __LINE__, EString::formatOf("Exception while connecting, id: %ld, addr: %s, closing learner connection",
                     	sid, sock->getRemoteSocketAddress()->toString().c_str()), e);
            closeSocket(sock);
            return;
        }
    }

    boolean startConnection(sp<ESocket> sock, llong sid) THROWS(EIOException);

    /**
     * If this server receives a connection request, then it gives up on the new
     * connection if it wins. Notice that it checks whether it has a connection
     * to this server already or not. If it does, then it sends the smallest
     * possible long value to lose the challenge.
     * 
     */
    void receiveConnection(sp<ESocket> sock) {
        sp<EDataInputStream> din = null;
        try {
            din = new EDataInputStream(
                    new EBufferedInputStream(sock->getInputStream()), true);

            handleConnection(sock, din);
        } catch (EIOException& e) {
            LOG->error(EString::formatOf("Exception handling connection, addr: %s, closing server connection",
                     sock->getRemoteSocketAddress()->toString().c_str()));
            closeSocket(sock);
        }
    }

    void handleConnection(sp<ESocket> sock, sp<EDataInputStream> din) THROWS(EIOException);

    /**
     * Processes invoke this message to queue a message to send. Currently, 
     * only leader election uses it.
     */
    void toSend(llong sid, sp<EIOByteBuffer> b);
    
    /**
     * Try to establish a connection to server with id sid.
     * 
     *  @param sid  server id
     */
    synchronized
    void connectOne(llong sid);
    
    /**
     * Try to establish a connection with each server if one
     * doesn't exist.
     */
    
    void connectAll(){
        auto iter = queueSendMap.keySet()->iterator();
        while (iter->hasNext()) {
        	llong sid = iter->next();
            connectOne(sid);
        }      
    }

    /**
     * Check if all queues are empty, indicating that all messages have been delivered.
     */
    boolean haveDelivered() {
    	auto iter = queueSendMap.values()->iterator();
    	while (iter->hasNext()) {
    		auto queue = iter->next();
            LOG->debug(EString("Queue size: ") + queue->size());
            if (queue->size() == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Flag that it is time to wrap up all activities and interrupt the listener.
     */
    void halt() {
        shutdown = true;
        LOG->debug("Halting listener");
        listener.halt();
        
        softHalt();
    }
   
    /**
     * A soft halt simply finishes workers.
     */
    void softHalt() {
    	auto iter = senderWorkerMap.values()->iterator();
		while (iter->hasNext()) {
			sp<SendWorker> sw = iter->next();
            LOG->debug("Halting sender: " + sw->toString());
            sw->finish();
        }
    }

    /**
     * Inserts an element in the {@link #recvQueue}. If the Queue is full, this
     * methods removes an element from the head of the Queue and then inserts
     * the element at the tail of the queue.
     *
     * This method is synchronized to achieve fairness between two threads that
     * are trying to insert an element in the queue. Each thread checks if the
     * queue is full, then removes the element at the head of the queue, and
     * then inserts an element at the tail. This three-step process is done to
     * prevent a thread from blocking while inserting an element in the queue.
     * If we do not synchronize the call to this method, then a thread can grab
     * a slot in the queue created by the second thread. This can cause the call
     * to insert by the second thread to fail.
     * Note that synchronizing this method does not block another thread
     * from polling the queue since that synchronization is provided by the
     * queue itself.
     *
     * @param msg
     *          Reference to the message to be inserted in the queue
     */
    void addToRecvQueue(sp<Message> msg);

    /**
     * Retrieves and removes a message at the head of this queue,
     * waiting up to the specified wait time if necessary for an element to
     * become available.
     *
     * {@link ArrayBlockingQueue#poll(long, java.util.concurrent.TimeUnit)}
     */
    sp<Message> pollRecvQueue(llong timeout, ETimeUnit* unit) THROWS(EInterruptedException);

    boolean connectedToPeer(llong peerSid) {
        return senderWorkerMap.get(peerSid) != null;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumCnxManager_HH_ */
