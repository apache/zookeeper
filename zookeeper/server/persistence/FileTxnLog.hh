/*
 * FileTxnLog.hh
 *
 *  Created on: 2017-11-22

 */

#ifndef FileTxnLog_HH_
#define FileTxnLog_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./Util.hh"
#include "./TxnLog.hh"
#include "./FileHeader.hh"
#include "../util/SerializeUtils.hh"
#include "../../txn/TxnHeader.hh"
#include "../../../jute/inc/ERecord.hh"
#include "../../../jute/inc/EBinaryInputArchive.hh"
#include "../../../jute/inc/EBinaryOutputArchive.hh"
#include "../../../jute/inc/ECsvOutputArchive.hh"

namespace efc {
namespace ezk {

/**
 * This class implements the TxnLog interface. It provides api's
 * to access the txnlogs and add entries to it.
 * <p>
 * The format of a Transactional log is as follows:
 * <blockquote><pre>
 * LogFile:
 *     FileHeader TxnList ZeroPad
 * 
 * FileHeader: {
 *     magic 4bytes (ZKLG)
 *     version 4bytes
 *     dbid 8bytes
 *   }
 * 
 * TxnList:
 *     Txn || Txn TxnList
 *     
 * Txn:
 *     checksum Txnlen TxnHeader Record 0x42
 * 
 * checksum: 8bytes Adler32 is currently used
 *   calculated across payload -- Txnlen, TxnHeader, Record and 0x42
 * 
 * Txnlen:
 *     len 4bytes
 * 
 * TxnHeader: {
 *     sessionid 8bytes
 *     cxid 4bytes
 *     zxid 8bytes
 *     time 8bytes
 *     type 4bytes
 *   }
 *     
 * Record:
 *     See Jute definition file for details on the various record types
 *      
 * ZeroPad:
 *     0 padded to EOF (filled during preallocation stage)
 * </pre></blockquote> 
 */

class FileTxnLog : public TxnLog, public ESynchronizeable {
private:
	static sp<ELogger> LOG;

    static const llong preAllocSize =  65536 * 1024;

    /** Maximum time we allow for elapsed fsync before WARNing */
    static const llong fsyncWarningThresholdMS = 1000;

    /**
     * read the header of the transaction file
     * @param file the transaction file to read
     * @return header that was read fomr the file
     * @throws IOException
     */
    static sp<FileHeader> readHeader(EFile* file) THROWS(EIOException);

private:
	llong lastZxidSeen;
	sp<EBufferedOutputStream> logStream;// = null; volatile?
	sp<EOutputArchive> oa; //volatile?
	sp<EFileOutputStream> fos;// = null; volatile?

	EFile logDir;
	boolean forceSync;// = !System.getProperty("zookeeper.forceSync", "yes").equals("no");;
	llong dbId;
	ELinkedList<sp<EFileOutputStream> > streamsToFlush;// = new LinkedList<FileOutputStream>();
	llong currentSize;

	void close(TxnIterator* itr);

	 /**
	 * pad the current file to increase its size
	 * @param out the outputstream to be padded
	 * @throws IOException
	 */
	void padFile(EFileOutputStream* out) THROWS(EIOException);

protected:
	/**
	 * a class that keeps track of the position
	 * in the input stream. The position points to offset
	 * that has been consumed by the applications. It can
	 * wrap buffered input streams to provide the right offset
	 * for the application.
	 */
	class PositionInputStream : public EFilterInputStream {
	private:
		llong position;
		boolean own;
	public:
		PositionInputStream(EInputStream* in, boolean owned) : EFilterInputStream(in, owned) {
			position = 0;
		}

		virtual int read() THROWS(EIOException) {
			int rc = EInputStream::read();
			if (rc > -1) {
				position++;
			}
			return rc;
		}

		virtual int read(void *b, int len) THROWS(EIOException) {
			int rc = EFilterInputStream::read(b, len);
			if (rc > 0) {
				position += rc;
			}
			return rc;
		}

		virtual long skip(long n) THROWS(EIOException) {
			long rc = EFilterInputStream::skip(n);
			if (rc > 0) {
				position += rc;
			}
			return rc;
		}

		llong getPosition() {
			return position;
		}
	};

	/**
	 * this class implements the txnlog iterator interface
	 * which is used for reading the transaction logs
	 */
	class FileTxnIterator : public TxnLog::TxnIterator {
	private:
		/**
		 * initialize to the zxid specified
		 * this is inclusive of the zxid
		 * @throws IOException
		 */
		void init() THROWS(EIOException);

	public:
		EFile logDir;
		llong zxid;
		sp<TxnHeader> hdr;
		sp<ERecord> record;
		EFile* logFile;
		sp<EInputArchive> ia;

