/*
 * DataTree.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./DataTree.hh"

namespace efc {
namespace ezk {

sp<ELogger> DataTree::LOG = ELoggerManager::getLogger("DataTree");

DataTree::DataTree() {
	root = new DataNode(null, new EA<byte>(0), -1L, new StatPersisted());
	procDataNode = new DataNode(root, new EA<byte>(0), -1L, new StatPersisted());
	quotaDataNode = new DataNode(procDataNode, new EA<byte>(0), -1L, new StatPersisted());

	/* Rather than fight it, let root have an alias */
	nodes.put(new EString(""), root);
	nodes.put(new EString(rootZookeeper), root);

	/** add the proc node and quota node */
	root->addChild(procChildZookeeper);
	nodes.put(new EString(procZookeeper), procDataNode);

	procDataNode->addChild(quotaChildZookeeper);
	nodes.put(new EString(quotaZookeeper), quotaDataNode);

	lastProcessedZxid = 0;

	scount = 0;
	initialized = false;
}

sp<EHashSet<EString*> > DataTree::getEphemerals(llong sessionId) {
	auto retv = ephemerals.get(sessionId);
	if (retv == null) {
		return new HashSetSync<EString*>();
	}
	sp<EHashSet<EString*> > cloned = null;
	/*
	synchronized (retv) {
		cloned = (HashSet<String>) retv.clone();
	}
	*/
	SYNCHRONIZED(this) {
		cloned = new HashSetSync<EString*>();
		auto iter = retv->iterator();
		while (iter->hasNext()) {
			EString* s = iter->next();
			cloned->add(new EString(s));
		}
	}}
	return cloned;
}

EConcurrentHashMap<llong, DataTree::HashSetSync<EString*> >* DataTree::getEphemeralsMap() {
	return &ephemerals;
}

ESet<llong>* DataTree::getSessions() {
	return ephemerals.keySet();
}

void DataTree::addDataNode(EString path, sp<DataNode> node) {
	nodes.put(new EString(path), node);
}

sp<DataNode> DataTree::getNode(EString path) {
	return nodes.get(&path);
}

int DataTree::getNodeCount() {
	return nodes.size();
}

int DataTree::getWatchCount() {
	return dataWatches.size() + childWatches.size();
}

int DataTree::getEphemeralsCount() {
	int result = 0;
	auto iter = ephemerals.values()->iterator();
	while (iter->hasNext()) {
		sp<EHashSet<EString*> > set = iter->next();
		result += set->size();
	}
	return result;
}

llong DataTree::approximateDataSize() {
	llong result = 0;
	auto iter = nodes.entrySet()->iterator();
	while (iter->hasNext()) {
		auto entry = iter->next();
		sp<DataNode> value = entry->getValue();
		SYNCHRONIZED(value) {
			result += entry->getKey()->length();
			result += (value->data == null ? 0
					: value->data->length());
		}}
	}
	return result;
}

boolean DataTree::isSpecialPath(EString path) {
	if (path.equals(rootZookeeper) || path.equals(procZookeeper)
			|| path.equals(quotaZookeeper)) {
		return true;
	}
	return false;
}

void DataTree::updateCount(EString lastPrefix, int diff) {
	EString statNode = Quotas::statPath(lastPrefix);
	sp<DataNode> node = nodes.get(&statNode);
	sp<StatsTrack> updatedStat = null;
	if (node == null) {
		// should not happen
		LOG->error("Missing count node for stat " + statNode);
		return;
	}
	SYNCHRONIZED(node) {
		updatedStat = new StatsTrack(new EString((const char*)node->data->address(), 0, node->data->length()));
		updatedStat->setCount(updatedStat->getCount() + diff);
		EString s = updatedStat.toString();
		node->data = new EA<byte>((byte*)s.c_str(), s.length());
	}}
	// now check if the counts match the quota
	EString quotaNode = Quotas::quotaPath(lastPrefix);
	node = nodes.get(&quotaNode);
	sp<StatsTrack> thisStats = null;
	if (node == null) {
		// should not happen
		LOG->error("Missing count node for quota " + quotaNode);
		return;
	}
	SYNCHRONIZED(node) {
		thisStats = new StatsTrack(new EString((const char*)node->data->address(), 0, node->data->length()));
	}}
	if (thisStats->getCount() > -1 && (thisStats->getCount() < updatedStat->getCount())) {
		LOG->warn("Quota exceeded: " + lastPrefix + " count="
				+ updatedStat->getCount() + " limit="
				+ thisStats->getCount());
	}
}

