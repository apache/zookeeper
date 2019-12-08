/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.graph;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.zip.Adler32;
import java.util.zip.Checksum;
import java.util.HashMap;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.server.TraceFormatter;
import org.apache.zookeeper.server.TxnLogEntry;
import org.apache.zookeeper.server.persistence.FileHeader;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnHeader;

import org.apache.zookeeper.ZooDefs.OpCode;

import org.apache.zookeeper.txn.CreateSessionTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;

import java.io.File;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TxnLogSource implements LogSource {
    private static final Logger LOG = LoggerFactory.getLogger(TxnLogSource.class);

    private LogSkipList skiplist = null;
    private static final int skipN = 10000;

    private String file = null;
    private long starttime = 0;
    private long endtime = 0;
    private long size = 0;

    public boolean overlapsRange(long starttime, long endtime) {
	return (starttime <= this.endtime && endtime >= this.starttime);
    }

    public long size() { return size; }
    public long getStartTime() { return starttime; }
    public long getEndTime() { return endtime; }
    public LogSkipList getSkipList() { return skiplist; }

    public static boolean isTransactionFile(String file) throws IOException {
        RandomAccessFileReader reader = new RandomAccessFileReader(new File(file));
        BinaryInputArchive logStream = new BinaryInputArchive(reader);
        FileHeader fhdr = new FileHeader();
        fhdr.deserialize(logStream, "fileheader");
	reader.close();

        return fhdr.getMagic() == FileTxnLog.TXNLOG_MAGIC;
    }

    private class TxnLogSourceIterator implements LogIterator {
	private LogEntry next = null;
	private long starttime = 0;
	private long endtime = 0;
	private TxnLogSource src = null;
	private RandomAccessFileReader reader = null;
	private BinaryInputArchive logStream = null;
	private long skippedAtStart = 0;
	private FilterOp filter = null;

	public TxnLogSourceIterator(TxnLogSource src, long starttime, long endtime) throws IllegalArgumentException, FilterException {
	    this(src,starttime,endtime,null);
	}
	
	public TxnLogSourceIterator(TxnLogSource src, long starttime, long endtime, FilterOp filter) throws IllegalArgumentException, FilterException {
	    try {
		this.src = src;
		this.starttime = starttime;
		this.endtime = endtime;
		reader = new RandomAccessFileReader(new File(src.file));
		logStream = new BinaryInputArchive(reader);
		FileHeader fhdr = new FileHeader();
		fhdr.deserialize(logStream, "fileheader");
	    } catch (Exception e) {
		throw new IllegalArgumentException("Cannot open transaction log ("+src.file+") :" + e);
	    }
	    
	    LogSkipList.Mark start = src.getSkipList().findMarkBefore(starttime);
	    try {
		reader.seek(start.getBytes());
		skippedAtStart = start.getEntriesSkipped();
	    } catch (IOException ioe) {
		// if we can't skip, we should just read from the start
	    }

	    this.filter = filter;

	    LogEntry e;
	    while ((e = readNextEntry()) != null && e.getTimestamp() < endtime) {
		if (e.getTimestamp() >= starttime && (filter == null || filter.matches(e))  ) {
		    next = e;
		    return;
		}
		skippedAtStart++;
	    }


	}
	
	public long size() throws IOException {
	    if (this.endtime >= src.getEndTime()) {
		return src.size() - skippedAtStart;
	    }
	    
	    long pos = reader.getPosition();
	    LogEntry e;

	    LogSkipList.Mark lastseg = src.getSkipList().findMarkBefore(this.endtime);
	    reader.seek(lastseg.getBytes());
	    // number of entries skipped to get to the end of the iterator, less the number skipped to get to the start
	    long count = lastseg.getEntriesSkipped() - skippedAtStart; 

	    while ((e = readNextEntry()) != null) {
		if (e.getTimestamp() > this.endtime) {
		    break;
		}
		count++;
	    }
	    reader.seek(pos);;

	    return count;
	}
	
	private LogEntry readNextEntry() {
	    LogEntry e = null;
	    try {
		long crcValue;
		byte[] bytes;
		try {
		    crcValue = logStream.readLong("crcvalue");
		    
		    bytes = logStream.readBuffer("txnEntry");
		} catch (EOFException ex) {
		    return null;
		}
		
		if (bytes.length == 0) {
		    return null;
		}
		Checksum crc = new Adler32();
		crc.update(bytes, 0, bytes.length);
		if (crcValue != crc.getValue()) {
		    throw new IOException("CRC doesn't match " + crcValue +
					  " vs " + crc.getValue());
		}
    
		TxnLogEntry logEntry = SerializeUtils.deserializeTxn(bytes);
		TxnHeader hdr = logEntry.getHeader();
		Record r = logEntry.getTxn();

		switch (hdr.getType()) {
		case OpCode.createSession: {
		    e = new TransactionEntry(hdr.getTime(), hdr.getClientId(), hdr.getCxid(), hdr.getZxid(), "createSession");
		}
		    break;
		case OpCode.closeSession: {
		    e = new TransactionEntry(hdr.getTime(), hdr.getClientId(), hdr.getCxid(), hdr.getZxid(), "closeSession");
		}
		    break;
		case OpCode.create:
		    if (r != null) {
			CreateTxn create = (CreateTxn)r;
			String path = create.getPath();
			e = new TransactionEntry(hdr.getTime(), hdr.getClientId(), hdr.getCxid(), hdr.getZxid(), "create", path);
		    }
		    break;
		case OpCode.setData:
		    if (r != null) {
			SetDataTxn set = (SetDataTxn)r;
			String path = set.getPath();
			e = new TransactionEntry(hdr.getTime(), hdr.getClientId(), hdr.getCxid(), hdr.getZxid(), "setData", path);
		    }
		    break;
		case OpCode.setACL:
		    if (r != null) {
			SetACLTxn setacl = (SetACLTxn)r;
			String path = setacl.getPath();
		    e = new TransactionEntry(hdr.getTime(), hdr.getClientId(), hdr.getCxid(), hdr.getZxid(), "setACL", path);
		    }
		    break;
		case OpCode.error:
		    if (r != null)  {
			ErrorTxn error = (ErrorTxn)r;
			
			e = new TransactionEntry(hdr.getTime(), hdr.getClientId(), hdr.getCxid(), hdr.getZxid(), "error", "Error: " + error.getErr());
		    }
		    break;
		default:
		    LOG.info("Unknown op: " + hdr.getType());
		    break;
		}
		
		if (logStream.readByte("EOR") != 'B') {
		    throw new EOFException("Last transaction was partial.");
		}
	    } catch (Exception ex) {
		LOG.error("Error reading transaction from (" + src.file + ") :" + e);
		return null;
	    }
	    return e;
	}

	public boolean hasNext() {
	    return next != null;
	}
	
	public LogEntry next() throws NoSuchElementException {
	    LogEntry ret = next;
	    LogEntry e = readNextEntry();

	    if (filter != null) {
		try {
		    while (e != null && !filter.matches(e)) {
			e = readNextEntry();
		    }
		} catch (FilterException fe) {
		    throw new NoSuchElementException(fe.toString());
		}
	    }
	    if (e != null && e.getTimestamp() < endtime) {
		next = e;
	    } else {
		next = null;
	    }
	    return ret;
	}

	public void remove() throws UnsupportedOperationException {
	    throw new UnsupportedOperationException("remove not supported for Txn logs");
	}
	
	public void close() throws IOException {
	    reader.close();
	}
    }

    public LogIterator iterator(long starttime, long endtime) throws IllegalArgumentException {
	try {
	    return iterator(starttime, endtime, null);
	} catch (FilterException fe) {
	    assert(false); // should never ever happen
	    return null;
	}
    }

    public LogIterator iterator(long starttime, long endtime, FilterOp filter) throws IllegalArgumentException, FilterException {
	// sanitise start and end times
	if (endtime < starttime) {
	    throw new IllegalArgumentException("End time (" +  endtime + ") must be greater or equal to starttime (" + starttime + ")");
	}

	return new TxnLogSourceIterator(this, starttime, endtime, filter);
    }

    public LogIterator iterator() throws IllegalArgumentException {
	return iterator(starttime, endtime+1);
    }
    
    public TxnLogSource(String file) throws IOException {
	this.file = file;

	skiplist = new LogSkipList();

	RandomAccessFileReader reader = new RandomAccessFileReader(new File(file));
	try {
	    BinaryInputArchive logStream = new BinaryInputArchive(reader);
	    FileHeader fhdr = new FileHeader();
	    fhdr.deserialize(logStream, "fileheader");
	    
	    byte[] bytes = null;
	    while (true) {
		long lastFp = reader.getPosition();

		long crcValue;

		try {
		    crcValue = logStream.readLong("crcvalue");
		    bytes = logStream.readBuffer("txnEntry");
		} catch (EOFException e) {
		    break;
		}
		
		if (bytes.length == 0) {
		    break;
		}
		Checksum crc = new Adler32();
		crc.update(bytes, 0, bytes.length);
		if (crcValue != crc.getValue()) {
		    throw new IOException("CRC doesn't match " + crcValue +
					  " vs " + crc.getValue());
		}
		if (logStream.readByte("EOR") != 'B') {
		    throw new EOFException("Last transaction was partial.");
		}
		TxnLogEntry logEntry = SerializeUtils.deserializeTxn(bytes);
		TxnHeader hdr = logEntry.getHeader();
		Record r = logEntry.getTxn();
		
		if (starttime == 0) {
		    starttime = hdr.getTime();
		}
		endtime = hdr.getTime();

		if (size % skipN == 0) {
		    skiplist.addMark(hdr.getTime(), lastFp, size);
		}
		size++;
	    }
	    if (bytes == null) {
		throw new IOException("Nothing read from ("+file+")");
	    }
	} finally {
	    reader.close();
	}
    }

    public String toString() {
	return "TxnLogSource(file=" + file + ", size=" + size + ", start=" + starttime + ", end=" + endtime +")";
    }

    public static void main(String[] args) throws IOException, FilterException {
	TxnLogSource s = new TxnLogSource(args[0]);
	System.out.println(s);

	LogIterator iter;

	if (args.length == 3) {
	    long starttime = Long.valueOf(args[1]);
	    long endtime = Long.valueOf(args[2]);
	    FilterOp fo = new FilterParser("(or (and (> zxid 0x2f0bd6f5e0) (< zxid 0x2f0bd6f5e9)) (= operation \"error\"))").parse();
	    System.out.println("fo: " + fo);
	    iter = s.iterator(starttime, endtime, fo);
	} else {
	    iter = s.iterator();
	}
	System.out.println(iter);
	while (iter.hasNext()) {
	    	    System.out.println(iter.next());
	}
	iter.close();
    }
}
