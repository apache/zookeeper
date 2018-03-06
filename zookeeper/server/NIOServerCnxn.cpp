/*
 * NIOServerCnxn.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./NIOServerCnxn.hh"
#include "NIOServerCnxnFactory.hh"
#include "./quorum/LeaderZooKeeperServer.hh"
#include "./quorum/ReadOnlyZooKeeperServer.hh"

namespace efc {
namespace ezk {

namespace prv {
	#define ZK_NOT_SERVING "This ZooKeeper instance is not currently serving requests"

    /**
     * This class wraps the sendBuffer method of NIOServerCnxn. It is
     * responsible for chunking up the response to a client. Rather
     * than cons'ing up a response fully in memory, which may be large
     * for some commands, this class chunks up the result.
     */
    class SendBufferWriter : public EOutputStream {
    private:
    	sp<NIOServerCnxn> owner;
    	EString sb;
    	boolean closed;

        /**
         * Check if we are ready to send another chunk.
         * @param force force sending, even if not a full chunk
         */
        void checkFlush(boolean force) {
            if ((force && sb.length() > 0) || sb.length() > 2048) {
            	owner->sendBufferSync(EIOByteBuffer::wrap((void*)sb.c_str(), sb.length()));
                // clear our internal buffer
                sb.setLength(0);
            }
        }

    public:
        SendBufferWriter(sp<NIOServerCnxn> owner) : owner(owner), closed(false) {
        }

        virtual void close() THROWS(EIOException) {
            if (closed) return;
            checkFlush(true);
            closed = true; // clear out the ref to ensure no reuse
        }

        virtual void flush() THROWS(EIOException) {
            checkFlush(true);
        }

        virtual void write(const void *b, int len) THROWS(EIOException)  {
            sb.append((char*)b, len);
            checkFlush(false);
        }
    };

    /**
     * Set of threads for commmand ports. All the 4
     * letter commands are run via a thread. Each class
     * maps to a corresponding 4 letter command. CommandThread
     * is the abstract class from which all the others inherit.
     */
    abstract class CommandThread : public EThread {
    public:
    	sp<NIOServerCnxn> owner;
    	sp<EPrintStream> pw;

        CommandThread(sp<NIOServerCnxn> owner, sp<EPrintStream> pw) {
            this->owner = owner;
            this->pw = pw;
        }

        virtual void run() {
        	ON_FINALLY_NOTHROW(
        		owner->cleanupWriterSocket(pw.get());
        	) {
				try {
					commandRun();
				} catch (EIOException& ie) {
					NIOServerCnxn::LOG->error("Error in running command ", ie);
				}
        	}}
        }

        virtual void commandRun() THROWS(EIOException) = 0;
    };

	class RuokCommand : public CommandThread {
    public:
        RuokCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw) : CommandThread(owner, pw) {
        }

        virtual void commandRun() {
            pw->print("imok");
        }
    };

	class TraceMaskCommand : public CommandThread {
	public:
		TraceMaskCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw) : CommandThread(owner, pw) {
		}

		virtual void commandRun() {
			llong traceMask = ZooTrace::getTextTraceLevel();
			pw->print(traceMask);
		}
	};

	class SetTraceMaskCommand : public CommandThread {
	public:
		llong trace;

		SetTraceMaskCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw, llong trace) : CommandThread(owner, pw) {
			this->trace = trace;
		}

		virtual void commandRun() {
			pw->print(trace);
		}
	};

	class EnvCommand : public CommandThread {
	public:
		EnvCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw) : CommandThread(owner, pw) {
		}

		virtual void commandRun() {
			auto env = Environment::list();

			pw->println("Environment:");
			auto iter = env->iterator();
			while (iter->hasNext()) {
				auto e = iter->next();
				pw->print(e->getKey().c_str());
				pw->print("=");
				pw->println(e->getValue().c_str());
			}
		}
	};

	class ConfCommand : public CommandThread {
	public:
		ConfCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw) : CommandThread(owner, pw) {
		}

		virtual void commandRun() {
			if (!owner->isZKServerRunning()) {
				pw->println(ZK_NOT_SERVING);
			} else {
				owner->zkServer->dumpConf(pw.get());
			}
		}
	};

	class StatResetCommand : public CommandThread {
	public:
		StatResetCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw) : CommandThread(owner, pw) {
		}

		virtual void commandRun() {
			if (!owner->isZKServerRunning()) {
				pw->println(ZK_NOT_SERVING);
			}
			else {
				owner->zkServer->serverStats()->reset();
				pw->println("Server stats reset.");
			}
		}
	};

	class CnxnStatResetCommand : public CommandThread {
	public:
		CnxnStatResetCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw) : CommandThread(owner, pw) {
		}

		virtual void commandRun() {
			if (!owner->isZKServerRunning()) {
				pw->println(ZK_NOT_SERVING);
			}
			else {
				SYNCBLOCK(&owner->factory->cnxnsLock){
					auto iter = owner->factory->cnxns.iterator();
					while (iter->hasNext()) {
						auto c = iter->next();
						c->resetStats();
					}
				}}
				pw->println("Connection stats reset.");
			}
		}
	};

	class DumpCommand : public CommandThread {
	public:
		DumpCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw) : CommandThread(owner, pw) {
		}

		virtual void commandRun() {
			if (!owner->isZKServerRunning()) {
				pw->println(ZK_NOT_SERVING);
			}
			else {
				pw->println("SessionTracker dump:");
				owner->zkServer->sessionTracker->dumpSessions(pw.get());
				pw->println("ephemeral nodes dump:");
				owner->zkServer->dumpEphemerals(pw.get());
			}
		}
	};

	class StatCommand : public CommandThread {
	public:
		int len;

		StatCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw, int len) : CommandThread(owner, pw) {
			this->len = len;
		}

		virtual void commandRun() {
			if (!owner->isZKServerRunning()) {
				pw->println(ZK_NOT_SERVING);
			}
			else {
				pw->print("Zookeeper version: ");
				pw->println(Version::getFullVersion().c_str());
				sp<ReadOnlyZooKeeperServer> o = dynamic_pointer_cast<ReadOnlyZooKeeperServer>(owner->zkServer);
				if (o != null) {
					pw->println("READ-ONLY mode; serving only read-only clients");
				}
				if (len == ServerCnxn::statCmd) {
					NIOServerCnxn::LOG->info("Stat command output");
					pw->println("Clients:");
					// clone should be faster than iteration
					// ie give up the cnxns lock faster
					/* @see:
					HashSet<NIOServerCnxn> cnxnset;
					synchronized(factory->cnxns){
						cnxnset = (HashSet<NIOServerCnxn>)factory
						.cnxns.clone();
					}
					*/
					EHashSet<sp<ServerCnxn> > cnxnset;
					SYNCBLOCK(&owner->factory->cnxnsLock) {
						cnxnset = owner->factory->cnxns;
					}}
					auto iter = cnxnset.iterator();
					while (iter->hasNext()) {
						auto c = iter->next();
						c->dumpConnectionInfo(pw.get(), true);
						pw->println();
					}
					pw->println();
				}
				pw->print(owner->zkServer->serverStats()->toString().c_str());
				pw->print("Node count: ");
				pw->println(owner->zkServer->getZKDatabase()->getNodeCount());
			}
		}
	};

	class ConsCommand : public CommandThread {
	public:
		ConsCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw) : CommandThread(owner, pw) {
		}

		virtual void commandRun() {
			if (!owner->isZKServerRunning()) {
				pw->println(ZK_NOT_SERVING);
			}
			else {
				// clone should be faster than iteration
				// ie give up the cnxns lock faster
				/* @see:
				HashSet<NIOServerCnxn> cnxns;
				synchronized (factory->cnxns) {
					cnxns = (HashSet<NIOServerCnxn>) factory->cnxns.clone();
				}
				*/
				EHashSet<sp<ServerCnxn> > cnxnset;
				SYNCBLOCK(&owner->factory->cnxnsLock) {
					cnxnset = owner->factory->cnxns;
				}}
				auto iter = cnxnset.iterator();
				while (iter->hasNext()) {
					auto c = iter->next();
					c->dumpConnectionInfo(pw.get(), false);
					pw->println();
				}
				pw->println();
			}
		}
	};

	class WatchCommand : public CommandThread {
	public:
		int len;

		WatchCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw, int len) : CommandThread(owner, pw) {
			this->len = len;
		}

		virtual void commandRun() {
			if (!owner->isZKServerRunning()) {
				pw->println(ZK_NOT_SERVING);
			}
			else {
				sp<DataTree> dt = owner->zkServer->getZKDatabase()->getDataTree();
				if (len == ServerCnxn::wchsCmd) {
					dt->dumpWatchesSummary(pw.get());
				} else if (len == ServerCnxn::wchpCmd) {
					dt->dumpWatches(pw.get(), true);
				} else {
					dt->dumpWatches(pw.get(), false);
				}
				pw->println();
			}
		}
	};

	class MonitorCommand : public CommandThread {
	private:
		void print(EString key, llong number) {
			print(key, EString(number));
		}

		void print(EString key, EString value) {
			pw->print("zk_");
			pw->print(key.c_str());
			pw->print("\t");
			pw->println(value.c_str());
		}
	public:
		MonitorCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw) : CommandThread(owner, pw) {
		}

		virtual void commandRun() {
			if (!owner->isZKServerRunning()) {
				pw->println(ZK_NOT_SERVING);
			} else {
				sp<ZKDatabase> zkdb = owner->zkServer->getZKDatabase();
				ServerStats* stats = owner->zkServer->serverStats();

				print("version", Version::getFullVersion());

				print("avg_latency", stats->getAvgLatency());
				print("max_latency", stats->getMaxLatency());
				print("min_latency", stats->getMinLatency());

				print("packets_received", stats->getPacketsReceived());
				print("packets_sent", stats->getPacketsSent());
				print("num_alive_connections", stats->getNumAliveClientConnections());

				print("outstanding_requests", stats->getOutstandingRequests());

				print("server_state", stats->getServerState());
				print("znode_count", zkdb->getNodeCount());

				print("watch_count", zkdb->getDataTree()->getWatchCount());
				print("ephemerals_count", zkdb->getDataTree()->getEphemeralsCount());
				print("approximate_data_size", zkdb->getDataTree()->approximateDataSize());

				if(stats->getServerState().equals("leader")) {
					sp<Leader> leader = dynamic_pointer_cast<LeaderZooKeeperServer>(owner->zkServer)->getLeader();

					print("followers", leader->getLearners()->size());
					print("synced_followers", leader->getForwardingFollowers()->size());
					print("pending_syncs", leader->getNumPendingSyncs());
				}
			}
		}
	};

	class IsroCommand : public CommandThread {
	public:
		IsroCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw) : CommandThread(owner, pw) {
		}

		virtual void commandRun() {
			if (!owner->isZKServerRunning()) {
				pw->print("null");
			} else {
				sp<ReadOnlyZooKeeperServer> o = dynamic_pointer_cast<ReadOnlyZooKeeperServer>(owner->zkServer);
				if (o != null) {
					pw->print("ro");
				} else {
					pw->print("rw");
				}
			}
		}
	};

    class NopCommand : public CommandThread {
    public:
        EString msg;

        NopCommand(sp<NIOServerCnxn> owner, sp<EPrintStream> pw, EString msg) : CommandThread(owner, pw) {
            this->msg = msg;
        }

        virtual void commandRun() {
            pw->println(msg.c_str());
        }
    };
} /* namespace prv */