void DataTree::updateBytes(EString lastPrefix, llong diff) {
	EString statNode = Quotas::statPath(lastPrefix);
	sp<DataNode> node = nodes.get(&statNode);
	if (node == null) {
		// should never be null but just to make
		// findbugs happy
		LOG->error("Missing stat node for bytes " + statNode);
		return;
	}
	sp<StatsTrack> updatedStat = null;
	SYNCHRONIZED(node) {
		updatedStat = new StatsTrack(new EString((const char*)node->data->address(), 0, node->data->length()));
		updatedStat->setBytes(updatedStat->getBytes() + diff);
		EString s = updatedStat.toString();
		node->data = new EA<byte>((byte*)s.c_str(), s.length());
	}}
	// now check if the bytes match the quota
	EString quotaNode = Quotas::quotaPath(lastPrefix);
	node = nodes.get(&quotaNode);
	if (node == null) {
		// should never be null but just to make
		// findbugs happy
		LOG->error("Missing quota node for bytes " + quotaNode);
		return;
	}
	sp<StatsTrack> thisStats = null;
	SYNCHRONIZED(node) {
		thisStats = new StatsTrack(new EString((const char*)node->data->address(), 0, node->data->length()));
	}}
	if (thisStats->getBytes() > -1 && (thisStats->getBytes() < updatedStat->getBytes())) {
		LOG->warn("Quota exceeded: " + lastPrefix + " bytes="
				+ updatedStat->getBytes() + " limit="
				+ thisStats->getBytes());
	}
}

EString DataTree::createNode(EString path, sp<EA<byte> > data, sp<EList<sp<ACL> > > acl,
		llong ephemeralOwner, int parentCVersion, llong zxid, llong time)
		THROWS2(NoNodeException, NodeExistsException) {
	int lastSlash = path.lastIndexOf('/');
	EString parentName = path.substring(0, lastSlash);
	EString childName = path.substring(lastSlash + 1);
	sp<StatPersisted> stat = new StatPersisted();
	stat->setCtime(time);
	stat->setMtime(time);
	stat->setCzxid(zxid);
	stat->setMzxid(zxid);
	stat->setPzxid(zxid);
	stat->setVersion(0);
	stat->setAversion(0);
	stat->setEphemeralOwner(ephemeralOwner);
	sp<DataNode> parent = nodes.get(&parentName);
	if (parent == null) {
		throw NoNodeException(__FILE__, __LINE__);
	}
	SYNCHRONIZED(parent) {
		sp<EHashSet<EString*> > children = parent->getChildren();
		if (children->contains(&childName)) {
			throw NodeExistsException(__FILE__, __LINE__);
		}

		if (parentCVersion == -1) {
			parentCVersion = parent->stat->getCversion();
			parentCVersion++;
		}
		parent->stat->setCversion(parentCVersion);
		parent->stat->setPzxid(zxid);
		llong longval = aclCache.convertAcls(acl);
		sp<DataNode> child = new DataNode(parent, data, longval, stat);
		parent->addChild(childName);
		nodes.put(new EString(path), child);
		if (ephemeralOwner != 0) {
			auto list = ephemerals.get(ephemeralOwner);
			if (list == null) {
				list = new HashSetSync<EString*>();
				ephemerals.put(ephemeralOwner, list);
			}
			SYNCHRONIZED (list) {
				list->add(new EString(path));
			}}
		}
	}}
	// now check if its one of the zookeeper node child
	if (parentName.startsWith(quotaZookeeper)) {
		// now check if its the limit node
		if (childName.equals(Quotas::limitNode)) {
			// this is the limit node
			// get the parent and add it to the trie
			pTrie.addPath(parentName.substring(strlen(quotaZookeeper)));
		}
		if (childName.equals(Quotas::statNode)) {
			updateQuotaForPath(parentName
					.substring(strlen(quotaZookeeper)));
		}
	}
	// also check to update the quotas for this node
	EString lastPrefix;
	//@see: if((lastPrefix = getMaxPrefixWithQuota(path)) != null) {
	if(!(lastPrefix = getMaxPrefixWithQuota(path)).isEmpty()) {
		// ok we have some match and need to update
		updateCount(lastPrefix, 1);
		updateBytes(lastPrefix, data == null ? 0 : data->length());
	}
	dataWatches.triggerWatch(path, Watcher::Event::EventType::NodeCreated);
	childWatches.triggerWatch(parentName.equals("") ? "/" : parentName,
			Watcher::Event::EventType::NodeChildrenChanged);
	return path;
}

