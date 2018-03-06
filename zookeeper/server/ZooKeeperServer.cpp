/*
 * ZooKeeperServer.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./ZooKeeperServer.hh"
#include "./Request.hh"
#include "./SyncRequestProcessor.hh"
#include "./quorum/ReadOnlyZooKeeperServer.hh"

namespace efc {
namespace ezk {

/**
 * Default listener implementation, which will be used to notify internal
 * errors. For example, if some critical thread has stopped due to fatal errors,
 * then it will get notifications and will change the state of ZooKeeper server
 * to ERROR representing an error status.
 */

class ZooKeeperServerListenerImpl : virtual public ZooKeeperServerListener {
private:
    ZooKeeperServer* zkServer;

public:
    ZooKeeperServerListenerImpl(ZooKeeperServer* zkServer) {
        this->zkServer = zkServer;
    }

    virtual void notifyStopping(EString threadName, int errorCode) {
        zkServer->setState(State::ERROR);
    }
};

//=============================================================================

sp<ELogger> ZooKeeperServer::LOG = ELoggerManager::getLogger("ZooKeeperServer");

ZooKeeperServer::~ZooKeeperServer() {
	delete listener;
}

ZooKeeperServer::ZooKeeperServer() :
		tickTime(DEFAULT_TICK_TIME),
		minSessionTimeout(-1),
		maxSessionTimeout(-1),
		state(State::INITIAL),
		txnLogFactory(null),
		superSecret(0XB3415C00L),
		serverCnxnFactory(null),
		listener(null),
		zkShutdownHandler(null),
		stats(this) {
	Environment::logEnv("Server environment:", LOG);

	listener = new ZooKeeperServerListenerImpl(this);
}

ZooKeeperServer::ZooKeeperServer(sp<FileTxnSnapLog> txnLogFactory, int tickTime,
		int minSessionTimeout, int maxSessionTimeout, sp<ZKDatabase> zkDb) :
		tickTime(DEFAULT_TICK_TIME),
		minSessionTimeout(-1),
		maxSessionTimeout(-1),
		state(State::INITIAL),
		txnLogFactory(null),
		superSecret(0XB3415C00L),
		serverCnxnFactory(null),
		listener(null),
		zkShutdownHandler(null),
		stats(this)
{
	this->txnLogFactory = txnLogFactory;
	this->zkDb = zkDb;
	this->tickTime = tickTime;
	this->minSessionTimeout = minSessionTimeout;
	this->maxSessionTimeout = maxSessionTimeout;

	listener = new ZooKeeperServerListenerImpl(this);

	LOG->info(EString("Created server with tickTime ") + tickTime
			+ " minSessionTimeout " + getMinSessionTimeout()
			+ " maxSessionTimeout " + getMaxSessionTimeout()
			+ " datadir " + txnLogFactory->getDataDir()
			+ " snapdir " + txnLogFactory->getSnapDir());
}

ZooKeeperServer::ZooKeeperServer(sp<FileTxnSnapLog> txnLogFactory, int tickTime) :
		tickTime(DEFAULT_TICK_TIME),
		minSessionTimeout(-1),
		maxSessionTimeout(-1),
		state(State::INITIAL),
		txnLogFactory(null),
		superSecret(0XB3415C00L),
		serverCnxnFactory(null),
		listener(null),
		zkShutdownHandler(null),
		stats(this)
{
	this->txnLogFactory = txnLogFactory;
	this->zkDb = new ZKDatabase(txnLogFactory);
	this->tickTime = tickTime;
	this->minSessionTimeout = -1;
	this->maxSessionTimeout = -1;

	listener = new ZooKeeperServerListenerImpl(this);

	LOG->info(EString("Created server with tickTime ") + tickTime
			+ " minSessionTimeout " + getMinSessionTimeout()
			+ " maxSessionTimeout " + getMaxSessionTimeout()
			+ " datadir " + txnLogFactory->getDataDir()
			+ " snapdir " + txnLogFactory->getSnapDir());
}

