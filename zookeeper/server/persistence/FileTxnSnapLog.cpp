/*
 * FileTxnSnapLog.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./FileTxnSnapLog.hh"
#include "./FileTxnLog.hh"
#include "./FileSnap.hh"

namespace efc {
namespace ezk {

sp<ELogger> FileTxnSnapLog::LOG = ELoggerManager::getLogger("FileTxnSnapLog");

FileTxnSnapLog::FileTxnSnapLog(EFile* dataDir, EFile* snapDir) THROWS(EIOException) :
	dataDir_(dataDir, (EString(version) + VERSION).c_str()),
	snapDir_(snapDir, (EString(version) + VERSION).c_str()) {
	LOG->debug(EString::formatOf("Opening datadir:%s snapDir:%s", dataDir->toString().c_str(), snapDir->toString().c_str()));

	if (!dataDir_.exists()) {
		if (!dataDir_.mkdirs()) {
			throw EIOException(__FILE__, __LINE__, ("Unable to create data directory " + dataDir_.toString()).c_str());
		}
	}

	if (!dataDir_.canWrite()) {
		throw EIOException(__FILE__, __LINE__, ("Cannot write to data directory " + dataDir_.toString()).c_str());
	}

	if (!snapDir_.exists()) {
		if (!snapDir_.mkdirs()) {
			throw EIOException(__FILE__, __LINE__, ("Unable to create snap directory " + snapDir_.toString()).c_str());
		}
	}

	if (!snapDir_.canWrite()) {
		throw EIOException(__FILE__, __LINE__, ("Cannot write to snap directory " + snapDir_.toString()).c_str());
	}

	txnLog = new FileTxnLog(&dataDir_);
	snapLog = new FileSnap(&snapDir_);
}

llong FileTxnSnapLog::restore(sp<DataTree> dt, EConcurrentHashMap<llong, EInteger>* sessions,
		PlayBackListener* listener) THROWS(EIOException) {
	snapLog->deserialize(dt, sessions);
	FileTxnLog txnLog(&dataDir_);// = new FileTxnLog(dataDir);
	sp<TxnLog::TxnIterator> itr = txnLog.read(dt->lastProcessedZxid+1);
	llong highestZxid = dt->lastProcessedZxid;
	sp<TxnHeader> hdr;
	ON_FINALLY_NOTHROW(
		if (itr != null) {
			itr->close();
		}
	) {
		while (true) {
			// iterator points to
			// the first valid txn when initialized
			hdr = itr->getHeader();
			if (hdr == null) {
				//empty logs
				return dt->lastProcessedZxid;
			}
			if (hdr->getZxid() < highestZxid && highestZxid != 0) {
				LOG->error(EString::formatOf("%ld(higestZxid) > %ld(next log) for type %d",
						highestZxid, hdr->getZxid(), hdr->getType()));
			} else {
				highestZxid = hdr->getZxid();
			}
			try {
				processTransaction(hdr.get(), dt.get(), sessions, itr->getTxn().get());
			} catch (NoNodeException& e) {
			   throw EIOException(__FILE__, __LINE__, (EString("Failed to process transaction type: ") +
					 hdr->getType() + " error: " + e.getMessage()).c_str(), &e);
			}
			listener->onTxnLoaded(hdr, itr->getTxn());
			if (!itr->next())
				break;
		}
	}}
	return highestZxid;
}

void FileTxnSnapLog::processTransaction(TxnHeader* hdr, DataTree* dt,
		EConcurrentHashMap<llong, EInteger>* sessions, ERecord* txn)
		THROWS(NoNodeException) {
	sp<DataTree::ProcessTxnResult> rc;
	CreateSessionTxn* cstxn = dynamic_cast<CreateSessionTxn*>(txn);
	switch (hdr->getType()) {
	case ZooDefs::OpCode::createSession: {
		sessions->put(hdr->getClientId(),
				new EInteger(cstxn->getTimeOut()));
		if (LOG->isTraceEnabled()) {
			ZooTrace::logTraceMessage(LOG, ZooTrace::SESSION_TRACE_MASK,
					"playLog --- create session in log: 0x"
							+ ELLong::toHexString(hdr->getClientId())
							+ " with timeout: "
							+ cstxn->getTimeOut());
		}
		// give dataTree a chance to sync its lastProcessedZxid
		rc = dt->processTxn(hdr, txn);
		break;
	}
	case ZooDefs::OpCode::closeSession: {
		sessions->remove(hdr->getClientId());
		if (LOG->isTraceEnabled()) {
			ZooTrace::logTraceMessage(LOG, ZooTrace::SESSION_TRACE_MASK,
					"playLog --- close session in log: 0x"
							+ ELLong::toHexString(hdr->getClientId()));
		}
		rc = dt->processTxn(hdr, txn);
		break;
	}
	default:
		rc = dt->processTxn(hdr, txn);
		break;
	}

	/**
	 * Snapshots are lazily created. So when a snapshot is in progress,
	 * there is a chance for later transactions to make into the
	 * snapshot. Then when the snapshot is restored, NONODE/NODEEXISTS
	 * errors could occur. It should be safe to ignore these.
	 */
	if (rc->err != KeeperException::Code::OK) {
		LOG->debug((EString("Ignoring processTxn failure hdr:") + hdr->getType()
				+ ", error: " + rc->err + ", path: " + rc->path).c_str());
	}
}

llong FileTxnSnapLog::getLastLoggedZxid() {
	FileTxnLog txnLog(&dataDir_);
	return txnLog.getLastLoggedZxid();
}

void FileTxnSnapLog::save(sp<DataTree> dataTree,
		EConcurrentHashMap<llong, EInteger>* sessionsWithTimeouts)
		THROWS(EIOException) {
	llong lastZxid = dataTree->lastProcessedZxid;
	EFile snapshotFile(&snapDir_, Util::makeSnapshotName(lastZxid).c_str());
	LOG->info(EString::formatOf("Snapshotting: 0x%s to %s", ELLong::toHexString(lastZxid).c_str(),
			snapshotFile.toString().c_str()).c_str());
	snapLog->serialize(dataTree, sessionsWithTimeouts, &snapshotFile);
}

boolean FileTxnSnapLog::truncateLog(llong zxid) THROWS(EIOException) {
	// close the existing txnLog and snapLog
	close();

	// truncate it
	FileTxnLog truncLog(&dataDir_);
	boolean truncated = truncLog.truncate(zxid);
	truncLog.close();

	// re-open the txnLog and snapLog
	// I'd rather just close/reopen this object itself, however that
	// would have a big impact outside ZKDatabase as there are other
	// objects holding a reference to this object.
	txnLog = new FileTxnLog(&dataDir_);
	snapLog = new FileSnap(&snapDir_);

	return truncated;
}

sp<EFile> FileTxnSnapLog::findMostRecentSnapshot() THROWS(EIOException) {
	FileSnap snaplog(&snapDir_);
	return snaplog.findMostRecentSnapshot();
}

sp<EList<EFile*> > FileTxnSnapLog::findNRecentSnapshots(int n) THROWS(EIOException) {
	FileSnap snaplog(&snapDir_);
	return snaplog.findNRecentSnapshots(n);
}

sp<EArray<EFile*> > FileTxnSnapLog::getSnapshotLogs(llong zxid) {
	EArray<EFile*> files = dataDir_.listFiles();
	return FileTxnLog::getLogFiles(&files, zxid);
}

boolean FileTxnSnapLog::append(sp<Request> si) THROWS(EIOException) {
	return txnLog->append(si->hdr.get(), si->txn.get());
}

} /* namespace ezk */
} /* namespace efc */