//=============================================================================

sp<ELogger> NIOServerCnxn::LOG = ELoggerManager::getLogger("NIOServerCnxn");

NIOServerCnxn::~NIOServerCnxn() {
	//
}

NIOServerCnxn::NIOServerCnxn(sp<ZooKeeperServer> zk, sp<ESocketChannel> sock,
            sp<ESelectionKey> sk, NIOServerCnxnFactory* factory) {
	initialized = false;
	sessionTimeout = 0;
	outstandingRequests = 0;
	sessionId = 0;
	outstandingLimit = 1;
	lenBuffer = EIOByteBuffer::allocate(4);
	incomingBuffer = lenBuffer;

	this->zkServer = zk;
	this->sock = sock;
	this->sk = sk;
	this->factory = factory;
	if (zk != null) {
		outstandingLimit = zk->getGlobalOutstandingLimit();
	}
	sock->socket()->setTcpNoDelay(true);
	/* set socket linger to false, so that socket close does not
	 * block */
	sock->socket()->setSoLinger(false, -1);
	EInetAddress* addr = sock->socket()
			->getRemoteSocketAddress()->getAddress();
	authInfo->add(new Id("ip", addr->getHostAddress()));
	sk->interestOps(ESelectionKey::OP_READ);
}

void NIOServerCnxn::readPayload() THROWS2(EIOException, EInterruptedException) {
    if (incomingBuffer->remaining() != 0) { // have we read length bytes?
        int rc = sock->read(incomingBuffer.get()); // sock is non-blocking, so ok
        if (rc < 0) {
            throw EndOfStreamException(__FILE__, __LINE__,
                    "Unable to read additional data from client sessionid 0x"
                    + ELLong::toHexString(sessionId)
                    + ", likely client has closed socket");
        }
    }

    if (incomingBuffer->remaining() == 0) { // have we read length bytes?
        packetReceived();
        incomingBuffer->flip();
        if (!initialized) {
            readConnectRequest();
        } else {
            readRequest();
        }
        lenBuffer->clear();
        incomingBuffer = lenBuffer;
    }
}