void DataTree::deleteNode(EString path, llong zxid) THROWS(NoNodeException) {
	int lastSlash = path.lastIndexOf('/');
	EString parentName = path.substring(0, lastSlash);
	EString childName = path.substring(lastSlash + 1);
	sp<DataNode> node = nodes.get(&path);
	if (node == null) {
		throw NoNodeException(__FILE__, __LINE__);
	}
	nodes.remove(&path);
	SYNCHRONIZED(node) {
		aclCache.removeUsage(node->acl);
	}}
	sp<DataNode> parent = nodes.get(&parentName);
	if (parent == null) {
		throw NoNodeException(__FILE__, __LINE__);
	}
	SYNCHRONIZED (parent) {
		parent->removeChild(&childName);
		parent->stat->setPzxid(zxid);
		llong eowner = node->stat->getEphemeralOwner();
		if (eowner != 0) {
			auto nodes = ephemerals.get(eowner);
			if (nodes != null) {
				SYNCHRONIZED (nodes) {
					nodes->remove(&path);
				}}
			}
		}
		node->parent = null;
	}}
	if (parentName.startsWith(procZookeeper)) {
		// delete the node in the trie.
		if (childName.equals(Quotas::limitNode)) {
			// we need to update the trie
			// as well
			pTrie.deletePath(parentName.substring(strlen(quotaZookeeper)));
		}
	}

	// also check to update the quotas for this node
	EString lastPrefix;
	//@see: if((lastPrefix = getMaxPrefixWithQuota(path)) != null) {
	if(!(lastPrefix = getMaxPrefixWithQuota(path)).isEmpty()) {
		// ok we have some match and need to update
		updateCount(lastPrefix, -1);
		int bytes = 0;
		SYNCHRONIZED (node) {
			bytes = (node->data == null ? 0 : -(node->data->length()));
		}}
		updateBytes(lastPrefix, bytes);
	}
	if (LOG->isTraceEnabled()) {
		ZooTrace::logTraceMessage(LOG, ZooTrace::EVENT_DELIVERY_TRACE_MASK,
				"dataWatches.triggerWatch " + path);
		ZooTrace::logTraceMessage(LOG, ZooTrace::EVENT_DELIVERY_TRACE_MASK,
				"childWatches.triggerWatch " + parentName);
	}
	auto processed = dataWatches.triggerWatch(path,
			Watcher::Event::EventType::NodeDeleted);
	childWatches.triggerWatch(path, Watcher::Event::EventType::NodeDeleted, processed);
	childWatches.triggerWatch(parentName.equals("") ? "/" : parentName,
			Watcher::Event::EventType::NodeChildrenChanged);
}

sp<Stat> DataTree::setData(EString path, sp<EA<byte> > data, int version, llong zxid,
		llong time) THROWS(NoNodeException) {
	sp<Stat> s = new Stat();
	sp<DataNode> n = nodes.get(&path);
	if (n == null) {
		throw NoNodeException(__FILE__, __LINE__);
	}
	sp<EA<byte> > lastdata = null;
	SYNCHRONIZED (n) {
		lastdata = n->data;
		n->data = data;
		n->stat->setMtime(time);
		n->stat->setMzxid(zxid);
		n->stat->setVersion(version);
		n->copyStat(s);
	}}
	// now update if the path is in a quota subtree.
	EString lastPrefix;
	//@see: if((lastPrefix = getMaxPrefixWithQuota(path)) != null) {
	if (!(lastPrefix = getMaxPrefixWithQuota(path)).isEmpty()) {
	  this->updateBytes(lastPrefix, (data == null ? 0 : data->length())
		  - (lastdata == null ? 0 : lastdata->length()));
	}
	dataWatches.triggerWatch(path, Watcher::Event::EventType::NodeDataChanged);
	return s;
}

EString DataTree::getMaxPrefixWithQuota(EString path) {
	// do nothing for the root.
	// we are not keeping a quota on the zookeeper
	// root node for now.
	EString lastPrefix = pTrie.findMaxPrefix(path);

	if (!lastPrefix.equals(rootZookeeper) && !(lastPrefix.equals(""))) {
		return lastPrefix;
	}
	else {
		return null;
	}
}

sp<EA<byte> > DataTree::getData(EString path, sp<Stat> stat, sp<Watcher> watcher)
		THROWS(NoNodeException) {
	sp<DataNode> n = nodes.get(&path);
	if (n == null) {
		throw NoNodeException(__FILE__, __LINE__);
	}
	SYNCHRONIZED (n) {
		n->copyStat(stat);
		if (watcher != null) {
			dataWatches.addWatch(path, watcher);
		}
		return n->data;
	}}
}

