/*
 * PrepRequestProcessor.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./PrepRequestProcessor.hh"

namespace efc {
namespace ezk {

sp<ELogger> PrepRequestProcessor::LOG = ELoggerManager::getLogger("PrepRequestProcessor");

boolean PrepRequestProcessor::skipACL = false;

DEFINE_STATIC_INITZZ_BEGIN(PrepRequestProcessor)
    ESystem::_initzz_();
    skipACL = EString("yes").equals(ESystem::getProperty("zookeeper.skipACL", "no"));
    if (skipACL) {
        LOG->info("zookeeper.skipACL==\"yes\", ACL checks will be skipped");
    }
DEFINE_STATIC_INITZZ_END

PrepRequestProcessor::PrepRequestProcessor(sp<ZooKeeperServer> zks,
		sp<RequestProcessor> nextProcessor) :
			ZooKeeperCriticalThread(EString("ProcessThread(sid:") + zks->getServerId() + " cport:"
			+ zks->getClientPort() + "):", zks->getZooKeeperServerListener()) {
	this->nextProcessor = nextProcessor;
	this->zks = zks;
}

void PrepRequestProcessor::run() {
	try {
		while (true) {
			sp<Request> request = submittedRequests.take();
			long traceMask = ZooTrace::CLIENT_REQUEST_TRACE_MASK;
			if (request->type == ZooDefs::OpCode::ping) {
				traceMask = ZooTrace::CLIENT_PING_TRACE_MASK;
			}
			if (LOG->isTraceEnabled()) {
				ZooTrace::logRequest(LOG, traceMask, 'P', request.get(), "");
			}
			if (Request::requestOfDeath == request) {
				break;
			}
			pRequest(request);
		}
	} catch (RequestProcessorException& e) {
		XidRolloverException* o = dynamic_cast<XidRolloverException*>(e.getCause());
		if (o) {
			LOG->info(e.getCause()->getMessage());
		}
		handleException(this->getName(), e);
	} catch (EException& e) {
		handleException(this->getName(), e);
	}
	LOG->info("PrepRequestProcessor exited loop!");
}

sp<ZooKeeperServer::ChangeRecord> PrepRequestProcessor::getRecordForPath(EString path) {
	sp<ZooKeeperServer::ChangeRecord> lastChange = null;
	SYNCBLOCK(&zks->outstandingChangesLock) {
		lastChange = zks->outstandingChangesForPath.get(&path);
		if (lastChange == null) {
			sp<DataNode> n = zks->getZKDatabase()->getNode(path);
			if (n != null) {
				/*
				Set<String> children;
				synchronized(n) {
					children = n.getChildren();
				}
				*/
				sp<EHashSet<EString*> > children = n->getChildren(); //already synced.
				lastChange = new ZooKeeperServer::ChangeRecord(-1, path, n->stat, children->size(),
						zks->getZKDatabase()->aclForNode(n.get()));
			}
		}
	}}
	if (lastChange == null || lastChange->stat == null) {
		throw NoNodeException(__FILE__, __LINE__, path);
	}
	return lastChange;
}

sp<ZooKeeperServer::ChangeRecord> PrepRequestProcessor::getOutstandingChange(EString path) {
	SYNCBLOCK(&zks->outstandingChangesLock) {
		return zks->outstandingChangesForPath.get(&path);
	}}
}

void PrepRequestProcessor::addChangeRecord(sp<ZooKeeperServer::ChangeRecord> c) {
	SYNCBLOCK(&zks->outstandingChangesLock) {
		zks->outstandingChanges.add(c);
		zks->outstandingChangesForPath.put(new EString(c->path), c);
	}}
}

