/*
 * FileTxnLog.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./FileTxnLog.hh"

namespace efc {
namespace ezk {

sp<ELogger> FileTxnLog::LOG = ELoggerManager::getLogger("FileTxnLog");

void FileTxnLog::FileTxnIterator::init() THROWS(EIOException) {
	//@see: List<File> files = Util.sortDataDir(FileTxnLog.getLogFiles(logDir.listFiles(), 0), "log", false);
	EArray<EFile*> listfiles = logDir.listFiles();
	sp<EArray<EFile*> > files = FileTxnLog::getLogFiles(&listfiles, 0);
	Util::sortDataDir(files.get(), "log", false);

	auto iter = files->iterator();
	while (iter->hasNext()) {
		EFile* f = iter->next();
		if (Util::getZxidFromName(f->getName(), "log") >= zxid) {
			storedFiles.add(new EFile(f));
		}
		// add the last logfile that is less than the zxid
		else if (Util::getZxidFromName(f->getName(), "log") < zxid) {
			storedFiles.add(new EFile(f));
			break;
		}
	}
	goToNextLog();
	if (!next())
		return;
	while (hdr->getZxid() < zxid) {
		if (!next())
			return;
	}
}

boolean FileTxnLog::FileTxnIterator::next() THROWS(EIOException) {
	if (ia == null) {
		return false;
	}
	try {
		llong crcValue = ia->readLLong("crcvalue");
		sp<EA<byte> > bytes = Util::readTxnBytes(ia.get());
		// Since we preallocate, we define EOF to be an
		if (bytes == null || bytes->length()==0) {
			throw EEOFException(__FILE__, __LINE__, ("Failed to read " + logFile->toString()).c_str());
		}
		// EOF or corrupted record
		// validate CRC
		//@see: Checksum crc = makeChecksumAlgorithm();
		EAdler32 crc;
		crc.update(bytes->address(), bytes->length());
		if (crcValue != crc.getValue())
			throw EIOException(__FILE__, __LINE__, CRC_ERROR);
		hdr = new TxnHeader();
		record = SerializeUtils::deserializeTxn(bytes, hdr.get());
	} catch (EEOFException& e) {
		LOG->debug(__FILE__, __LINE__, EString("EOF excepton ") + e.toString());
		inputStream->close();
		inputStream = null;
		ia = null;
		hdr = null;
		// this means that the file has ended
		// we should go to the next file
		if (!goToNextLog()) {
			return false;
		}
		// if we went to the next log file, we should call next() again
		return next();
	} catch (EIOException& e) {
		inputStream->close();
		throw e;
	}
	return true;
}

boolean FileTxnLog::FileTxnIterator::goToNextLog() THROWS(EIOException) {
	if (storedFiles.size() > 0) {
		delete logFile; //!!!
		logFile = storedFiles.removeAt(storedFiles.size()-1);
		ia = createInputArchive(logFile);
		return true;
	}
	return false;
}

void FileTxnLog::FileTxnIterator::inStreamCreated(EInputArchive* ia, EInputStream* is) {
	FileHeader header;//= new FileHeader();
	header.deserialize(ia, "fileheader");
	if (header.getMagic() != TXNLOG_MAGIC) {
		throw EIOException(__FILE__, __LINE__, ("Transaction log: " + logFile->toString() + " has invalid magic number "
				+ header.getMagic()
				+ " != " + TXNLOG_MAGIC).c_str());
	}
}

sp<EInputArchive> FileTxnLog::FileTxnIterator::createInputArchive(EFile* logFile) {
	if (inputStream==null) {
		inputStream = new PositionInputStream(new EBufferedInputStream(new EFileInputStream(logFile), 8192, true), true);
		LOG->debug("Created new input stream " + logFile->toString());
		ia = EBinaryInputArchive::getArchive(inputStream.get());
		inStreamCreated(ia.get(),inputStream.get());
		LOG->debug("Created new input archive " + logFile->toString());
	}
	return ia;
}

//=============================================================================

FileTxnLog::~FileTxnLog() {
	//
}

FileTxnLog::FileTxnLog(EFile* logDir) :
	lastZxidSeen(0),
	logStream(null),
	oa(null),
	fos(null),
	logDir(logDir),
	forceSync(true), /*default value*/
	dbId(0),
	currentSize(0) {
}