sp<Stat> DataTree::statNode(EString path, sp<Watcher> watcher)
		THROWS(NoNodeException) {
	sp<Stat> stat = new Stat();
	sp<DataNode> n = nodes.get(&path);
	if (watcher != null) {
		dataWatches.addWatch(path, watcher);
	}
	if (n == null) {
		throw NoNodeException(__FILE__, __LINE__);
	}
	SYNCHRONIZED (n) {
		n->copyStat(stat);
		return stat;
	}}
}

sp<EList<EString*> > DataTree::getChildren(EString path, sp<Stat> stat, sp<Watcher> watcher)
		THROWS(NoNodeException) {
	sp<DataNode> n = nodes.get(&path);
	if (n == null) {
		throw NoNodeException(__FILE__, __LINE__);
	}
	SYNCHRONIZED (n) {
		if (stat != null) {
			n->copyStat(stat);
		}
		//@see: EList<EString*>* children = new EArrayList<EString*>(n->getChildren());
		sp<EList<EString*> > list = new EArrayList<EString*>();
		sp<EHashSet<EString*> > children = n->getChildren();
		auto iter = children->iterator();
		while (iter->hasNext()) {
			EString* s = iter->next();
			list->add(new EString(s));
		}

		if (watcher != null) {
			childWatches.addWatch(path, watcher);
		}

		return list;
	}}
}

sp<Stat> DataTree::setACL(EString path, sp<EList<sp<ACL> > > acl, int version)
		THROWS(NoNodeException) {
	sp<Stat> stat = new Stat();
	sp<DataNode> n = nodes.get(&path);
	if (n == null) {
		throw NoNodeException(__FILE__, __LINE__);
	}
	SYNCHRONIZED (n) {
		aclCache.removeUsage(n->acl);
		n->stat->setAversion(version);
		n->acl = aclCache.convertAcls(acl);
		n->copyStat(stat);
		return stat;
	}}
}

sp<EList<sp<ACL> > > DataTree::getACL(EString path, sp<Stat> stat)
		THROWS(NoNodeException) {
	sp<DataNode> n = nodes.get(&path);
	if (n == null) {
		throw NoNodeException(__FILE__, __LINE__);
	}
	sp<EList<sp<ACL> > > clone = new EArrayList<sp<ACL> >();
	SYNCHRONIZED (n) {
		n->copyStat(stat);
		//@see: return new ArrayList<ACL>(aclCache.convertLong(n.acl));
		auto aclList = aclCache.convertLong(n->acl);
		for (int i=0; i<aclList->size(); i++) {
			clone->add(aclList->getAt(i));
		}
	}}
	return clone;
}

sp<EList<sp<ACL> > > DataTree::getACL(DataNode* node) {
	SYNCHRONIZED (node) {
		return aclCache.convertLong(node->acl);
	}}
}

llong DataTree::getACL(DataNodeV1* oldDataNode) {
	SYNCHRONIZED (oldDataNode) {
		return aclCache.convertAcls(oldDataNode->acl);
	}}
}

int DataTree::aclCacheSize() {
	return aclCache.size();
}

