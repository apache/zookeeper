/*
 * QuorumCnxManager.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./QuorumCnxManager.hh"

namespace efc {
namespace ezk {

void QuorumCnxManager::Listener::run() {
	int numRetries = 0;
	sp<EInetSocketAddress> addr;
	while((!mgr->shutdown) && (numRetries < 3)){
		try {
			ss = new EServerSocket();
			ss->setReuseAddress(true);
			if (mgr->listenOnAllIPs) {
				int port = mgr->view->get(mgr->mySid)
					->electionAddr->getPort();
				addr = new EInetSocketAddress(port);
			} else {
				addr = mgr->view->get(mgr->mySid)->electionAddr;
			}
			LOG->info("My election bind port: " + addr->toString());
			setName(mgr->view->get(mgr->mySid)->electionAddr->toString().c_str());
			ss->bind(addr.get());
			while (!mgr->shutdown) {
				sp<ESocket> client = ss->accept();
				mgr->setSockOpts(client);
				LOG->info("Received connection request "
						+ client->getRemoteSocketAddress()->toString());

				// Receive and handle the connection request
				// asynchronously if the quorum sasl authentication is
				// enabled. This is required because sasl server
				// authentication process may take few seconds to finish,
				// this may delay next peer connection requests.
				mgr->receiveConnection(client);

				numRetries = 0;
			}
		} catch (EIOException& e) {
			LOG->error(__FILE__, __LINE__, "Exception while listening", e);
			numRetries++;
			try {
				ss->close();
				EThread::sleep(1000);
			} catch (EIOException& ie) {
				LOG->error(__FILE__, __LINE__, "Error closing server socket", ie);
			} catch (EInterruptedException& ie) {
				LOG->error(__FILE__, __LINE__, "Interrupted while sleeping. "
						  "Ignoring exception", ie);
			}
		}
	}
	LOG->info("Leaving listener");
	if (!mgr->shutdown) {
		LOG->error("As I'm leaving the listener thread, "
				    "I won't be able to participate in leader "
				    "election any longer: "
				   + mgr->view->get(mgr->mySid)->electionAddr->toString());
	}
}

boolean QuorumCnxManager::SendWorker::finish() {
	SYNCHRONIZED(this) {
		if (LOG->isDebugEnabled()) {
			LOG->debug(EString("Calling finish for ") + sid);
		}

		if(!running){
			/*
			 * Avoids running finish() twice.
			 */
			return running;
		}

		running = false;
		mgr->closeSocket(sock);
		// channel = null;

		this->interrupt();
		sp<RecvWorker> rw = recvWorker.lock();
		if (rw != null) {
			rw->finish();
		}

		if (LOG->isDebugEnabled()) {
			LOG->debug(EString("Removing entry from senderWorkerMap sid=") + sid);
		}
		mgr->senderWorkerMap.remove(sid, this);
		return running;
	}}
}

void QuorumCnxManager::SendWorker::send(sp<EIOByteBuffer> b) THROWS(EIOException) {
	SYNCHRONIZED(this) {
		EA<byte> msgBytes(b->capacity());
		try {
			b->position(0);
			b->get(msgBytes.address(), msgBytes.length(), msgBytes.length());
		} catch (EBufferUnderflowException& be) {
			LOG->error(__FILE__, __LINE__, "BufferUnderflowException ", be);
			return;
		}
		dout->writeInt(b->capacity());
		//@see: dout.write(b.array());
		ES_ASSERT(b->capacity() == msgBytes.length());
		dout->write(msgBytes.address(), msgBytes.length());
		dout->flush();
	}}
}