sp<EHashMap<sp<EString>, sp<ZooKeeperServer::ChangeRecord> > > PrepRequestProcessor::getPendingChanges(
		MultiTransactionRecord* multiRequest) {
	sp<EHashMap<sp<EString>, sp<ZooKeeperServer::ChangeRecord> > > pendingChangeRecords =
			new EHashMap<sp<EString>, sp<ZooKeeperServer::ChangeRecord> >();

	auto iter = multiRequest->iterator();
	while (iter->hasNext()) {
		sp<Op> op = iter->next();
		EString path = op->getPath();
		sp<ZooKeeperServer::ChangeRecord> cr = getOutstandingChange(path);
		// only previously existing records need to be rolled back.
		if (cr != null) {
			pendingChangeRecords->put(new EString(path), cr);
		}

		/*
		 * ZOOKEEPER-1624 - We need to store for parent's ChangeRecord
		 * of the parent node of a request. So that if this is a
		 * sequential node creation request, rollbackPendingChanges()
		 * can restore previous parent's ChangeRecord correctly.
		 *
		 * Otherwise, sequential node name generation will be incorrect
		 * for a subsequent request.
		 */
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash == -1 || path.indexOf('\0') != -1) {
			continue;
		}
		EString parentPath = path.substring(0, lastSlash);
		sp<ZooKeeperServer::ChangeRecord> parentCr = getOutstandingChange(parentPath);
		if (parentCr != null) {
			pendingChangeRecords->put(new EString(parentPath), parentCr);
		}
	}

	return pendingChangeRecords;
}

void PrepRequestProcessor::rollbackPendingChanges(llong zxid,
		sp<EHashMap<sp<EString>, sp<ZooKeeperServer::ChangeRecord> > > pendingChangeRecords) {
	SYNCBLOCK(&zks->outstandingChangesLock) {
		// Grab a list iterator starting at the END of the list so we can iterate in reverse
		auto iter = zks->outstandingChanges.listIterator(zks->outstandingChanges.size());
		while (iter->hasPrevious()) {
			sp<ZooKeeperServer::ChangeRecord> c = iter->previous();
			if (c->zxid == zxid) {
				iter->remove();
				// Remove all outstanding changes for paths of this multi.
				// Previous records will be added back later.
				zks->outstandingChangesForPath.remove(&c->path);
			} else {
				break;
			}
		}

		// we don't need to roll back any records because there is nothing left.
		if (zks->outstandingChanges.isEmpty()) {
			return;
		}

		llong firstZxid = zks->outstandingChanges.getAt(0)->zxid;

		auto i2 = pendingChangeRecords->values()->iterator();
		while (i2->hasNext()) {
			auto c = i2->next();
			// Don't apply any prior change records less than firstZxid.
			// Note that previous outstanding requests might have been removed
			// once they are completed.
			if (c->zxid < firstZxid) {
				continue;
			}

			// add previously existing records back.
			zks->outstandingChangesForPath.put(new EString(c->path), c);
		}
	}}
}