void NIOServerCnxn::readRequest() THROWS(EIOException) {
	zkServer->processPacket(shared_from_this(), incomingBuffer);
}

void NIOServerCnxn::readConnectRequest() THROWS2(EIOException, EInterruptedException) {
	if (!isZKServerRunning()) {
		throw EIOException(__FILE__, __LINE__, "ZooKeeperServer not running");
	}
	zkServer->processConnectRequest(shared_from_this(), incomingBuffer);
	initialized = true;
}

void NIOServerCnxn::cleanupWriterSocket(EPrintStream* pwriter) {
	ON_FINALLY_NOTHROW(
		try {
			close();
		} catch (EException& e) {
			LOG->error("Error closing a command socket ", e);
		}
	) {
		try {
			if (pwriter != null) {
				pwriter->flush();
				pwriter->close();
			}
		} catch (EException& e) {
			LOG->info("Error closing PrintWriter ", e);
		}
	}}
}

void NIOServerCnxn::closeSock() {
	if (sock->isOpen() == false) {
		return;
	}

	LOG->info("Closed socket connection for client "
			+ sock->socket()->getRemoteSocketAddress()->toString()
			+ (sessionId != 0 ?
					" which had sessionid 0x" + ELLong::toHexString(sessionId) :
					" (no session established for client)"));
	try {
		/*
		 * The following sequence of code is stupid! You would think that
		 * only sock.close() is needed, but alas, it doesn't work that way.
		 * If you just do sock.close() there are cases where the socket
		 * doesn't actually close...
		 */
		sock->socket()->shutdownOutput();
	} catch (EIOException& e) {
		// This is a relatively common exception that we can't avoid
		if (LOG->isDebugEnabled()) {
			LOG->debug("ignoring exception during output shutdown", e);
		}
	}
	try {
		sock->socket()->shutdownInput();
	} catch (EIOException& e) {
		// This is a relatively common exception that we can't avoid
		if (LOG->isDebugEnabled()) {
			LOG->debug("ignoring exception during input shutdown", e);
		}
	}
	try {
		sock->socket()->close();
	} catch (EIOException& e) {
		if (LOG->isDebugEnabled()) {
			LOG->debug("ignoring exception during socket close", e);
		}
	}
	try {
		sock->close();
		// XXX The next line doesn't seem to be needed, but some posts
		// to forums suggest that it is needed. Keep in mind if errors in
		// this section arise.
		// factory.selector.wakeup();
	} catch (EIOException& e) {
		if (LOG->isDebugEnabled()) {
			LOG->debug("ignoring exception during socketchannel close", e);
		}
	}
}

