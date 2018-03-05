/*
 * FinalRequestProcessor.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./FinalRequestProcessor.hh"

namespace efc {
namespace ezk {

sp<ELogger> FinalRequestProcessor::LOG = ELoggerManager::getLogger("FinalRequestProcessor");

void FinalRequestProcessor::processRequest(sp<Request> request) {
	if (LOG->isDebugEnabled()) {
		LOG->debug("Processing request:: " + request->toString());
	}
	// request.addRQRec(">final");
	llong traceMask = ZooTrace::CLIENT_REQUEST_TRACE_MASK;
	if (request->type == ZooDefs::OpCode::ping) {
		traceMask = ZooTrace::SERVER_PING_TRACE_MASK;
	}
	if (LOG->isTraceEnabled()) {
		ZooTrace::logRequest(LOG, traceMask, 'E', request.get(), "");
	}
	sp<DataTree::ProcessTxnResult> rc = null;
	SYNCBLOCK(&zks->outstandingChangesLock) {
		while (!zks->outstandingChanges.isEmpty()
				&& zks->outstandingChanges.getAt(0)->zxid <= request->zxid) {
			sp<ZooKeeperServer::ChangeRecord> cr = zks->outstandingChanges.removeAt(0);
			if (cr->zxid < request->zxid) {
				LOG->warn(EString("Zxid outstanding ")
						+ cr->zxid
						+ " is less than current " + request->zxid);
			}
			if (zks->outstandingChangesForPath.get(&cr->path) == cr) {
				zks->outstandingChangesForPath.remove(&cr->path);
			}
		}
		if (request->hdr != null) {
		   auto hdr = request->hdr;
		   auto txn = request->txn;

		   rc = zks->processTxn(hdr.get(), txn.get());
		}
		// do not add non quorum packets to the queue.
		if (Request::isQuorum(request->type)) {
			zks->getZKDatabase()->addCommittedProposal(request);
		}
	}}

	if (request->hdr != null && request->hdr->getType() == ZooDefs::OpCode::closeSession) {
		ServerCnxnFactory* scxn = zks->getServerCnxnFactory();
		// this might be possible since
		// we might just be playing diffs from the leader
		if (scxn != null && request->cnxn == null) {
			// calling this if we have the cnxn results in the client's
			// close session response being lost - we've already closed
			// the session/socket here before we can send the closeSession
			// in the switch block below
			scxn->closeSession(request->sessionId);
			return;
		}
	}

	if (request->cnxn == null) {
		return;
	}
	sp<ServerCnxn> cnxn = request->cnxn;

	EString lastOp = "NA";
	zks->decInProcess();
	KeeperException::Code err = KeeperException::Code::OK;
	sp<ERecord> rsp = null;
	boolean closeSession = false;
	try {
		if (request->hdr != null && request->hdr->getType() == ZooDefs::OpCode::error) {
			ErrorTxn* txn = dynamic_cast<ErrorTxn*>(request->txn.get());
			ES_ASSERT(txn);
			KeeperException::Code err = (KeeperException::Code)txn->getErr();
			auto e = KeeperException::create(__FILE__, __LINE__, err);
			throw *e;
		}

		sp<KeeperException> ke = request->getException();
		if (ke != null && request->type != ZooDefs::OpCode::multi) {
			throw *ke;
		}

		if (LOG->isDebugEnabled()) {
			LOG->debug(request->toString());
		}
		switch (request->type) {
		case ZooDefs::OpCode::ping: {
			zks->serverStats()->updateLatency(request->createTime);

			lastOp = "PING";
			cnxn->updateStatsForResponse(request->cxid, request->zxid, lastOp,
					request->createTime, TimeUtils::currentElapsedTime());

			cnxn->sendResponse(new ReplyHeader(-2,
					zks->getZKDatabase()->getDataTreeLastProcessedZxid(), 0), null, "response");
			return;
		}
		case ZooDefs::OpCode::createSession: {
			zks->serverStats()->updateLatency(request->createTime);

			lastOp = "SESS";
			cnxn->updateStatsForResponse(request->cxid, request->zxid, lastOp,
					request->createTime, TimeUtils::currentElapsedTime());

			zks->finishSessionInit(request->cnxn, true);
			return;
		}
		case ZooDefs::OpCode::multi: {
			lastOp = "MULT";
			rsp = new MultiResponse() ;

			auto iter = rc->multiResult->iterator();
			while (iter->hasNext()) {
				auto subTxnResult = iter->next();

				OpResult* subResult = null;

				switch (subTxnResult->type) {
					case ZooDefs::OpCode::check:
						subResult = new CheckResult();
						break;
					case ZooDefs::OpCode::create:
						subResult = new CreateResult(subTxnResult->path);
						break;
					case ZooDefs::OpCode::delete_:
						subResult = new DeleteResult();
						break;
					case ZooDefs::OpCode::setData:
						subResult = new SetDataResult(subTxnResult->stat);
						break;
					case ZooDefs::OpCode::error:
						subResult = new ErrorResult(subTxnResult->err) ;
						break;
					default:
						throw EIOException(__FILE__, __LINE__, "Invalid type of op");
				}

				dynamic_pointer_cast<MultiResponse>(rsp)->add(subResult);
			}

			break;
		}
		case ZooDefs::OpCode::create: {
			lastOp = "CREA";
			rsp = new CreateResponse(rc->path);
			err = (KeeperException::Code)rc->err; //Code.get(rc.err);
			break;
		}
		case ZooDefs::OpCode::delete_: {
			lastOp = "DELE";
			err = (KeeperException::Code)rc->err; //Code.get(rc.err);
			break;
		}
		case ZooDefs::OpCode::setData: {
			lastOp = "SETD";
			rsp = new SetDataResponse(rc->stat);
			err = (KeeperException::Code)rc->err; //Code.get(rc.err);
			break;
		}
		case ZooDefs::OpCode::setACL: {
			lastOp = "SETA";
			rsp = new SetACLResponse(rc->stat);
			err = (KeeperException::Code)rc->err; //Code.get(rc.err);
			break;
		}
		case ZooDefs::OpCode::closeSession: {
			lastOp = "CLOS";
			closeSession = true;
			err = (KeeperException::Code)rc->err; //Code.get(rc.err);
			break;
		}
		case ZooDefs::OpCode::sync: {
			lastOp = "SYNC";
			sp<SyncRequest> syncRequest = new SyncRequest();
			ByteBufferInputStream::byteBuffer2Record(request->request.get(),
					syncRequest.get());
			rsp = new SyncResponse(syncRequest->getPath());
			break;
		}
		case ZooDefs::OpCode::check: {
			lastOp = "CHEC";
			rsp = new SetDataResponse(rc->stat);
			err = (KeeperException::Code)rc->err; //Code.get(rc.err);
			break;
		}
		case ZooDefs::OpCode::exists: {
			lastOp = "EXIS";
			// TODO we need to figure out the security requirement for this!
			sp<ExistsRequest> existsRequest = new ExistsRequest();
			ByteBufferInputStream::byteBuffer2Record(request->request.get(),
					existsRequest.get());
			EString path = existsRequest->getPath();
			if (path.indexOf('\0') != -1) {
				throw BadArgumentsException(__FILE__, __LINE__);
			}
			sp<Stat> stat = zks->getZKDatabase()->statNode(path, existsRequest
							->getWatch() ? cnxn : null);
			rsp = new ExistsResponse(stat);
			break;
		}
		case ZooDefs::OpCode::getData: {
			lastOp = "GETD";
			sp<GetDataRequest> getDataRequest = new GetDataRequest();
			ByteBufferInputStream::byteBuffer2Record(request->request.get(),
					getDataRequest.get());
			sp<DataNode> n = zks->getZKDatabase()->getNode(getDataRequest->getPath());
			if (n == null) {
				throw NoNodeException(__FILE__, __LINE__);
			}
			PrepRequestProcessor::checkACL(zks, zks->getZKDatabase()->aclForNode(n.get()),
					ZooDefs::Perms::READ,
					request->authInfo);
			sp<Stat> stat = new Stat();
			sp<EA<byte> > b = zks->getZKDatabase()->getData(getDataRequest->getPath(), stat,
					getDataRequest->getWatch() ? cnxn : null);
			rsp = new GetDataResponse(b, stat);
			break;
		}
		case ZooDefs::OpCode::setWatches: {
			lastOp = "SETW";
			sp<SetWatches> setWatches = new SetWatches();
			// XXX We really should NOT need this!!!!
			request->request->rewind();
			ByteBufferInputStream::byteBuffer2Record(request->request.get(), setWatches.get());
			llong relativeZxid = setWatches->getRelativeZxid();
			zks->getZKDatabase()->setWatches(relativeZxid,
					setWatches->getDataWatches(),
					setWatches->getExistWatches(),
					setWatches->getChildWatches(), cnxn);
			break;
		}
		case ZooDefs::OpCode::getACL: {
			lastOp = "GETA";
			sp<GetACLRequest> getACLRequest = new GetACLRequest();
			ByteBufferInputStream::byteBuffer2Record(request->request.get(),
					getACLRequest.get());
			sp<Stat> stat = new Stat();
			auto acl =
					zks->getZKDatabase()->getACL(getACLRequest->getPath(), stat);
			rsp = new GetACLResponse(acl, stat);
			break;
		}
		case ZooDefs::OpCode::getChildren: {
			lastOp = "GETC";
			sp<GetChildrenRequest> getChildrenRequest = new GetChildrenRequest();
			ByteBufferInputStream::byteBuffer2Record(request->request.get(),
					getChildrenRequest.get());
			sp<DataNode> n = zks->getZKDatabase()->getNode(getChildrenRequest->getPath());
			if (n == null) {
				throw NoNodeException(__FILE__, __LINE__);
			}
			PrepRequestProcessor::checkACL(zks, zks->getZKDatabase()->aclForNode(n.get()),
					ZooDefs::Perms::READ,
					request->authInfo);
			auto children = zks->getZKDatabase()->getChildren(
					getChildrenRequest->getPath(), null, getChildrenRequest
							->getWatch() ? cnxn : null);
			rsp = new GetChildrenResponse(children);
			break;
		}
		case ZooDefs::OpCode::getChildren2: {
			lastOp = "GETC";
			sp<GetChildren2Request> getChildren2Request = new GetChildren2Request();
			ByteBufferInputStream::byteBuffer2Record(request->request.get(),
					getChildren2Request.get());
			sp<Stat> stat = new Stat();
			sp<DataNode> n = zks->getZKDatabase()->getNode(getChildren2Request->getPath());
			if (n == null) {
				throw NoNodeException(__FILE__, __LINE__);
			}
			PrepRequestProcessor::checkACL(zks, zks->getZKDatabase()->aclForNode(n.get()),
					ZooDefs::Perms::READ,
					request->authInfo);
			auto children = zks->getZKDatabase()->getChildren(
					getChildren2Request->getPath(), stat, getChildren2Request
							->getWatch() ? cnxn : null);
			rsp = new GetChildren2Response(children, stat);
			break;
		}
		}
	} catch (SessionMovedException e) {
		// session moved is a connection level error, we need to tear
		// down the connection otw ZOOKEEPER-710 might happen
		// ie client on slow follower starts to renew session, fails
		// before this completes, then tries the fast follower (leader)
		// and is successful, however the initial renew is then
		// successfully fwd/processed by the leader and as a result
		// the client and leader disagree on where the client is most
		// recently attached (and therefore invalid SESSION MOVED generated)
		cnxn->sendCloseSession();
		return;
	} catch (KeeperException& e) {
		err = e.code();
	} catch (EException& e) {
		// log at error level as we are returning a marshalling
		// error to the user
		LOG->error("Failed to process " + request->toString(), e);
		EString sb;// = new StringBuilder();
		auto bb = request->request;
		bb->rewind();
		while (bb->hasRemaining()) {
			sb.append(EInteger::toHexString(bb->get() & 0xff));
		}
		LOG->error("Dumping request buffer: 0x" + sb);
		err = KeeperException::Code::MARSHALLINGERROR;
	}

	llong lastZxid = zks->getZKDatabase()->getDataTreeLastProcessedZxid();
	sp<ReplyHeader> hdr =
		new ReplyHeader(request->cxid, lastZxid, err);

	zks->serverStats()->updateLatency(request->createTime);
	cnxn->updateStatsForResponse(request->cxid, lastZxid, lastOp,
			request->createTime, TimeUtils::currentElapsedTime());

	try {
		cnxn->sendResponse(hdr, rsp, "response");
		if (closeSession) {
			cnxn->sendCloseSession();
		}
	} catch (EIOException& e) {
		LOG->error("FIXMSG",e);
	}
}

} /* namespace ezk */
} /* namespace efc */