void QuorumCnxManager::SendWorker::run() {
	try {
		/**
		 * If there is nothing in the queue to send, then we
		 * send the lastMessage to ensure that the last message
		 * was received by the peer. The message could be dropped
		 * in case self or the peer shutdown their connection
		 * (and exit the thread) prior to reading/processing
		 * the last message. Duplicate messages are handled correctly
		 * by the peer.
		 *
		 * If the send queue is non-empty, then we have a recent
		 * message than that stored in lastMessage. To avoid sending
		 * stale message, we should send the message in the send queue.
		 */
		sp<EArrayBlockingQueue<EIOByteBuffer> > bq = mgr->queueSendMap.get(sid);
		if (bq == null || mgr->isSendQueueEmpty(bq)) {
		   sp<EIOByteBuffer> b = mgr->lastMessageSent.get(sid);
		   if (b != null) {
			   LOG->debug(EString("Attempting to send lastMessage to sid=") + sid);
			   send(b);
		   }
		}
	} catch (EIOException& e) {
		LOG->error(__FILE__, __LINE__, "Failed to send last message. Shutting down thread.", e);
		this->finish();
	}

	try {
		while (running && !mgr->shutdown && sock != null) {

			sp<EIOByteBuffer> b = null;
			try {
				sp<EArrayBlockingQueue<EIOByteBuffer> > bq = mgr->queueSendMap.get(sid);
				if (bq != null) {
					b = mgr->pollSendQueue(bq, 1000, ETimeUnit::MILLISECONDS);
				} else {
					LOG->error(EString("No queue of incoming messages for server ") + sid);
					break;
				}

				if(b != null){
					mgr->lastMessageSent.put(sid, b);
					send(b);
				}
			} catch (EInterruptedException& e) {
				LOG->warn(__FILE__, __LINE__, "Interrupted while waiting for message on queue",
						e);
			}
		}
	} catch (EException& e) {
		LOG->warn(__FILE__, __LINE__, EString("Exception when using channel: for id ") + sid
				 + " my id = " + mgr->mySid
				 + " error = ", e);
	}
	this->finish();
	LOG->warn("Send worker leaving thread");
}

boolean QuorumCnxManager::RecvWorker::finish() {
	SYNCHRONIZED(this) {
		if(!running){
			/*
			 * Avoids running finish() twice.
			 */
			return running;
		}
		running = false;

		this->interrupt();
		return running;
	}}
}

void QuorumCnxManager::RecvWorker::run() {
	ON_FINALLY_NOTHROW(
		LOG->warn("Interrupting SendWorker");
		sw->finish();
		if (sock != null) {
			mgr->closeSocket(sock);
		}
	) {
		try {
			while (running && !mgr->shutdown && sock != null) {
				/**
				 * Reads the first int to determine the length of the
				 * message
				 */
				int length = din->readInt();
				if (length <= 0 || length > PACKETMAXSIZE) {
					throw EIOException(__FILE__, __LINE__, (EString("Received packet with invalid packet: ")
									+ length).c_str());
				}
				/**
				 * Allocates a new ByteBuffer to receive the message
				 */
				/* @see:
				byte[] msgArray = new byte[length];
				din.readFully(msgArray, 0, length);
				ByteBuffer message = ByteBuffer.wrap(msgArray);
				addToRecvQueue(new Message(message.duplicate(), sid));
				*/
				sp<EIOByteBuffer> message = EIOByteBuffer::allocate(length);
				din->readFully((byte*)message->address(), message->capacity());
				mgr->addToRecvQueue(new Message(message, sid));
			}
		} catch (EException& e) {
			LOG->warn(__FILE__, __LINE__, EString("Connection broken for id ") + sid + ", my id = "
					 + mgr->mySid , e);
		}
	}}
}

//=============================================================================

sp<ELogger> QuorumCnxManager::LOG = ELoggerManager::getLogger("QuorumCnxManager");