void PrepRequestProcessor::pRequest2Txn(int type, llong zxid,
		sp<Request> request, ERecord* record,
		boolean deserialize)
        THROWS3(KeeperException, EIOException, RequestProcessorException)
{
	request->hdr = new TxnHeader(request->sessionId, request->cxid, zxid,
								TimeUtils::currentWallTime(), type);

	switch (type) {
		case ZooDefs::OpCode::create: {
			zks->sessionTracker->checkSession(request->sessionId, request->getOwner());
			CreateRequest* createRequest = dynamic_cast<CreateRequest*>(record);
			if (deserialize)
				ByteBufferInputStream::byteBuffer2Record(request->request.get(), createRequest);
			EString path = createRequest->getPath();
			int lastSlash = path.lastIndexOf('/');
			if (lastSlash == -1 || path.indexOf('\0') != -1) {
				LOG->info("Invalid path " + path + " with session 0x" +
						ELLong::toHexString(request->sessionId));
				throw BadArgumentsException(__FILE__, __LINE__, path.c_str());
			}
			sp<EList<sp<ACL> > > listACL = removeDuplicates(createRequest->getAcl());
			if (!fixupACL(request->authInfo, listACL)) {
				throw InvalidACLException(__FILE__, __LINE__, path);
			}
			EString parentPath = path.substring(0, lastSlash);
			sp<ZooKeeperServer::ChangeRecord> parentRecord = getRecordForPath(parentPath);

			checkACL(zks, parentRecord->acl, ZooDefs::Perms::CREATE,
					request->authInfo);
			int parentCVersion = parentRecord->stat->getCversion();
			CreateMode createMode =
				CreateMode::fromFlag(createRequest->getFlags());
			if (createMode.isSequential()) {
				//@see: path = path + String.format(Locale.ENGLISH, "%010d", parentCVersion);
				path = path + EString::formatOf("%010d", parentCVersion);
			}
			validatePath(path, request->sessionId);
			try {
				if (getRecordForPath(path) != null) {
					throw NodeExistsException(__FILE__, __LINE__, path);
				}
			} catch (NoNodeException& e) {
				// ignore this one
			}
			boolean ephemeralParent = parentRecord->stat->getEphemeralOwner() != 0;
			if (ephemeralParent) {
				throw NoChildrenForEphemeralsException(__FILE__, __LINE__, path);
			}
			int newCversion = parentRecord->stat->getCversion()+1;
			request->txn = new CreateTxn(path, createRequest->getData(),
					listACL,
					createMode.isEphemeral(), newCversion);
			sp<StatPersisted> s = new StatPersisted();
			if (createMode.isEphemeral()) {
				s->setEphemeralOwner(request->sessionId);
			}
			parentRecord = parentRecord->duplicate(request->hdr->getZxid());
			parentRecord->childCount++;
			parentRecord->stat->setCversion(newCversion);
			addChangeRecord(parentRecord);
			addChangeRecord(new ZooKeeperServer::ChangeRecord(request->hdr->getZxid(), path, s,
					0, listACL));
			break;
		}
		case ZooDefs::OpCode::delete_: {
			zks->sessionTracker->checkSession(request->sessionId, request->getOwner());
			DeleteRequest* deleteRequest = dynamic_cast<DeleteRequest*>(record);
			if (deserialize)
				ByteBufferInputStream::byteBuffer2Record(request->request.get(), deleteRequest);
			EString path = deleteRequest->getPath();
			int lastSlash = path.lastIndexOf('/');
			if (lastSlash == -1 || path.indexOf('\0') != -1
					|| zks->getZKDatabase()->isSpecialPath(path)) {
				throw BadArgumentsException(__FILE__, __LINE__, path);
			}
			EString parentPath = path.substring(0, lastSlash);
			sp<ZooKeeperServer::ChangeRecord> parentRecord = getRecordForPath(parentPath);
			sp<ZooKeeperServer::ChangeRecord> nodeRecord = getRecordForPath(path);
			checkACL(zks, parentRecord->acl, ZooDefs::Perms::DELETE,
					request->authInfo);
			int version = deleteRequest->getVersion();
			if (version != -1 && nodeRecord->stat->getVersion() != version) {
				throw BadVersionException(__FILE__, __LINE__, path);
			}
			if (nodeRecord->childCount > 0) {
				throw NotEmptyException(__FILE__, __LINE__, path);
			}
			request->txn = new DeleteTxn(path);
			parentRecord = parentRecord->duplicate(request->hdr->getZxid());
			parentRecord->childCount--;
			addChangeRecord(parentRecord);
			addChangeRecord(new ZooKeeperServer::ChangeRecord(request->hdr->getZxid(), path,
					null, -1, null));
			break;
		}
		case ZooDefs::OpCode::setData: {
			zks->sessionTracker->checkSession(request->sessionId, request->getOwner());
			SetDataRequest* setDataRequest = dynamic_cast<SetDataRequest*>(record);
			if (deserialize)
				ByteBufferInputStream::byteBuffer2Record(request->request.get(), setDataRequest);
			EString path = setDataRequest->getPath();
			validatePath(path, request->sessionId);
			sp<ZooKeeperServer::ChangeRecord> nodeRecord = getRecordForPath(path);
			checkACL(zks, nodeRecord->acl, ZooDefs::Perms::WRITE,
					request->authInfo);
			int version = setDataRequest->getVersion();
			int currentVersion = nodeRecord->stat->getVersion();
			if (version != -1 && version != currentVersion) {
				throw BadVersionException(__FILE__, __LINE__, path);
			}
			version = currentVersion + 1;
			request->txn = new SetDataTxn(path, setDataRequest->getData(), version);
			nodeRecord = nodeRecord->duplicate(request->hdr->getZxid());
			nodeRecord->stat->setVersion(version);
			addChangeRecord(nodeRecord);
			break;
		}
		case ZooDefs::OpCode::setACL: {
			zks->sessionTracker->checkSession(request->sessionId, request->getOwner());
			SetACLRequest* setAclRequest = dynamic_cast<SetACLRequest*>(record);
			if (deserialize)
				ByteBufferInputStream::byteBuffer2Record(request->request.get(), setAclRequest);
			EString path = setAclRequest->getPath();
			validatePath(path, request->sessionId);
			sp<EList<sp<ACL> > > listACL = removeDuplicates(setAclRequest->getAcl());
			if (!fixupACL(request->authInfo, listACL)) {
				throw InvalidACLException(__FILE__, __LINE__, path);
			}
			sp<ZooKeeperServer::ChangeRecord> nodeRecord = getRecordForPath(path);
			checkACL(zks, nodeRecord->acl, ZooDefs::Perms::ADMIN,
					request->authInfo);
			int version = setAclRequest->getVersion();
			int currentVersion = nodeRecord->stat->getAversion();
			if (version != -1 && version != currentVersion) {
				throw BadVersionException(__FILE__, __LINE__, path);
			}
			version = currentVersion + 1;
			request->txn = new SetACLTxn(path, listACL, version);
			nodeRecord = nodeRecord->duplicate(request->hdr->getZxid());
			nodeRecord->stat->setAversion(version);
			addChangeRecord(nodeRecord);
			break;
		}
		case ZooDefs::OpCode::createSession: {
			request->request->rewind();
			int to = request->request->getInt();
			request->txn = new CreateSessionTxn(to);
			request->request->rewind();
			zks->sessionTracker->addSession(request->sessionId, to);
			zks->setOwner(request->sessionId, request->getOwner());
			break;
		}
		case ZooDefs::OpCode::closeSession: {
			// We don't want to do this check since the session expiration thread
			// queues up this operation without being the session owner.
			// this request is the last of the session so it should be ok
			//zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
			sp<EHashSet<EString*> > es = zks->getZKDatabase()->getEphemerals(request->sessionId);
			SYNCBLOCK(&zks->outstandingChangesLock) {
				auto i1 = zks->outstandingChanges.iterator();
				while (i1->hasNext()) {
					auto c = i1->next();
					if (c->stat == null) {
						// Doing a delete
						es->remove(&c->path);
					} else if (c->stat->getEphemeralOwner() == request->sessionId) {
						es->add(new EString(c->path));
					}
				}
				auto i2 = es->iterator();
				while (i2->hasNext()) {
					EString* path2Delete = i2->next();
					addChangeRecord(new ZooKeeperServer::ChangeRecord(request->hdr->getZxid(),
							path2Delete, null, 0, null));
				}

				zks->sessionTracker->setSessionClosing(request->sessionId);
			}}

			LOG->info("Processed session termination for sessionid: 0x"
					+ ELLong::toHexString(request->sessionId));
			break;
		}
		case ZooDefs::OpCode::check: {
			zks->sessionTracker->checkSession(request->sessionId, request->getOwner());
			CheckVersionRequest* checkVersionRequest = dynamic_cast<CheckVersionRequest*>(record);
			if (deserialize)
				ByteBufferInputStream::byteBuffer2Record(request->request.get(), checkVersionRequest);
			EString path = checkVersionRequest->getPath();
			validatePath(path, request->sessionId);
			sp<ZooKeeperServer::ChangeRecord> nodeRecord = getRecordForPath(path);
			checkACL(zks, nodeRecord->acl, ZooDefs::Perms::READ,
					request->authInfo);
			int version = checkVersionRequest->getVersion();
			int currentVersion = nodeRecord->stat->getVersion();
			if (version != -1 && version != currentVersion) {
				throw BadVersionException(__FILE__, __LINE__, path);
			}
			version = currentVersion + 1;
			request->txn = new CheckVersionTxn(path, version);
			break;
		}
		default:
			LOG->error(EString::formatOf("Invalid OpCode: %d received by PrepRequestProcessor", type));
			break;
	}
}