void ZooKeeperServer::setupRequestProcessors() {
	/* @see:
	RequestProcessor finalProcessor = new FinalRequestProcessor(this);
	RequestProcessor syncProcessor = new SyncRequestProcessor(this,
			finalProcessor);
	((SyncRequestProcessor)syncProcessor).start();
	firstProcessor = new PrepRequestProcessor(this, syncProcessor);
	((PrepRequestProcessor)firstProcessor).start();
	*/
	sp<SyncRequestProcessor> syncProcessor = new SyncRequestProcessor(shared_from_this(),
			new FinalRequestProcessor(shared_from_this()));
	EThread::setDaemon(syncProcessor, true); //!!!
	syncProcessor->start();
	sp<PrepRequestProcessor> prp = new PrepRequestProcessor(shared_from_this(), syncProcessor);
	firstProcessor = prp;
	EThread::setDaemon(prp, true); //!!!
	prp->start();
}

void ZooKeeperServer::finishSessionInit(sp<ServerCnxn> cnxn, boolean valid)
{
	try {
		ConnectResponse rsp(0, valid ? cnxn->getSessionTimeout()
				: 0, valid ? cnxn->getSessionId() : 0, // send 0 if session is no
						// longer valid
						valid ? generatePasswd(cnxn->getSessionId()) : new EA<byte>(16));
		EByteArrayOutputStream baos;
		sp<EBinaryOutputArchive> bos = EBinaryOutputArchive::getArchive(&baos);
		bos->writeInt(-1, "len");
		rsp.serialize(bos.get(), "connect");
		if (!cnxn->isOldClient) {
			ReadOnlyZooKeeperServer* o = dynamic_cast<ReadOnlyZooKeeperServer*>(this);
			bos->writeBool(!!o, "readOnly");
		}
		baos.close();
		sp<EIOByteBuffer> bb = EIOByteBuffer::wrap(baos.data(), baos.size());
		bb->putInt(bb->remaining() - 4)->rewind();
		cnxn->sendBuffer(bb);

		if (!valid) {
			LOG->info("Invalid session 0x"
					+ ELLong::toHexString(cnxn->getSessionId())
					+ " for client "
					+ cnxn->getRemoteSocketAddress()->toString()
					+ ", probably expired");
			cnxn->sendBuffer(ServerCnxnFactory::closeConn);
		} else {
			LOG->info("Established session 0x"
					+ ELLong::toHexString(cnxn->getSessionId())
					+ " with negotiated timeout " + cnxn->getSessionTimeout()
					+ " for client "
					+ cnxn->getRemoteSocketAddress()->toString());
			cnxn->enableRecv();
		}

	} catch (EException& e) {
		LOG->warn("Exception while establishing session, closing", e);
		cnxn->close();
	}
}

void ZooKeeperServer::processConnectRequest(sp<ServerCnxn> cnxn, sp<EIOByteBuffer> incomingBuffer) THROWS(EIOException)
{
	ByteBufferInputStream bbis(incomingBuffer.get());
	sp<EBinaryInputArchive> bia = EBinaryInputArchive::getArchive(&bbis);
	ConnectRequest connReq;// = new ConnectRequest();
	connReq.deserialize(bia.get(), "connect");
	if (LOG->isDebugEnabled()) {
		LOG->debug("Session establishment request from client "
				+ cnxn->getRemoteSocketAddress()->toString()
				+ " client's lastZxid is 0x"
				+ ELLong::toHexString(connReq.getLastZxidSeen()));
	}
	boolean readOnly = false;
	try {
		readOnly = bia->readBool("readOnly");
		cnxn->isOldClient = false;
	} catch (EIOException& e) {
		// this is ok -- just a packet from an old client which
		// doesn't contain readOnly field
		LOG->warn("Connection request from old client "
				+ cnxn->getRemoteSocketAddress()->toString()
				+ "; will be dropped if server is in r-o mode");
	}
	if (readOnly == false) {
		ReadOnlyZooKeeperServer* o = dynamic_cast<ReadOnlyZooKeeperServer*>(this);
		if (o) {
			EString msg = "Refusing session request for not-read-only client "
				+ cnxn->getRemoteSocketAddress()->toString();
			LOG->info(msg.c_str());
			throw CloseRequestException(__FILE__, __LINE__, msg);
		}
	}
	if (connReq.getLastZxidSeen() > zkDb->dataTree->lastProcessedZxid) {
		EString msg = "Refusing session request for client "
			+ cnxn->getRemoteSocketAddress()->toString()
			+ " as it has seen zxid 0x"
			+ ELLong::toHexString(connReq.getLastZxidSeen())
			+ " our last zxid is 0x"
			+ ELLong::toHexString(getZKDatabase()->getDataTreeLastProcessedZxid())
			+ " client must try another server";

		LOG->info(msg.c_str());
		throw CloseRequestException(__FILE__, __LINE__, msg);
	}
	int sessionTimeout = connReq.getTimeOut();
	sp<EA<byte> > passwd = connReq.getPasswd();
	int minSessionTimeout = getMinSessionTimeout();
	if (sessionTimeout < minSessionTimeout) {
		sessionTimeout = minSessionTimeout;
	}
	int maxSessionTimeout = getMaxSessionTimeout();
	if (sessionTimeout > maxSessionTimeout) {
		sessionTimeout = maxSessionTimeout;
	}
	cnxn->setSessionTimeout(sessionTimeout);
	// We don't want to receive any packets until we are sure that the
	// session is setup
	cnxn->disableRecv();
	llong sessionId = connReq.getSessionId();
	if (sessionId != 0) {
		llong clientSessionId = connReq.getSessionId();
		LOG->info("Client attempting to renew session 0x"
				+ ELLong::toHexString(clientSessionId)
				+ " at " + cnxn->getRemoteSocketAddress()->toString());
		serverCnxnFactory->closeSession(sessionId);
		cnxn->setSessionId(sessionId);
		reopenSession(cnxn, sessionId, passwd.get(), sessionTimeout);
	} else {
		LOG->info("Client attempting to establish new session at "
				+ cnxn->getRemoteSocketAddress()->toString());
		createSession(cnxn, passwd.get(), sessionTimeout);
	}
}