sp<DataTree::ProcessTxnResult> DataTree::processTxn(TxnHeader* header, ERecord* txn)
{
	sp<ProcessTxnResult> rc = new ProcessTxnResult();

	try {
		rc->clientId = header->getClientId();
		rc->cxid = header->getCxid();
		rc->zxid = header->getZxid();
		rc->type = header->getType();
		rc->err = 0;
		rc->multiResult = null;
		switch (header->getType()) {
			case ZooDefs::OpCode::create: {
				CreateTxn* createTxn = dynamic_cast<CreateTxn*>(txn);
				rc->path = createTxn->getPath();
				createNode(
						createTxn->getPath(),
						createTxn->getData(),
						createTxn->getAcl(),
						createTxn->getEphemeral() ? header->getClientId() : 0,
								createTxn->getParentCVersion(),
						header->getZxid(), header->getTime());
				break;
			}
			case ZooDefs::OpCode::delete_: {
				DeleteTxn* deleteTxn = dynamic_cast<DeleteTxn*>(txn);
				rc->path = deleteTxn->getPath();
				deleteNode(deleteTxn->getPath(), header->getZxid());
				break;
			}
			case ZooDefs::OpCode::setData: {
				SetDataTxn* setDataTxn = dynamic_cast<SetDataTxn*>(txn);
				rc->path = setDataTxn->getPath();
				rc->stat = setData(setDataTxn->getPath(), setDataTxn->getData(),
							setDataTxn->getVersion(), header->getZxid(),
							header->getTime());
				break;
			}
			case ZooDefs::OpCode::setACL: {
				SetACLTxn* setACLTxn = dynamic_cast<SetACLTxn*>(txn);
				rc->path = setACLTxn->getPath();
				rc->stat = setACL(setACLTxn->getPath(), setACLTxn->getAcl(),
						setACLTxn->getVersion());
				break;
			}
			case ZooDefs::OpCode::closeSession:
				killSession(header->getClientId(), header->getZxid());
				break;
			case ZooDefs::OpCode::error: {
				ErrorTxn* errTxn = dynamic_cast<ErrorTxn*>(txn);
				rc->err = errTxn->getErr();
				break;
			}
			case ZooDefs::OpCode::check: {
				CheckVersionTxn* checkTxn = dynamic_cast<CheckVersionTxn*>(txn);
				rc->path = checkTxn->getPath();
				break;
			}
			case ZooDefs::OpCode::multi: {
				MultiTxn* multiTxn = dynamic_cast<MultiTxn*>(txn) ;
				sp<EList<Txn*> > txns = multiTxn->getTxns();
				rc->multiResult = new EArrayList<sp<ProcessTxnResult> >();
				boolean failed = false;
				auto iter = txns->iterator();
				while (iter->hasNext()) {
					Txn* subtxn = dynamic_cast<Txn*>(iter->next());
					if (subtxn->getType() == ZooDefs::OpCode::error) {
						failed = true;
						break;
					}
				}

				boolean post_failed = false;
				iter = txns->iterator();
				while (iter->hasNext()) {
					Txn* subtxn = dynamic_cast<Txn*>(iter->next());
					sp<EIOByteBuffer> bb = EIOByteBuffer::wrap(subtxn->getData()->address(), subtxn->getData()->length());
					sp<ERecord> record = null;
					switch (subtxn->getType()) {
						case ZooDefs::OpCode::create:
							record = new CreateTxn();
							break;
						case ZooDefs::OpCode::delete_:
							record = new DeleteTxn();
							break;
						case ZooDefs::OpCode::setData:
							record = new SetDataTxn();
							break;
						case ZooDefs::OpCode::error:
							record = new ErrorTxn();
							post_failed = true;
							break;
						case ZooDefs::OpCode::check:
							record = new CheckVersionTxn();
							break;
						default:
							throw EIOException(__FILE__, __LINE__, (EString("Invalid type of op: ") + subtxn->getType()).c_str());
					}
					ES_ASSERT(record != null);

					ByteBufferInputStream::byteBuffer2Record(bb.get(), record.get());

					if (failed && subtxn->getType() != ZooDefs::OpCode::error){
						int ec = post_failed ? KeeperException::Code::RUNTIMEINCONSISTENCY
											 : KeeperException::Code::OK;

						subtxn->setType(ZooDefs::OpCode::error);
						record = new ErrorTxn(ec);
					}

					if (failed) {
						ES_ASSERT(subtxn->getType() == ZooDefs::OpCode::error) ;
					}

					TxnHeader subHdr(header->getClientId(), header->getCxid(),
							header->getZxid(), header->getTime(),
													 subtxn->getType());
					sp<ProcessTxnResult> subRc = processTxn(&subHdr, record.get());
					rc->multiResult->add(subRc);
					if (subRc->err != 0 && rc->err == 0) {
						rc->err = subRc->err ;
					}
				}
				break;
			}
		}
	} catch (KeeperException& e) {
		if (LOG->isDebugEnabled()) {
			LOG->debug("Failed: " + header->toString() + ":" + txn->toString(), e);
		}
		rc->err = e.code();
	} catch (EIOException& e) {
		if (LOG->isDebugEnabled()) {
			LOG->debug("Failed: " + header->toString() + ":" + txn->toString(), e);
		}
	}
	/*
	 * A snapshot might be in progress while we are modifying the data
	 * tree. If we set lastProcessedZxid prior to making corresponding
	 * change to the tree, then the zxid associated with the snapshot
	 * file will be ahead of its contents. Thus, while restoring from
	 * the snapshot, the restore method will not apply the transaction
	 * for zxid associated with the snapshot file, since the restore
	 * method assumes that transaction to be present in the snapshot.
	 *
	 * To avoid this, we first apply the transaction and then modify
	 * lastProcessedZxid.  During restore, we correctly handle the
	 * case where the snapshot contains data ahead of the zxid associated
	 * with the file.
	 */
	if (rc->zxid > lastProcessedZxid) {
		lastProcessedZxid = rc->zxid;
	}

	/*
	 * Snapshots are taken lazily. It can happen that the child
	 * znodes of a parent are created after the parent
	 * is serialized. Therefore, while replaying logs during restore, a
	 * create might fail because the node was already
	 * created.
	 *
	 * After seeing this failure, we should increment
	 * the cversion of the parent znode since the parent was serialized
	 * before its children.
	 *
	 * Note, such failures on DT should be seen only during
	 * restore.
	 */
	if (header->getType() == ZooDefs::OpCode::create &&
			rc->err == KeeperException::Code::NODEEXISTS) {
		LOG->debug(EString("Adjusting parent cversion for Txn: ") + header->getType() +
				" path:" + rc->path + " err: " + rc->err);
		int lastSlash = rc->path.lastIndexOf('/');
		EString parentName = rc->path.substring(0, lastSlash);
		CreateTxn* cTxn = dynamic_cast<CreateTxn*>(txn);
		try {
			setCversionPzxid(parentName, cTxn->getParentCVersion(),
					header->getZxid());
		} catch (NoNodeException& e) {
			LOG->error("Failed to set parent cversion for: " +
				  parentName, e);
			rc->err = e.code();
		}
	} else if (rc->err != KeeperException::Code::OK) {
		LOG->debug(EString("Ignoring processTxn failure hdr: ") + header->getType() +
			  " : error: " + rc->err);
	}
	return rc;
}