void NIOServerCnxn::sendCloseSession() {
	sendBuffer(ServerCnxnFactory::closeConn);
}

void NIOServerCnxn::sendBufferSync(sp<EIOByteBuffer> bb) {
   try {
	   /* configure socket to be blocking
		* so that we dont have to do write in
		* a tight while loop
		*/
	   sock->configureBlocking(true);
	   if (bb != ServerCnxnFactory::closeConn) {
		   if (sock->isOpen()) {
			   sock->write(bb.get());
		   }
		   packetSent();
	   }
   } catch (EIOException& ie) {
	   LOG->error("Error sending data synchronously ", ie);
   }
}

void NIOServerCnxn::sendBuffer(sp<EIOByteBuffer> bb) {
	try {
		internalSendBuffer(bb);
	} catch(EException& e) {
		LOG->error("Unexpected Exception: ", e);
	}
}

void NIOServerCnxn::doIO(sp<ESelectionKey> k) {
	try {
		if (isSocketOpen() == false) {
			LOG->warn("trying to do i/o on a null socket for session:0x"
					 + ELLong::toHexString(sessionId));

			return;
		}
		if (k->isReadable()) {
			int rc = sock->read(incomingBuffer.get());
			if (rc < 0) {
				throw EndOfStreamException(__FILE__, __LINE__,
						"Unable to read additional data from client sessionid 0x"
						+ ELLong::toHexString(sessionId)
						+ ", likely client has closed socket");
			}
			if (incomingBuffer->remaining() == 0) {
				boolean isPayload;
				if (incomingBuffer == lenBuffer) { // start of next request
					incomingBuffer->flip();
					isPayload = readLength(k);
					incomingBuffer->clear();
				} else {
					// continuation
					isPayload = true;
				}
				if (isPayload) { // not the case for 4letterword
					readPayload();
				}
				else {
					// four letter words take care
					// need not do anything else
					return;
				}
			}
		}
		if (k->isWritable()) {
			// ZooLog.logTraceMessage(LOG,
			// ZooLog.CLIENT_DATA_PACKET_TRACE_MASK
			// "outgoingBuffers.size() = " +
			// outgoingBuffers.size());
			if (outgoingBuffers.size() > 0) {
				// ZooLog.logTraceMessage(LOG,
				// ZooLog.CLIENT_DATA_PACKET_TRACE_MASK,
				// "sk " + k + " is valid: " +
				// k.isValid());

				/*
				 * This is going to reset the buffer position to 0 and the
				 * limit to the size of the buffer, so that we can fill it
				 * with data from the non-direct buffers that we need to
				 * send.
				 */
				sp<EIOByteBuffer> directBuffer = factory->directBuffer;
				directBuffer->clear();

				auto iter = outgoingBuffers.iterator();
				while (iter->hasNext()) {
					sp<EIOByteBuffer> b = iter->next();
					if (directBuffer->remaining() < b->remaining()) {
						/*
						 * When we call put later, if the directBuffer is to
						 * small to hold everything, nothing will be copied,
						 * so we've got to slice the buffer if it's too big.
						 */
						//@see: b = (ByteBuffer) b.slice().limit(directBuffer.remaining());
						b = b->slice();
						b->limit(directBuffer->remaining());
					}
					/*
					 * put() is going to modify the positions of both
					 * buffers, put we don't want to change the position of
					 * the source buffers (we'll do that after the send, if
					 * needed), so we save and reset the position after the
					 * copy
					 */
					int p = b->position();
					directBuffer->put(b.get());
					b->position(p);
					if (directBuffer->remaining() == 0) {
						break;
					}
				}
				/*
				 * Do the flip: limit becomes position, position gets set to
				 * 0. This sets us up for the write.
				 */
				directBuffer->flip();

				int sent = sock->write(directBuffer.get());
				sp<EIOByteBuffer> bb;

				// Remove the buffers that we have sent
				while (outgoingBuffers.size() > 0) {
					bb = outgoingBuffers.peek();
					if (bb == ServerCnxnFactory::closeConn) {
						throw CloseRequestException(__FILE__, __LINE__, "close requested");
					}
					int left = bb->remaining() - sent;
					if (left > 0) {
						/*
						 * We only partially sent this buffer, so we update
						 * the position and exit the loop.
						 */
						bb->position(bb->position() + sent);
						break;
					}
					packetSent();
					/* We've sent the whole buffer, so drop the buffer */
					sent -= bb->remaining();
					outgoingBuffers.remove();
				}
				// ZooLog.logTraceMessage(LOG,
				// ZooLog.CLIENT_DATA_PACKET_TRACE_MASK, "after send,
				// outgoingBuffers.size() = " + outgoingBuffers.size());
			}

			SYNCHRONIZED(this->factory){
				if (outgoingBuffers.size() == 0) {
					if (!initialized
							&& (sk->interestOps() & ESelectionKey::OP_READ) == 0) {
						throw CloseRequestException(__FILE__, __LINE__, "responded to info probe");
					}
					sk->interestOps(sk->interestOps()
							& (~ESelectionKey::OP_WRITE));
				} else {
					sk->interestOps(sk->interestOps()
							| ESelectionKey::OP_WRITE);
				}
			}}
		}
	} catch (ECancelledKeyException& e) {
		LOG->warn("CancelledKeyException causing close of session 0x"
				 + ELLong::toHexString(sessionId));
		if (LOG->isDebugEnabled()) {
			LOG->debug("CancelledKeyException stack trace", e);
		}
		close();
	} catch (CloseRequestException& e) {
		// expecting close to log session closure
		close();
	} catch (EndOfStreamException& e) {
		LOG->warn(e.getMessage());
		if (LOG->isDebugEnabled()) {
			LOG->debug("EndOfStreamException stack trace", e);
		}
		// expecting close to log session closure
		close();
	} catch (EIOException& e) {
		LOG->warn("Exception causing close of session 0x"
				 + ELLong::toHexString(sessionId) + ": " + e.getMessage());
		if (LOG->isDebugEnabled()) {
			LOG->debug("IOException stack trace", e);
		}
		close();
	}
}