void PrepRequestProcessor::pRequest(sp<Request> request) THROWS(RequestProcessorException) {
	// LOG.info("Prep>>> cxid = " + request.cxid + " type = " +
	// request.type + " id = 0x" + Long.toHexString(request.sessionId));
	request->hdr = null;
	request->txn = null;

	try {
		switch (request->type) {
			case ZooDefs::OpCode::create: {
			CreateRequest createRequest;// = new CreateRequest();
			pRequest2Txn(request->type, zks->getNextZxid(), request, &createRequest, true);
			break;
			}
		case ZooDefs::OpCode::delete_: {
			DeleteRequest deleteRequest;// = new DeleteRequest();
			pRequest2Txn(request->type, zks->getNextZxid(), request, &deleteRequest, true);
			break;
		}
		case ZooDefs::OpCode::setData: {
			SetDataRequest setDataRequest;// = new SetDataRequest();
			pRequest2Txn(request->type, zks->getNextZxid(), request, &setDataRequest, true);
			break;
		}
		case ZooDefs::OpCode::setACL: {
			SetACLRequest setAclRequest;// = new SetACLRequest();
			pRequest2Txn(request->type, zks->getNextZxid(), request, &setAclRequest, true);
			break;
		}
		case ZooDefs::OpCode::check: {
			CheckVersionRequest checkRequest;// = new CheckVersionRequest();
			pRequest2Txn(request->type, zks->getNextZxid(), request, &checkRequest, true);
			break;
		}
		case ZooDefs::OpCode::multi: {
			MultiTransactionRecord multiRequest;// = new MultiTransactionRecord();
			try {
				ByteBufferInputStream::byteBuffer2Record(request->request.get(), &multiRequest);
			} catch (EIOException& e) {
				request->hdr =  new TxnHeader(request->sessionId, request->cxid, zks->getNextZxid(),
						TimeUtils::currentWallTime(), ZooDefs::OpCode::multi);
				throw e;
			}
			sp<EList<Txn*> > txns = new EArrayList<Txn*>();
			//Each op in a multi-op must have the same zxid!
			llong zxid = zks->getNextZxid();
			sp<KeeperException> ke = null;

			//Store off current pending change records in case we need to rollback
			auto pendingChanges = getPendingChanges(&multiRequest);

			int index = 0;
			auto iter = multiRequest.iterator();
			while (iter->hasNext()) {
				sp<Op> op = iter->next();
				sp<ERecord> subrequest = op->toRequestRecord() ;

				/* If we've already failed one of the ops, don't bother
				 * trying the rest as we know it's going to fail and it
				 * would be confusing in the logfiles.
				 */
				if (ke != null) {
					request->hdr->setType(ZooDefs::OpCode::error);
					request->txn = new ErrorTxn(KeeperException::Code::RUNTIMEINCONSISTENCY);
				}

				/* Prep the request and convert to a Txn */
				else {
					try {
						pRequest2Txn(op->getType(), zxid, request, subrequest.get(), false);
					} catch (KeeperException& e) {
						ke = new KeeperException(e);
						request->hdr->setType(ZooDefs::OpCode::error);
						request->txn = new ErrorTxn(e.code());
						LOG->info("Got user-level KeeperException when processing "
								+ request->toString() + " aborting remaining multi ops."
								+ " Error Path:" + e.getPath()
								+ " Error:" + e.getMessage());

						request->setException(ke); //! e->ke

						/* Rollback change records from failed multi-op */
						rollbackPendingChanges(zxid, pendingChanges);
					}
				}

				//FIXME: I don't want to have to serialize it here and then
				//       immediately deserialize in next processor. But I'm
				//       not sure how else to get the txn stored into our list.
				EByteArrayOutputStream baos;// = new ByteArrayOutputStream();
				sp<EBinaryOutputArchive> boa = EBinaryOutputArchive::getArchive(&baos);
				request->txn->serialize(boa.get(), "request") ;
				sp<EIOByteBuffer> bb = EIOByteBuffer::wrap(baos.data(), baos.size());

				sp<EA<byte> > data = new EA<byte>((byte*)bb->current(), bb->limit());
				txns->add(new Txn(request->hdr->getType(), data));
				index++;
			}

			request->hdr = new TxnHeader(request->sessionId, request->cxid, zxid,
					TimeUtils::currentWallTime(), request->type);
			request->txn = new MultiTxn(txns);

			break;
		}
		//create/close session don't require request record
		case ZooDefs::OpCode::createSession:
		case ZooDefs::OpCode::closeSession:
			pRequest2Txn(request->type, zks->getNextZxid(), request, null, true);
			break;

		//All the rest don't need to create a Txn - just verify session
		case ZooDefs::OpCode::sync:
		case ZooDefs::OpCode::exists:
		case ZooDefs::OpCode::getData:
		case ZooDefs::OpCode::getACL:
		case ZooDefs::OpCode::getChildren:
		case ZooDefs::OpCode::getChildren2:
		case ZooDefs::OpCode::ping:
		case ZooDefs::OpCode::setWatches:
			zks->sessionTracker->checkSession(request->sessionId,
					request->getOwner());
			break;
		default:
			LOG->warn(EString("unknown type ") + request->type);
			break;
		}
	} catch (KeeperException& e) {
		if (request->hdr != null) {
			request->hdr->setType(ZooDefs::OpCode::error);
			request->txn = new ErrorTxn(e.code());
		}
		LOG->info("Got user-level KeeperException when processing "
				+ request->toString()
				+ " Error Path:" + e.getPath()
				+ " Error:" + e.getMessage());
		request->setException(new KeeperException(e));
	} catch (EException& e) {
		// log at error level as we are returning a marshalling
		// error to the user
		LOG->error("Failed to process " + request->toString(), e);

		EString sb;// = new StringBuilder();
		auto bb = request->request;
		if(bb != null){
			bb->rewind();
			while (bb->hasRemaining()) {
				sb.append(EInteger::toHexString(bb->get() & 0xff));
			}
		} else {
			sb.append("request buffer is null");
		}

		LOG->error("Dumping request buffer: 0x" + sb.toString());
		if (request->hdr != null) {
			request->hdr->setType(ZooDefs::OpCode::error);
			request->txn = new ErrorTxn(KeeperException::Code::MARSHALLINGERROR);
		}
	}
	request->zxid = zks->getZxid();
	nextProcessor->processRequest(request);
}