void DataTree::killSession(llong session, llong zxid) {
	// the list is already removed from the ephemerals
	// so we do not have to worry about synchronizing on
	// the list. This is only called from FinalRequestProcessor
	// so there is no need for synchronization. The list is not
	// changed here. Only create and delete change the list which
	// are again called from FinalRequestProcessor in sequence.
	sp<HashSetSync<EString*> > list = ephemerals.remove(session);
	if (list != null) {
		auto iter = list->iterator();
		while (iter->hasNext()) {
			EString* path = iter->next();
			try {
				deleteNode(path, zxid);
				if (LOG->isDebugEnabled()) {
					LOG->debug(EString("Deleting ephemeral node ") + path->c_str()
									+ " for session 0x"
									+ ELLong::toHexString(session));
				}
			} catch (NoNodeException& e) {
				LOG->warn(EString("Ignoring NoNodeException for path ") + path->c_str()
						+ " while removing ephemeral for dead session 0x"
						+ ELLong::toHexString(session));
			}
		}
	}
}

void DataTree::serializeNode(EOutputArchive* oa, EString& path) THROWS(EIOException) {
	EString pathString = path.toString();
	sp<DataNode> node = getNode(pathString);
	if (node == null) {
		return;
	}
	/* @see:
	String children[] = null;
	DataNode nodeCopy;
	synchronized (node) {
		scount++;
		StatPersisted statCopy = new StatPersisted();
		copyStatPersisted(node.stat, statCopy);
		//we do not need to make a copy of node.data because the contents
		//are never changed
		nodeCopy = new DataNode(node.parent, node.data, node.acl, statCopy);
		Set<String> childs = node.getChildren();
		children = childs.toArray(new String[childs.size()]);
	}
	*/
	sp<EA<EString*> > children;
	sp<DataNode> nodeCopy;
	SYNCHRONIZED(node) {
		scount++;
		sp<StatPersisted> statCopy = new StatPersisted();
		copyStatPersisted(node->stat, statCopy);
		//we do not need to make a copy of node.data because the contents
		//are never changed
		nodeCopy = new DataNode(node->parent, node->data, node->acl, statCopy);
		auto childs = node->getChildren();
		children = new EA<EString*>(childs->size());
		auto iter = childs->iterator();
		int i = 0;
		while (iter->hasNext()) {
			(*children)[i++] = new EString(iter->next());
		}
	}}

	oa->writeString(pathString, "path");
	oa->writeRecord(nodeCopy.get(), "node");
	path.append('/');
	int off = path.length();
	for (int i=0; i<children->length(); i++) {
		EString* child = (*children)[i];
		// since this is single buffer being resused
		// we need
		// to truncate the previous bytes of string.
		path.erase(off, EInteger::MAX_VALUE);
		path.append(child);
		serializeNode(oa, path);
	}
}