		constexpr static const char* CRC_ERROR = "CRC check failed";

		sp<PositionInputStream> inputStream;//=null;
		//stored files is the list of files greater than
		//the zxid we are looking for.
		EArrayList<EFile*> storedFiles;

		virtual ~FileTxnIterator() {
			delete logFile;
		}

		/**
		 * create an iterator over a transaction database directory
		 * @param logDir the transaction database directory
		 * @param zxid the zxid to start reading from
		 * @throws IOException
		 */
		FileTxnIterator(EFile* logDir, llong zxid) THROWS(EIOException) :
				logDir(logDir),
				zxid(zxid),
				logFile(null) {
		  init();
		}

		/**
		 * the iterator that moves to the next transaction
		 * @return true if there is more transactions to be read
		 * false if not.
		 */
		boolean next() THROWS(EIOException);

		/**
		 * go to the next logfile
		 * @return true if there is one and false if there is no
		 * new file to be read
		 * @throws IOException
		 */
		boolean goToNextLog() THROWS(EIOException);

		/**
		 * reutrn the current header
		 * @return the current header that
		 * is read
		 */
		sp<TxnHeader> getHeader() {
			return hdr;
		}

		/**
		 * return the current transaction
		 * @return the current transaction
		 * that is read
		 */
		sp<ERecord> getTxn() {
			return record;
		}

		/**
		 * close the iterator
		 * and release the resources.
		 */
		void close() THROWS(EIOException) {
			if (inputStream != null) {
				inputStream->close();
			}
		}

	protected:

		/**
		 * read the header from the inputarchive
		 * @param ia the inputarchive to be read from
		 * @param is the inputstream
		 * @throws IOException
		 */
		void inStreamCreated(EInputArchive* ia, EInputStream* is) THROWS(EIOException);

		/**
		 * Invoked to indicate that the input stream has been created.
		 * @param ia input archive
		 * @param is file input stream associated with the input archive.
		 * @throws IOException
		 **/
		sp<EInputArchive> createInputArchive(EFile* logFile) THROWS(EIOException);
	};

public:

    static const int TXNLOG_MAGIC = 1514884167;// ByteBuffer.wrap("ZKLG".getBytes()).getInt();

    static const int VERSION = 2;


    /**
     * Find the log file that starts at, or just before, the snapshot. Return
     * this and all subsequent logs. Results are ordered by zxid of file,
     * ascending order.
     * @param logDirList array of files
     * @param snapshotZxid return files at, or before this zxid
     * @return
     */
    static sp<EArray<EFile*> > getLogFiles(EList<EFile*>* logDirList, llong snapshotZxid);

public:
    virtual ~FileTxnLog();

    /**
     * constructor for FileTxnLog. Take the directory
     * where the txnlogs are stored
     * @param logDir the directory where the txnlogs are stored
     */
    FileTxnLog(EFile* logDir);

    /**
     * rollover the current log file to a new one.
     * @throws IOException
     */
    synchronized
    virtual void rollLog() THROWS(EIOException);

    /**
     * close all the open file handles
     * @throws IOException
     */
    synchronized
    virtual void close() THROWS(EIOException);

    /**
     * append an entry to the transaction log
     * @param hdr the header of the transaction
     * @param txn the transaction part of the entry
     * returns true iff something appended, otw false
     */
    synchronized
    virtual boolean append(TxnHeader* hdr, ERecord* txn) THROWS(EIOException);

    /**
     * get the last zxid that was logged in the transaction logs
     * @return the last zxid logged in the transaction logs
     */
    virtual llong getLastLoggedZxid();

    /**
     * commit the logs. make sure that evertyhing hits the
     * disk
     */
    synchronized
    virtual void commit() THROWS(EIOException);

    /**
     * start reading all the transactions from the given zxid
     * @param zxid the zxid to start reading transactions from
     * @return returns an iterator to iterate through the transaction
     * logs
     */
    virtual sp<TxnIterator> read(llong zxid) THROWS(EIOException);

    /**
     * truncate the current transaction logs
     * @param zxid the zxid to truncate the logs to
     * @return true if successful false if not
     */
    virtual boolean truncate(llong zxid) THROWS(EIOException);

    /**
     * the dbid of this transaction database
     * @return the dbid of this database
     */
    virtual llong getDbId() THROWS(EIOException);

    /**
     * the forceSync value. true if forceSync is enabled, false otherwise.
     * @return the forceSync value
     */
    virtual boolean isForceSync() {
        return forceSync;
    }
};

} /* namespace ezk */
} /* namespace efc */
#endif /* FileTxnLog_HH_ */