void NIOServerCnxn::internalSendBuffer(sp<EIOByteBuffer> bb) {
	if (bb != ServerCnxnFactory::closeConn) {
		// We check if write interest here because if it is NOT set,
		// nothing is queued, so we can try to send the buffer right
		// away without waking up the selector
		if(sk->isValid() &&
				((sk->interestOps() & ESelectionKey::OP_WRITE) == 0)) {
			try {
				sock->write(bb.get());
			} catch (EIOException& e) {
				// we are just doing best effort right now
			}
		}
		// if there is nothing left to send, we are done
		if (bb->remaining() == 0) {
			packetSent();
			return;
		}
	}

	SYNCHRONIZED(this->factory) {
		sk->selector()->wakeup();
		if (LOG->isTraceEnabled()) {
			LOG->trace("Add a buffer to outgoingBuffers, sk " + sk->toString()
					+ " is valid: " + sk->isValid());
		}
		outgoingBuffers.add(bb);
		if (sk->isValid()) {
			sk->interestOps(sk->interestOps() | ESelectionKey::OP_WRITE);
		}
	}}
}

void NIOServerCnxn::incrOutstandingRequests(RequestHeader* h) {
	if (h->getXid() >= 0) {
		SYNCHRONIZED(this) {
			outstandingRequests++;
		}}
		SYNCHRONIZED (this->factory) {
			// check throttling
			if (zkServer->getInProcess() > outstandingLimit) {
				if (LOG->isDebugEnabled()) {
					LOG->debug(EString("Throttling recv ") + zkServer->getInProcess());
				}
				disableRecv();
				// following lines should not be needed since we are
				// already reading
				// } else {
				// enableRecv();
			}
		}}
	}
}