void QuorumCnxManager::handleConnection(sp<ESocket> sock, sp<EDataInputStream> din) THROWS(EIOException) {
    llong sid = 0;
    try {
        // Read server id
        sid = din->readLLong();
        if (sid < 0) { // this is not a server id but a protocol version (see ZOOKEEPER-1633)
            sid = din->readLLong();

            // next comes the #bytes in the remainder of the message
            // note that 0 bytes is fine (old servers)
            int num_remaining_bytes = din->readInt();
            if (num_remaining_bytes < 0 || num_remaining_bytes > maxBuffer) {
                LOG->error(EString("Unreasonable buffer length: ") + num_remaining_bytes);
                closeSocket(sock);
                return;
            }
            byte b[num_remaining_bytes];

            // remove the remainder of the message from din
            int num_read = din->read(b, sizeof(b));
            if (num_read != num_remaining_bytes) {
                LOG->error(EString("Read only ") + num_read + " bytes out of " + num_remaining_bytes + " sent by server " + sid);
            }
        }
        //@see: if (sid == QuorumPeer.OBSERVER_ID) {
        if (sid == ELLong::MAX_VALUE) {
            /*
             * Choose identifier at random. We need a value to identify
             * the connection.
             */
            sid = observerCounter.getAndDecrement();
            LOG->info(EString("Setting arbitrary identifier to observer: ") + sid);
        }
    } catch (EIOException& e) {
        closeSocket(sock);
        LOG->warn("Exception reading or writing challenge: " + e.toString());
        return;
    }

    // do authenticating learner
    LOG->debug(EString("Authenticating learner server.id: ") + sid);
    authServer->authenticate(sock, din);

    //If wins the challenge, then close the new connection.
    if (sid < this->mySid) {
        /*
         * This replica might still believe that the connection to sid is
         * up, so we have to shut down the workers before trying to open a
         * new connection.
         */
        sp<SendWorker> sw = senderWorkerMap.get(sid);
        if (sw != null) {
            sw->finish();
        }

        /*
         * Now we start a new connection
         */
        LOG->debug(EString("Create new connection to server: ") + sid);
        closeSocket(sock);
        connectOne(sid);

        // Otherwise start worker threads to receive data.
    } else {
        sp<SendWorker> sw = new SendWorker(this, sock, sid);
        sp<RecvWorker> rw = new RecvWorker(this, sock, din, sid, sw);
        sw->setRecv(rw);

        sp<SendWorker> vsw = senderWorkerMap.get(sid);

        if(vsw != null)
            vsw->finish();

        senderWorkerMap.put(sid, sw);
        queueSendMap.putIfAbsent(sid, new EArrayBlockingQueue<EIOByteBuffer>(SEND_CAPACITY));

        EThread::setDaemon(sw, true); //!!!
        sw->start();
        EThread::setDaemon(rw, true); //!!!
        rw->start();

        return;
    }
}

boolean QuorumCnxManager::startConnection(sp<ESocket> sock, llong sid) THROWS(EIOException) {
    sp<EDataInputStream> din = null;
    try {
        // Sending id and challenge
        EDataOutputStream dout(sock->getOutputStream());
        dout.writeLLong(this->mySid);
        dout.flush();

        din = new EDataInputStream(
                new EBufferedInputStream(sock->getInputStream()), true);
    } catch (EIOException& e) {
        LOG->warn(__FILE__, __LINE__, "Ignoring exception reading or writing challenge: ", e);
        closeSocket(sock);
        return false;
    }

    // authenticate learner
    authLearner->authenticate(sock, view->get(sid)->hostname);

    // If lost the challenge, then drop the new connection
    if (sid > this->mySid) {
        LOG->info(EString("Have smaller server identifier, so dropping the "
                 "connection: (") + sid + ", " + this->mySid + ")");
        closeSocket(sock);
        // Otherwise proceed with the connection
    } else {
        sp<SendWorker> sw = new SendWorker(this, sock, sid);
        sp<RecvWorker> rw = new RecvWorker(this, sock, din, sid, sw);
        sw->setRecv(rw);

        sp<SendWorker> vsw = senderWorkerMap.get(sid);

        if(vsw != null)
            vsw->finish();

        senderWorkerMap.put(sid, sw);
        queueSendMap.putIfAbsent(sid, new EArrayBlockingQueue<EIOByteBuffer>(SEND_CAPACITY));

        EThread::setDaemon(sw, true); //!!!
        sw->start();
        EThread::setDaemon(rw, true); //!!!
        rw->start();

        return true;
    }
    return false;
}