void DataTree::serialize(EOutputArchive* oa, EString tag) THROWS(EIOException) {
	scount = 0;
	aclCache.serialize(oa);
	EString path("");
	serializeNode(oa, path /*new StringBuilder("")*/);
	// / marks end of stream
	// we need to check if clear had been called in between the snapshot.
	if (root != null) {
		oa->writeString("/", "path");
	}
}

void DataTree::deserialize(EInputArchive* ia, EString tag) THROWS(EIOException) {
	aclCache.deserialize(ia);
	nodes.clear();
	pTrie.clear();
	EString path = ia->readString("path");
	while (!path.equals("/")) {
		sp<DataNode> node = new DataNode();
		ia->readRecord(node.get(), "node");
		nodes.put(new EString(path), node);
		SYNCHRONIZED(node) {
			aclCache.addUsage(node->acl);
		}}
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash == -1) {
			root = node;
		} else {
			EString parentPath = path.substring(0, lastSlash);
			node->parent = nodes.get(&parentPath);
			if (node->parent == null) {
				throw EIOException(__FILE__, __LINE__, (EString("Invalid Datatree, unable to find ") +
						"parent " + parentPath + " of path " + path).c_str());
			}
			node->parent->addChild(path.substring(lastSlash + 1));
			llong eowner = node->stat->getEphemeralOwner();
			if (eowner != 0) {
				auto list = ephemerals.get(eowner);
				if (list == null) {
					list = new HashSetSync<EString*>();
					ephemerals.put(eowner, list);
				}
				list->add(new EString(path));
			}
		}
		path = ia->readString("path");
	}
	nodes.put(new EString("/"), root);
	// we are done with deserializing the
	// the datatree
	// update the quotas - create path trie
	// and also update the stat nodes
	setupQuota();

	aclCache.purgeUnused();
}

void DataTree::dumpWatchesSummary(EPrintStream* pwriter) {
	SYNCHRONIZED(this) {
		pwriter->print(dataWatches.toString().c_str());
	}}
}

void DataTree::dumpWatches(EPrintStream* pwriter, boolean byPath) {
	SYNCHRONIZED(this) {
		dataWatches.dumpWatches(pwriter, byPath);
	}}
}

void DataTree::dumpEphemerals(EPrintStream* pwriter) {
	auto entrySet = ephemerals.entrySet();
	pwriter->println((EString("Sessions with Ephemerals (")
			+ entrySet->size() + "):").c_str());
	auto iter = entrySet->iterator();
	while (iter->hasNext()) {
		auto entry = iter->next();
		pwriter->print(("0x" + ELLong::toHexString(entry->getKey())).c_str());
		pwriter->println(":");
		auto tmp = entry->getValue();
		if (tmp != null) {
			SYNCHRONIZED (tmp) {
				auto i2 = tmp->iterator();
				while (i2->hasNext()) {
					EString* path = i2->next();
					pwriter->println((EString("\t") + path).c_str());
				}
			}}
		}
	}
}

void DataTree::removeCnxn(Watcher* watcher) {
	dataWatches.removeWatcher(watcher);
	childWatches.removeWatcher(watcher);
}

void DataTree::clear() {
	root = null;
	nodes.clear();
	ephemerals.clear();
}

void DataTree::setWatches(llong relativeZxid, sp<EList<EString*> > dataWatches,
		sp<EList<EString*> > existWatches, sp<EList<EString*> > childWatches,
		sp<Watcher> watcher) {
	auto i1 = dataWatches->iterator();
	while (i1->hasNext()) {
		EString* path = dynamic_cast<EString*>(i1->next());
		sp<DataNode> node = getNode(path);
		if (node == null) {
			watcher->process(new WatchedEvent(Watcher::Event::EventType::NodeDeleted,
						Watcher::Event::KeeperState::SyncConnected, path));
		} else if (node->stat->getMzxid() > relativeZxid) {
			watcher->process(new WatchedEvent(Watcher::Event::EventType::NodeDataChanged,
						Watcher::Event::KeeperState::SyncConnected, path));
		} else {
			this->dataWatches.addWatch(path, watcher);
		}
	}

	auto i2 = existWatches->iterator();
	while (i2->hasNext()) {
		EString* path = dynamic_cast<EString*>(i2->next());
		sp<DataNode> node = getNode(path);
		if (node != null) {
			watcher->process(new WatchedEvent(Watcher::Event::EventType::NodeCreated,
						Watcher::Event::KeeperState::SyncConnected, path));
		} else {
			this->dataWatches.addWatch(path, watcher);
		}
	}

	auto i3 = childWatches->iterator();
	while (i3->hasNext()) {
		EString* path = dynamic_cast<EString*>(i3->next());
		sp<DataNode> node = getNode(path);
		if (node == null) {
			watcher->process(new WatchedEvent(Watcher::Event::EventType::NodeDeleted,
						Watcher::Event::KeeperState::SyncConnected, path));
		} else if (node->stat->getPzxid() > relativeZxid) {
			watcher->process(new WatchedEvent(Watcher::Event::EventType::NodeChildrenChanged,
						Watcher::Event::KeeperState::SyncConnected, path));
		} else {
			this->childWatches.addWatch(path, watcher);
		}
	}
}