void NIOServerCnxn::enableRecv() {
	SYNCHRONIZED (this->factory) {
		sk->selector()->wakeup();
		if (sk->isValid()) {
			int interest = sk->interestOps();
			if ((interest & ESelectionKey::OP_READ) == 0) {
				sk->interestOps(interest | ESelectionKey::OP_READ);
			}
		}
	}}
}

llong NIOServerCnxn::getOutstandingRequests() {
	SYNCHRONIZED (this) {
		SYNCHRONIZED (this->factory) {
			return outstandingRequests;
		}}
	}}
}

void NIOServerCnxn::close() {
	factory->removeCnxn(this);

	if (zkServer != null) {
		zkServer->removeCnxn(this);
	}

	closeSock();

	if (sk != null) {
		try {
			// need to cancel this selection key from the selector
			sk->cancel();
		} catch (EException& e) {
			if (LOG->isDebugEnabled()) {
				LOG->debug("ignoring exception during selectionkey cancel", e);
			}
		}
	}
}

void NIOServerCnxn::sendResponse(sp<ReplyHeader> h, sp<ERecord> r, EString tag) THROWS(EIOException) {
	SYNCHRONIZED(this) {
		try {
			EByteArrayOutputStream baos;// = new ByteArrayOutputStream();
			// Make space for length
			sp<EBinaryOutputArchive> bos = EBinaryOutputArchive::getArchive(&baos);
			try {
				baos.write("1234", 4);
				bos->writeRecord(h.get(), "header");
				if (r != null) {
					bos->writeRecord(r.get(), tag.c_str());
				}
				baos.close();
			} catch (EIOException& e) {
				LOG->error("Error serializing response");
			}
			sp<EA<byte> > b = baos.toByteArray();
			sp<EIOByteBuffer> bb = EIOByteBuffer::wrap(b->address(), b->length());
			bb->putInt(b->length() - 4)->rewind();
			sendBuffer(bb);
			if (h->getXid() > 0) {
				SYNCHRONIZED(this){
					outstandingRequests--;
				}}
				// check throttling
				SYNCHRONIZED (this->factory) {
					if (zkServer->getInProcess() < outstandingLimit
							|| outstandingRequests < 1) {
						sk->selector()->wakeup();
						enableRecv();
					}
				}}
			}
		 } catch(EException& e) {
			LOG->warn("Unexpected exception. Destruction averted.", e);
		 }
	}}
}

