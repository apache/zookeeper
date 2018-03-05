/*
 * TxnLog.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef TxnLog_HH_
#define TxnLog_HH_

#include "Efc.hh"

#include "../../txn/TxnHeader.hh"
#include "../../../jute/inc/ERecord.hh"

namespace efc {
namespace ezk {

/**
 * Interface for reading transaction logs.
 *
 */
interface TxnLog : virtual public EObject {
	virtual ~TxnLog() {}

	/**
     * an iterating interface for reading
     * transaction logs.
     */
    interface TxnIterator : virtual public EObject {
    	virtual ~TxnIterator() {}

        /**
         * return the transaction header.
         * @return return the transaction header.
         */
    	virtual sp<TxnHeader> getHeader() = 0;

        /**
         * return the transaction record.
         * @return return the transaction record.
         */
    	virtual sp<ERecord> getTxn() = 0;

        /**
         * go to the next transaction record.
         * @throws IOException
         */
    	virtual boolean next() THROWS(EIOException) = 0;

        /**
         * close files and release the
         * resources
         * @throws IOException
         */
    	virtual void close() THROWS(EIOException) = 0;
    };

    /**
     * roll the current
     * log being appended to
     * @throws IOException 
     */
	virtual void rollLog() THROWS(EIOException) = 0;
    /**
     * Append a request to the transaction log
     * @param hdr the transaction header
     * @param r the transaction itself
     * returns true iff something appended, otw false 
     * @throws IOException
     */
	virtual boolean append(TxnHeader* hdr, ERecord* r) THROWS(EIOException) = 0;

    /**
     * Start reading the transaction logs
     * from a given zxid
     * @param zxid
     * @return returns an iterator to read the 
     * next transaction in the logs.
     * @throws IOException
     */
	virtual sp<TxnIterator> read(llong zxid) THROWS(EIOException) = 0;
    
    /**
     * the last zxid of the logged transactions.
     * @return the last zxid of the logged transactions.
     * @throws IOException
     */
	virtual llong getLastLoggedZxid() THROWS(EIOException) = 0;
    
    /**
     * truncate the log to get in sync with the 
     * leader.
     * @param zxid the zxid to truncate at.
     * @throws IOException 
     */
	virtual boolean truncate(llong zxid) THROWS(EIOException) = 0;
    
    /**
     * the dbid for this transaction log. 
     * @return the dbid for this transaction log.
     * @throws IOException
     */
	virtual llong getDbId() THROWS(EIOException) = 0;
    
    /**
     * commmit the trasaction and make sure
     * they are persisted
     * @throws IOException
     */
	virtual void commit() THROWS(EIOException) = 0;
   
    /** 
     * close the transactions logs
     */
	virtual void close() THROWS(EIOException) = 0;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* TxnLog_HH_ */