void DataTree::setCversionPzxid(EString path, int newCversion, llong zxid)
		THROWS(NoNodeException) {
	if (path.endsWith("/")) {
	   path = path.substring(0, path.length() - 1);
	}
	sp<DataNode> node = nodes.get(&path);
	if (node == null) {
		throw NoNodeException(__FILE__, __LINE__, path.c_str());
	}
	SYNCHRONIZED(node) {
		if(newCversion == -1) {
			newCversion = node->stat->getCversion() + 1;
		}
		if (newCversion > node->stat->getCversion()) {
			node->stat->setCversion(newCversion);
			node->stat->setPzxid(zxid);
		}
	}}
}

void DataTree::getCounts(EString path, Counts& counts) {
	sp<DataNode> node = getNode(path);
	if (node == null) {
		return;
	}
	/* @see:
	String[] children = null;
	int len = 0;
	synchronized (node) {
		Set<String> childs = node.getChildren();
		children = childs.toArray(new String[childs.size()]);
		len = (node.data == null ? 0 : node.data.length);
	}
	*/
	sp<EHashSet<EString*> > children = new EHashSet<EString*>();
	int len = 0;
	SYNCHRONIZED(node) {
		sp<EHashSet<EString*> > childs = node->getChildren();
		auto iter = childs->iterator();
		while (iter->hasNext()) {
			children->add(new EString(iter->next()));
		}
		len = (node->data == null ? 0 : node->data->length());
	}}
	// add itself
	counts.count += 1;
	counts.bytes += len;
	auto iter = children->iterator();
	while (iter->hasNext()) {
		EString* child = iter->next();
		getCounts(path + "/" + child, counts);
	}
}

void DataTree::updateQuotaForPath(EString path) {
	Counts c(this);
	getCounts(path, c);
	StatsTrack strack;
	strack.setBytes(c.bytes);
	strack.setCount(c.count);
	EString statPath = Quotas::quotaZookeeper + path + "/" + Quotas::statNode;
	sp<DataNode> node = getNode(statPath);
	// it should exist
	if (node == null) {
		LOG->warn("Missing quota stat node " + statPath);
		return;
	}
	SYNCHRONIZED (node) {
		EString s = strack.toString();
		node->data = new EA<byte>((byte*)s.c_str(), s.length());
	}}
}

void DataTree::traverseNode(EString path) {
	sp<DataNode> node = getNode(path);
	/* @see:
	String children[] = null;
	synchronized (node) {
		Set<String> childs = node.getChildren();
		children = childs.toArray(new String[childs.size()]);
	}
	*/
	sp<EA<EString*> > children;
	SYNCHRONIZED(node) {
		sp<EHashSet<EString*> > childs = node->getChildren();
		children = new EA<EString*>(childs->size());
		auto iter = childs->iterator();
		int i = 0;
		while (iter->hasNext()) {
			(*children)[i++] = new EString(iter->next());
		}
	}}

	if (children->length() == 0) {
		// this node does not have a child
		// is the leaf node
		// check if its the leaf node
		EString endString = EString("/") + Quotas::limitNode;
		if (path.endsWith(endString.c_str())) {
			// ok this is the limit node
			// get the real node and update
			// the count and the bytes
			EString realPath = path.substring(eso_strlen(Quotas::quotaZookeeper),
					path.indexOf(endString.c_str()));
			updateQuotaForPath(realPath);
			this->pTrie.addPath(realPath);
		}
		return;
	}
	for (int i=0; i<children->length(); i++) {
		EString* child = (*children)[i];
		traverseNode(path + "/" + child);
	}
}

void DataTree::setupQuota() {
	EString quotaPath = Quotas::quotaZookeeper;
	sp<DataNode> node = getNode(quotaPath);
	if (node == null) {
		return;
	}
	traverseNode(quotaPath);
}

} /* namespace ezk */
} /* namespace efc */