void NIOServerCnxn::setSessionId(llong sessionId) {
	this->sessionId = sessionId;
	this->factory->addSession(sessionId, shared_from_this());
}

boolean NIOServerCnxn::checkFourLetterWord(sp<ESelectionKey> k, int len) {
	// We take advantage of the limited size of the length to look
	// for cmds. They are all 4-bytes which fits inside of an int
	if (!ServerCnxn::isKnown(len)) {
		return false;
	}

	packetReceived();

	/** cancel the selection key to remove the socket handling
	 * from selector. This is to prevent netcat problem wherein
	 * netcat immediately closes the sending side after sending the
	 * commands and still keeps the receiving channel open.
	 * The idea is to remove the selectionkey from the selector
	 * so that the selector does not notice the closed read on the
	 * socket channel and keep the socket alive to write the data to
	 * and makes sure to close the socket after its done writing the data
	 */
	if (k != null) {
		try {
			k->cancel();
		} catch(EException& e) {
			LOG->error("Error cancelling command selection key ", e);
		}
	}

	sp<EPrintStream> pwriter = new EPrintStream(
			new EBufferedOutputStream(new prv::SendBufferWriter(shared_from_this()), 8192, true),
			false, true);

	EString cmd = ServerCnxn::getCommandString(len);
	// ZOOKEEPER-2693: don't execute 4lw if it's not enabled.
	if (!ServerCnxn::isEnabled(cmd)) {
		LOG->debug("Command " + cmd + " is not executed because it is not in the whitelist.");
		prv::NopCommand* nopCmd = new prv::NopCommand(shared_from_this(), pwriter, cmd + " is not executed because it is not in the whitelist.");
		EThread::setDaemon(nopCmd, true); //!!!
		nopCmd->start();
		return true;
	}

	LOG->info("Processing " + cmd + " command from "
			+ sock->socket()->getRemoteSocketAddress()->toString());

	if (len == ServerCnxn::ruokCmd) {
		prv::RuokCommand* ruok = new prv::RuokCommand(shared_from_this(), pwriter);
		EThread::setDaemon(ruok, true); //!!!
		ruok->start();
		return true;
	} else if (len == ServerCnxn::getTraceMaskCmd) {
		prv::TraceMaskCommand* tmask = new prv::TraceMaskCommand(shared_from_this(), pwriter);
		EThread::setDaemon(tmask, true); //!!!
		tmask->start();
		return true;
	} else if (len == ServerCnxn::setTraceMaskCmd) {
		incomingBuffer = EIOByteBuffer::allocate(8);
		int rc = sock->read(incomingBuffer.get());
		if (rc < 0) {
			throw EIOException(__FILE__, __LINE__, "Read error");
		}

		incomingBuffer->flip();
		llong traceMask = incomingBuffer->getLLong();
		ZooTrace::setTextTraceLevel(traceMask);
		prv::SetTraceMaskCommand* setMask = new prv::SetTraceMaskCommand(shared_from_this(), pwriter, traceMask);
		EThread::setDaemon(setMask, true); //!!!
		setMask->start();
		return true;
	} else if (len == ServerCnxn::enviCmd) {
		prv::EnvCommand* env = new prv::EnvCommand(shared_from_this(), pwriter);
		EThread::setDaemon(env, true); //!!!
		env->start();
		return true;
	} else if (len == ServerCnxn::confCmd) {
		prv::ConfCommand* ccmd = new prv::ConfCommand(shared_from_this(), pwriter);
		EThread::setDaemon(ccmd, true); //!!!
		ccmd->start();
		return true;
	} else if (len == ServerCnxn::srstCmd) {
		prv::StatResetCommand* strst = new prv::StatResetCommand(shared_from_this(), pwriter);
		EThread::setDaemon(strst, true); //!!!
		strst->start();
		return true;
	} else if (len == ServerCnxn::crstCmd) {
		prv::CnxnStatResetCommand* crst = new prv::CnxnStatResetCommand(shared_from_this(), pwriter);
		EThread::setDaemon(crst, true); //!!!
		crst->start();
		return true;
	} else if (len == ServerCnxn::dumpCmd) {
		prv::DumpCommand* dump = new prv::DumpCommand(shared_from_this(), pwriter);
		EThread::setDaemon(dump, true); //!!!
		dump->start();
		return true;
	} else if (len == ServerCnxn::statCmd || len == ServerCnxn::srvrCmd) {
		prv::StatCommand* stat = new prv::StatCommand(shared_from_this(), pwriter, len);
		EThread::setDaemon(stat, true); //!!!
		stat->start();
		return true;
	} else if (len == ServerCnxn::consCmd) {
		prv::ConsCommand* cons = new prv::ConsCommand(shared_from_this(), pwriter);
		EThread::setDaemon(cons, true); //!!!
		cons->start();
		return true;
	} else if (len == ServerCnxn::wchpCmd || len == ServerCnxn::wchcCmd || len == ServerCnxn::wchsCmd) {
		prv::WatchCommand* wcmd = new prv::WatchCommand(shared_from_this(), pwriter, len);
		EThread::setDaemon(wcmd, true); //!!!
		wcmd->start();
		return true;
	} else if (len == ServerCnxn::mntrCmd) {
		prv::MonitorCommand* mntr = new prv::MonitorCommand(shared_from_this(), pwriter);
		EThread::setDaemon(mntr, true); //!!!
		mntr->start();
		return true;
	} else if (len == ServerCnxn::isroCmd) {
		prv::IsroCommand* isro = new prv::IsroCommand(shared_from_this(), pwriter);
		EThread::setDaemon(isro, true); //!!!
		isro->start();
		return true;
	}
	return false;
}