void ZooKeeperServer::processPacket(sp<ServerCnxn> cnxn, sp<EIOByteBuffer> incomingBuffer) THROWS(EIOException)
{
	// We have the request, now process and setup for next
	ByteBufferInputStream bais(incomingBuffer.get());
	sp<EBinaryInputArchive> bia = EBinaryInputArchive::getArchive(&bais);
	RequestHeader h;// = new RequestHeader();
	h.deserialize(bia.get(), "header");
	// Through the magic of byte buffers, txn will not be
	// pointing
	// to the start of the txn
	incomingBuffer = incomingBuffer->slice();
	if (h.getType() == ZooDefs::OpCode::auth) {
		LOG->info("got auth packet " + cnxn->getRemoteSocketAddress()->toString());
		AuthPacket authPacket;// = new AuthPacket();
		ByteBufferInputStream::byteBuffer2Record(incomingBuffer.get(), &authPacket);
		EString scheme = authPacket.getScheme();
		AuthenticationProvider* ap = ProviderRegistry::getProvider(scheme);
		KeeperException::Code authReturn = KeeperException::Code::AUTHFAILED;
		if(ap != null) {
			try {
				sp<EA<byte> > data = authPacket.getAuth();
				authReturn = ap->handleAuthentication(cnxn, data->address(), data->length());
			} catch (ERuntimeException& e) {
				LOG->warn("Caught runtime exception from AuthenticationProvider: " + scheme, e);
				authReturn = KeeperException::Code::AUTHFAILED;
			}
		}
		if (authReturn!= KeeperException::Code::OK) {
			if (ap == null) {
				LOG->warn("No authentication provider for scheme: "
						+ scheme + " has "
						+ ProviderRegistry::listProviders());
			} else {
				LOG->warn("Authentication failed for scheme: " + scheme);
			}
			// send a response...
			sp<ReplyHeader> rh = new ReplyHeader(h.getXid(), 0,
					KeeperException::Code::AUTHFAILED);
			cnxn->sendResponse(rh, null, null);
			// ... and close connection
			cnxn->sendBuffer(ServerCnxnFactory::closeConn);
			cnxn->disableRecv();
		} else {
			if (LOG->isDebugEnabled()) {
				LOG->debug("Authentication succeeded for scheme: "
						  + scheme);
			}
			LOG->info("auth success " + cnxn->getRemoteSocketAddress()->toString());
			sp<ReplyHeader> rh = new ReplyHeader(h.getXid(), 0,
					KeeperException::Code::OK);
			cnxn->sendResponse(rh, null, null);
		}
		return;
	} else {
		//if (h.getType() == ZooDefs::OpCode::sasl) {
		//	throw ERuntimeException("xxxxxx");
		//}
		//else {
			sp<Request> si = new Request(cnxn, cnxn->getSessionId(), h.getXid(),
			  h.getType(), incomingBuffer, cnxn->getAuthInfo());
			si->setOwner(ServerCnxn::me);
			submitRequest(si);
		//}
	}
	cnxn->incrOutstandingRequests(&h);
}

} /* namespace ezk */
} /* namespace efc */
