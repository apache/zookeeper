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

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.util.Vector;
import java.util.Iterator;
import java.util.Collections;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MergedLogSource implements LogSource {
    private static final Logger LOG = LoggerFactory.getLogger(MergedLogSource.class);
    private Vector<LogSource> sources = null;
    private long starttime = 0;
    private long endtime = 0;
    private long size = 0;

    public boolean overlapsRange(long starttime, long endtime) {
	return (starttime <= this.endtime && endtime >= this.starttime);
    }
    
    public long size() { return size; }
    public long getStartTime() { return starttime; }
    public long getEndTime() { return endtime; }

    private class MergedLogSourceIterator implements LogIterator {
	private LogEntry next = null;
	private long start = 0;
	private long end = 0;
	private MergedLogSource src = null;
	private LogIterator[] sources = null;
	private LogEntry[] nexts = null;
	private FilterOp filter = null;
	
	public MergedLogSourceIterator(MergedLogSource src, long starttime, long endtime, FilterOp filter) throws IllegalArgumentException, FilterException {
	    Vector<LogIterator> iters = new Vector<LogIterator>();
	    for (LogSource s : src.sources) {
		if (s.overlapsRange(starttime, endtime)) {
		    iters.add(s.iterator(starttime, endtime, filter));
		}
	    }
	    
	    sources = new LogIterator[iters.size()];
	    sources = iters.toArray(sources);
	    nexts = new LogEntry[iters.size()];
	    for (int i = 0; i < sources.length; i++) {
		if (sources[i].hasNext()) 
		    nexts[i] = sources[i].next();
	    }
	    this.filter = filter;
	}
		    
	public MergedLogSourceIterator(MergedLogSource src, long starttime, long endtime) throws IllegalArgumentException, FilterException {
	    this(src, starttime, endtime, null);
	}
	
	public long size() throws IOException {
	    long size = 0;
	    for (LogIterator i : sources) {
		size += i.size();
	    }
	    return size;
	}

	public boolean hasNext() {
	    for (LogEntry n : nexts) {
		if (n != null) return true;
	    }
	    return false;
	}
	
	public LogEntry next() {
	    int min = -1;
	    for (int i = 0; i < nexts.length; i++) {
		if (nexts[i] != null) {
		    if (min == -1) {
			min = i;
		    } else if (nexts[i].getTimestamp() < nexts[min].getTimestamp()) {
			min = i;
		    }
		}
	    }
	    if (min == -1) {
		return null;
	    } else {
		LogEntry e =  nexts[min];
		nexts[min] = sources[min].next();
		return e;
	    }
	}

	public void remove() throws UnsupportedOperationException {
	    throw new UnsupportedOperationException("remove not supported for Merged logs");
	}
	
	public void close() throws IOException {
	    for (LogIterator i : sources) {
		i.close();
	    }
	}
    }

    public LogIterator iterator(long starttime, long endtime) throws IllegalArgumentException {
	try {
	    return iterator(starttime, endtime, null);
	} catch (FilterException fe) {
	    assert(false); // shouldn't happen without filter
	    return null;
	}
    }

    public LogIterator iterator(long starttime, long endtime, FilterOp filter) throws IllegalArgumentException, FilterException {
	// sanitise start and end times
	if (endtime < starttime) {
	    throw new IllegalArgumentException("End time (" +  endtime + ") must be greater or equal to starttime (" + starttime + ")");
	}

	return new MergedLogSourceIterator(this, starttime, endtime, filter);
    }

    public LogIterator iterator() throws IllegalArgumentException {
	return iterator(starttime, endtime+1);
    }
    
    public MergedLogSource(String[] files) throws IOException {
	sources = new Vector<LogSource>();
	for (String f : files) {
	    addSource(f);
	}
    }
    
    public void addSource(String f) throws IOException {
	LogSource s = null;
	if (TxnLogSource.isTransactionFile(f)) {
	    s = new TxnLogSource(f);
	} else {
	    s = new Log4JSource(f);
	}

	size += s.size();
	endtime = s.getEndTime() > endtime ? s.getEndTime() : endtime;
	starttime = s.getStartTime() < starttime || starttime == 0 ? s.getStartTime() : starttime;
	sources.add(s);
    }

    public String toString() {
	String s = "MergedLogSource(size=" + size + ", start=" + starttime + ", end=" + endtime +")";
	for (LogSource src : sources) {
	    s += "\n\t- " +src;
	}
	return s;
    }

    public static void main(String[] args) throws IOException {
	System.out.println("Time: " + System.currentTimeMillis());
	MergedLogSource s = new MergedLogSource(args);
	System.out.println(s);

	LogIterator iter;

	iter = s.iterator();
	System.out.println("Time: " + System.currentTimeMillis());
	System.out.println("Iterator Size: " + iter.size());
	System.out.println("Time: " + System.currentTimeMillis());
	/*	while (iter.hasNext()) {
	    System.out.println(iter.next());
	    }*/
	iter.close();
	System.out.println("Time: " + System.currentTimeMillis());
    }
}