boolean NIOServerCnxn::readLength(sp<ESelectionKey> k) THROWS(EIOException) {
    // Read the length, now get the buffer
    int len = lenBuffer->getInt();
    if (!initialized && checkFourLetterWord(sk, len)) {
        return false;
    }
    if (len < 0 || len > EBinaryInputArchive::maxBuffer) {
        throw EIOException(__FILE__, __LINE__, (EString("Len error ") + len).c_str());
    }
    if (!isZKServerRunning()) {
        throw EIOException(__FILE__, __LINE__, "ZooKeeperServer not running");
    }
    incomingBuffer = EIOByteBuffer::allocate(len);
    return true;
}

void NIOServerCnxn::process(sp<WatchedEvent> event) {
	SYNCHRONIZED(this){
		sp<ReplyHeader> h = new ReplyHeader(-1, -1L, 0);
		if (LOG->isTraceEnabled()) {
			ZooTrace::logTraceMessage(LOG, ZooTrace::EVENT_DELIVERY_TRACE_MASK,
									 "Deliver event " + event->toString() + " to 0x"
									 + ELLong::toHexString(this->sessionId)
									 + " through " + this->toString());
		}

		// Convert WatchedEvent to a type that can be sent over the wire
		sp<WatcherEvent> e = event->getWrapper();

		sendResponse(h, e, "notification");
	}}
}

boolean NIOServerCnxn::isZKServerRunning() {
	return zkServer != null && zkServer->isRunning();
}

} /* namespace ezk */
} /* namespace efc */