void QuorumCnxManager::connectOne(llong sid) {
	SYNCHRONIZED(this) {
		if (!connectedToPeer(sid)){
			sp<EInetSocketAddress> electionAddr;
			if (view->containsKey(sid)) {
				electionAddr = view->get(sid)->electionAddr;
			} else {
				LOG->warn(EString("Invalid server id: ") + sid);
				return;
			}
			try {

				LOG->debug(EString("Opening channel to server ") + sid);
				sp<ESocket> sock = new ESocket();
				setSockOpts(sock);
				sock->connect(view->get(sid)->electionAddr.get(), cnxTO);
				LOG->debug(EString("Connected to server ") + sid);

				// Sends connection request asynchronously if the quorum
				// sasl authentication is enabled. This is required because
				// sasl server authentication process may take few seconds to
				// finish, this may delay next peer connection requests.
				initiateConnection(sock, sid);
			} catch (EUnresolvedAddressException& e) {
				// Sun doesn't include the address that causes this
				// exception to be thrown, also UAE cannot be wrapped cleanly
				// so we log the exception in order to capture this critical
				// detail.
				LOG->warn(__FILE__, __LINE__, EString("Cannot open channel to ") + sid
						+ " at election address " + electionAddr->toString(), e);
				// Resolve hostname for this server in case the
				// underlying ip address has changed.
				if (view->containsKey(sid)) {
					view->get(sid)->recreateSocketAddresses();
				}
				throw e;
			} catch (EIOException& e) {
				LOG->warn(__FILE__, __LINE__, EString("Cannot open channel to ") + sid
						+ " at election address " + electionAddr->toString(),
						e);
				// We can't really tell if the server is actually down or it failed
				// to connect to the server because the underlying IP address
				// changed. Resolve the hostname again just in case.
				if (view->containsKey(sid)) {
					view->get(sid)->recreateSocketAddresses();
				}
			}
		} else {
			LOG->debug(EString("There is a connection already for server ") + sid);
		}
	}}
}

void QuorumCnxManager::addToRecvQueue(sp<QuorumCnxManager::Message> msg) {
    SYNCBLOCK(&recvQLock) {
        if (recvQueue.remainingCapacity() == 0) {
            try {
                recvQueue.remove();
            } catch (ENoSuchElementException& ne) {
                // element could be removed by poll()
                 LOG->debug("Trying to remove from an empty "
                     "recvQueue. Ignoring NoSuchElementException ");
            }
        }
        try {
            recvQueue.add(msg);
        } catch (EIllegalStateException& ie) {
            // This should never happen
            LOG->error(__FILE__, __LINE__, "Unable to insert element in the recvQueue ", ie);
        }
    }}
}

sp<QuorumCnxManager::Message> QuorumCnxManager::pollRecvQueue(llong timeout, ETimeUnit* unit) {
   return recvQueue.poll(timeout, unit);
}

void QuorumCnxManager::toSend(llong sid, sp<EIOByteBuffer> b) {
	/*
	 * If sending message to myself, then simply enqueue it (loopback).
	 */
	if (this->mySid == sid) {
		 b->position(0);
		 addToRecvQueue(new Message(b->duplicate(), sid));
		/*
		 * Otherwise send to the corresponding thread to send.
		 */
	} else {
		 /*
		  * Start a new connection if doesn't have one already.
		  */
		 sp<EArrayBlockingQueue<EIOByteBuffer> > bq = new EArrayBlockingQueue<EIOByteBuffer>(SEND_CAPACITY);
		 sp<EArrayBlockingQueue<EIOByteBuffer> > bqExisting = queueSendMap.putIfAbsent(sid, bq);
		 if (bqExisting != null) {
			 addToSendQueue(bqExisting, b);
		 } else {
			 addToSendQueue(bq, b);
		 }
		 connectOne(sid);
	}
}

void QuorumCnxManager::setSockOpts(sp<ESocket> sock) {
	sock->setTcpNoDelay(true);
	sock->setKeepAlive(tcpKeepAlive);
	sock->setSoTimeout(socketTimeout);
}

void QuorumCnxManager::closeSocket(sp<ESocket> sock) {
	try {
		sock->close();
	} catch (EIOException& ie) {
		LOG->error(__FILE__, __LINE__, "Exception while closing", ie);
	}
}

void QuorumCnxManager::addToSendQueue(sp<EArrayBlockingQueue<EIOByteBuffer> > queue,
	  sp<EIOByteBuffer> buffer) {
	if (queue->remainingCapacity() == 0) {
		try {
			queue->remove();
		} catch (ENoSuchElementException& ne) {
			// element could be removed by poll()
			LOG->debug("Trying to remove from an empty Queue. Ignoring NoSuchElementException");
		}
	}
	try {
		queue->add(buffer);
	} catch (EIllegalStateException& ie) {
		// This should never happen
		LOG->error(__FILE__, __LINE__, "Unable to insert an element in the queue ", ie);
	}
}

} /* namespace ezk */
} /* namespace efc */