void FileTxnLog::rollLog() THROWS(EIOException) {
	SYNCHRONIZED(this) {
		if (logStream != null) {
			logStream->flush();
			logStream = null;
			oa = null;
		}
	}}
}

void FileTxnLog::close() THROWS(EIOException) {
	SYNCHRONIZED(this) {
		if (logStream != null) {
			logStream->close();
		}
		auto iter = streamsToFlush.iterator();
		while (iter->hasNext()) {
			sp<EFileOutputStream> log = iter->next();
			log->close();
		}
	}}
}

boolean FileTxnLog::append(TxnHeader* hdr, ERecord* txn) THROWS(EIOException) {
	if (hdr == null) {
		return false;
	}

	SYNCHRONIZED(this) {
		if (hdr->getZxid() <= lastZxidSeen) {
			LOG->warn(EString("Current zxid ") + hdr->getZxid()
					+ " is <= " + lastZxidSeen + " for "
					+ hdr->getType());
		} else {
			lastZxidSeen = hdr->getZxid();
		}

		if (logStream==null) {
			if(LOG->isInfoEnabled()){
				LOG->info("Creating new log file: log." +
						ELLong::toHexString(hdr->getZxid()));
			}

			EFile logFileWrite(&logDir, ("log." +
					ELLong::toHexString(hdr->getZxid())).c_str());
			fos = new EFileOutputStream(&logFileWrite);
			logStream = new EBufferedOutputStream(fos.get());
			oa = EBinaryOutputArchive::getArchive(logStream.get());
			FileHeader fhdr(TXNLOG_MAGIC, VERSION, dbId);
			fhdr.serialize(oa.get(), "fileheader");
			// Make sure that the magic number is written before padding.
			logStream->flush();
			//@see: currentSize = fos.getChannel().position();
			currentSize = fos->getChannel()->position();
			streamsToFlush.add(fos);
		}
		padFile(fos.get()); //!!!
		sp<EA<byte> > buf = Util::marshallTxnEntry(hdr, txn);
		if (buf == null || buf->length() == 0) {
			throw EIOException(__FILE__, __LINE__, "Faulty serialization for header and txn");
		}
		//@see: Checksum crc = makeChecksumAlgorithm();
		EAdler32 crc;
		crc.update(buf->address(), buf->length());
		oa->writeLLong(crc.getValue(), "txnEntryCRC");
		Util::writeTxnBytes(oa.get(), buf);
	}}
	return true;
}

void FileTxnLog::padFile(EFileOutputStream* fos) THROWS(EIOException) {
	 /** @see: Util.java#padLogFile()
	 * Grows the file to the specified number of bytes. This only happenes if
	 * the current file position is sufficiently close (less than 4K) to end of
	 * file.
	 *
	 * @param f output stream to pad
	 * @param currentSize application keeps track of the cuurent file size
	 * @param preAllocSize how many bytes to pad
	 * @return the new file size. It can be the same as currentSize if no
	 * padding was done.
	 * @throws IOException
	 */
	EFileChannel* channel = fos->getChannel();
	long position = fos->getChannel()->position();
	if (position + 4096 >= currentSize) {
		currentSize += preAllocSize;
		EIOByteBuffer fill(1);
		channel->write(&fill, currentSize-fill.remaining());
	}
}

llong FileTxnLog::getLastLoggedZxid() {
	EArray<EFile*> listfiles = logDir.listFiles();
	sp<EArray<EFile*> > files = getLogFiles(&listfiles, 0);
	llong maxLog = files->length() > 0 ?
					Util::getZxidFromName((*files)[files->length() - 1]->getName(), "log") :
					-1;

	// if a log file is more recent we must scan it to find
	// the highest zxid
	llong zxid = maxLog;
	sp<TxnLog::TxnIterator> itr = null;
	ON_FINALLY_NOTHROW(
		close(itr.get());
	) {
		try {
			FileTxnLog txn(&logDir);
			itr = txn.read(maxLog);
			while (true) {
				if(!itr->next())
					break;
				sp<TxnHeader> hdr = itr->getHeader();
				zxid = hdr->getZxid();
			}
		} catch (EIOException& e) {
			LOG->warn(__FILE__, __LINE__, "Unexpected exception", e);
		}
	}}
	return zxid;
}