void PrepRequestProcessor::processRequest(sp<Request> request) {
	// request.addRQRec(">prep="+zks.outstandingChanges.size());
	submittedRequests.add(request);
}

void PrepRequestProcessor::shutdown() {
	LOG->info("Shutting down");
	submittedRequests.clear();
	submittedRequests.add(Request::requestOfDeath);
	nextProcessor->shutdown();
}

void PrepRequestProcessor::checkACL(sp<ZooKeeperServer> zks, sp<EList<sp<ACL> > > acl, int perm,
		sp<EList<sp<Id> > > ids) THROWS(NoAuthException) {
	if (skipACL) {
		return;
	}
	if (acl == null || acl->size() == 0) {
		return;
	}
	auto it1 = ids->iterator();
	while (it1->hasNext()) {
		sp<Id> authId = it1->next();
		if (authId->getScheme().equals("super")) {
			return;
		}
	}
	auto it2 = acl->iterator();
	while (it2->hasNext()) {
		sp<ACL> a = it2->next();
		sp<Id> id = a->getId();
		if ((a->getPerms() & perm) != 0) {
			if (id->getScheme().equals("world")
					&& id->getId().equals("anyone")) {
				return;
			}
			AuthenticationProvider* ap = ProviderRegistry::getProvider(id->getScheme());
			if (ap != null) {
				auto it = ids->iterator();
				while (it->hasNext()) {
					sp<Id> authId = it->next();
					if (authId->getScheme().equals(id->getScheme().c_str())
							&& ap->matches(authId->getId(), id->getId())) {
						return;
					}
				}
			}
		}
	}
	throw NoAuthException(__FILE__, __LINE__);
}