void FileTxnLog::commit() THROWS(EIOException) {
	SYNCHRONIZED(this) {
		if (logStream != null) {
			logStream->flush();
		}
		auto iter = streamsToFlush.iterator();
		while (iter->hasNext()) {
			sp<EFileOutputStream> log = iter->next();
			log->flush();
			if (forceSync) {
				llong startSyncNS = ESystem::nanoTime();

				//@see: log.getChannel().force(false);
				EFileDispatcher::force(eso_fileno(log->getFD()), false);

				llong syncElapsedMS =
					ETimeUnit::NANOSECONDS->toMillis(ESystem::nanoTime() - startSyncNS);
				if (syncElapsedMS > fsyncWarningThresholdMS) {
					LOG->warn(__FILE__, __LINE__, EString("fsync-ing the write ahead log in ")
							+ EThread::currentThread()->getName()
							+ " took " + syncElapsedMS
							+ "ms which will adversely effect operation latency. "
							+ "See the ZooKeeper troubleshooting guide");
				}
			}
		}
		while (streamsToFlush.size() > 1) {
			streamsToFlush.removeFirst()->close();
		}
	}}
}

sp<TxnLog::TxnIterator> FileTxnLog::read(llong zxid) THROWS(EIOException) {
	return new FileTxnIterator(&logDir, zxid);
}

boolean FileTxnLog::truncate(llong zxid) THROWS(EIOException) {
	sp<FileTxnIterator> itr = null;
	ON_FINALLY_NOTHROW(
		close(itr.get());
	) {
		itr = new FileTxnIterator(&this->logDir, zxid);
		sp<PositionInputStream> input = itr->inputStream;
		if (input == null) {
			throw EIOException(__FILE__, __LINE__, "No log files found to truncate! This could "
					"happen if you still have snapshots from an old setup or "
					"log files were deleted accidentally or dataLogDir was changed in zoo.cfg.");
		}
		llong pos = input->getPosition();
		// now, truncate at the current position
		ERandomAccessFile raf(itr->logFile, "rw");
		raf.setLength(pos);
		raf.close();
		while (itr->goToNextLog()) {
			if (!itr->logFile->remove()) {
				LOG->warn(__FILE__, __LINE__, "Unable to truncate " + itr->logFile->toString());
			}
		}
	}}
	return true;
}

llong FileTxnLog::getDbId() THROWS(EIOException) {
	FileTxnIterator itr(&logDir, 0);
	sp<FileHeader> fh = readHeader(itr.logFile);
	itr.close();
	if (fh==null)
		throw EIOException(__FILE__, __LINE__, "Unsupported Format.");
	return fh->getDbid();
}

void FileTxnLog::close(TxnIterator* itr) {
	if (itr != null) {
		try {
			itr->close();
		} catch (EIOException& ioe) {
			LOG->warn(__FILE__, __LINE__, "Error closing file iterator", ioe);
		}
	}
}

sp<FileHeader> FileTxnLog::readHeader(EFile* file) THROWS(EIOException) {
	EBufferedInputStream is(new EFileInputStream(file), 8192, true);
	ON_FINALLY_NOTHROW(
		try {
			 is.close();
		 } catch (EIOException& e) {
			 LOG->warn(__FILE__, __LINE__, "Ignoring exception during close", e);
		 }
	) {
		sp<EInputArchive> ia = EBinaryInputArchive::getArchive(&is);
		sp<FileHeader> hdr = new FileHeader();
		hdr->deserialize(ia.get(), "fileheader");
		return hdr;
	}}
}

sp<EArray<EFile*> > FileTxnLog::getLogFiles(EList<EFile*>* logDirList, llong snapshotZxid) {
	Util::sortDataDir(logDirList, "log", true);
	llong logZxid = 0;

	// Find the log file that starts before or at the same time as the
	// zxid of the snapshot

	auto iter = logDirList->iterator();
	while (iter->hasNext()) {
		EFile* f = iter->next();
		llong fzxid = Util::getZxidFromName(f->getName(), "log");
		if (fzxid > snapshotZxid) {
			continue;
		}
		// the files
		// are sorted with zxid's
		if (fzxid > logZxid) {
			logZxid = fzxid;
		}
	}

	sp<EArray<EFile*> > v= new EArray<EFile*>(5);
	iter = logDirList->iterator();
	while (iter->hasNext()) {
		EFile* f = iter->next();
		llong fzxid = Util::getZxidFromName(f->getName(), "log");
		if (fzxid < logZxid) {
			continue;
		}
		v->add(new EFile(f));
	}
	return v;
}

} /* namespace ezk */
} /* namespace efc */