void PrepRequestProcessor::validatePath(EString path, llong sessionId) THROWS(BadArgumentsException) {
	try {
		PathUtils::validatePath(path);
	} catch(EIllegalArgumentException& ie) {
		LOG->info("Invalid path " +  path + " with session 0x" + ELLong::toHexString(sessionId) +
				", reason: " + ie.getMessage());
		throw BadArgumentsException(__FILE__, __LINE__, path);
	}
}

boolean PrepRequestProcessor::fixupACL(sp<EList<sp<Id> > > authInfo, sp<EList<sp<ACL> > > acl) {
	if (skipACL) {
		return true;
	}
	if (acl == null || acl->size() == 0) {
		return false;
	}

	auto it = acl->iterator();
	sp<ELinkedList<sp<ACL> > > toAdd = null;
	while (it->hasNext()) {
		sp<ACL> a = it->next();
		sp<Id> id = a->getId();
		if (id->getScheme().equals("world") && id->getId().equals("anyone")) {
			// wide open
		} else if (id->getScheme().equals("auth")) {
			// This is the "auth" id, so we have to expand it to the
			// authenticated ids of the requestor
			int perms = a->getPerms();
			it->remove();
			if (toAdd == null) {
				toAdd = new ELinkedList<sp<ACL> >(); //!
			}
			boolean authIdValid = false;
			auto it2 = authInfo->iterator();
			while (it2->hasNext()) {
				sp<Id> cid = it2->next();
				AuthenticationProvider* ap =
					ProviderRegistry::getProvider(cid->getScheme());
				if (ap == null) {
					LOG->error("Missing AuthenticationProvider for "
							+ cid->getScheme());
				} else if (ap->isAuthenticated()) {
					authIdValid = true;
					toAdd->add(new ACL(perms, cid));
				}
			}
			if (!authIdValid) {
				return false;
			}
		} else {
			AuthenticationProvider* ap = ProviderRegistry::getProvider(id->getScheme());
			if (ap == null) {
				return false;
			}
			if (!ap->isValid(id->getId())) {
				return false;
			}
		}
	}
	if (toAdd != null) {
		auto it = toAdd->iterator();
		while (it->hasNext()) {
			sp<ACL> a = it->next();
			acl->add(a);
		}
	}
	return acl->size() > 0;
}

sp<EList<sp<ACL> > > PrepRequestProcessor::removeDuplicates(sp<EList<sp<ACL> > > acl) {
	sp<EList<sp<ACL> > > retval = new EArrayList<sp<ACL> >();
	auto it = acl->iterator();
	while (it->hasNext()) {
		sp<ACL> a = it->next();
		if (retval->contains(a.get()) == false) {
			//@see: retval.add(a);
			retval->add(a);
		}
	}
	return retval;
}

} /* namespace ezk */
} /* namespace efc */
