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

import java.io.File;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.Calendar;
import java.util.GregorianCalendar;

import java.io.EOFException;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Log4JSource implements LogSource {
    private static final Logger LOG = LoggerFactory.getLogger(Log4JSource.class);
    
    private static final int skipN = 10000;
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss,SSS";

    private LogSkipList skiplist = null;

    private String file = null;
    private long starttime = 0;
    private long endtime = 0;
    private int serverid = 0;
    private long size = 0;

    private Pattern timep;

    public boolean overlapsRange(long starttime, long endtime) {
	return (starttime <= this.endtime && endtime >= this.starttime);
    }
    
    public long size() { return size; }
    public long getStartTime() { return starttime; }
    public long getEndTime() { return endtime; }
    public LogSkipList getSkipList() { return skiplist; }
    
    private class Log4JSourceIterator implements LogIterator {
	private RandomAccessFileReader in;
	private LogEntry next = null;
	private long starttime = 0;
	private long endtime = 0;
	private String buf = "";	
	private Log4JSource src = null;
	private long skippedAtStart = 0;
	private SimpleDateFormat dateformat = null;
	private FilterOp filter = null;

	public Log4JSourceIterator(Log4JSource src, long starttime, long endtime) throws IllegalArgumentException, FilterException {
	    this(src, starttime, endtime, null);
	}

	public Log4JSourceIterator(Log4JSource src, long starttime, long endtime, FilterOp filter) throws IllegalArgumentException, FilterException {

	    this.dateformat = new SimpleDateFormat(DATE_FORMAT);
	    this.src = src;
	    this.starttime = starttime;
	    this.endtime = endtime;

	    File f = new File(src.file);
	    try {
		in = new RandomAccessFileReader(f);
	    } catch (FileNotFoundException e) {
		throw new IllegalArgumentException("Bad file passed in (" + src.file +") cannot open:" + e);
	    }

	    // skip to the offset of latest skip point before starttime
	    LogSkipList.Mark start = src.getSkipList().findMarkBefore(starttime);
	    try {
		in.seek(start.getBytes());
		skippedAtStart = start.getEntriesSkipped();
	    } catch (IOException ioe) {
		// if we can't skip, we should just read from the start
	    }

	    LogEntry e;
	    while ((e = readNextEntry()) != null && e.getTimestamp() < endtime) {
		if (e.getTimestamp() >= starttime && (filter == null || filter.matches(e))) {
		    next = e;
		    return;
		}
		skippedAtStart++;
	    }
	    this.filter = filter;
	}
	
	synchronized public long size() throws IOException {
	    if (LOG.isTraceEnabled()) {
		LOG.trace("size() called");
	    }

	    if (this.endtime >= src.getEndTime()) {
		return src.size() - skippedAtStart;
	    }
	    
	    long pos = in.getPosition();
	    
	    if (LOG.isTraceEnabled()) {
		LOG.trace("saved pos () = " + pos);
	    }
	    
	    LogEntry e;
	  
	    LogSkipList.Mark lastseg = src.getSkipList().findMarkBefore(this.endtime);
	    in.seek(lastseg.getBytes());
	    buf = "";  // clear the buf so we don't get something we read before we sought
	    // number of entries skipped to get to the end of the iterator, less the number skipped to get to the start
	    long count = lastseg.getEntriesSkipped() - skippedAtStart; 

	    while ((e = readNextEntry()) != null) {
		if (LOG.isTraceEnabled()) {
		    //LOG.trace(e);
		}
		if (e.getTimestamp() > this.endtime) {
		    break;
		}
		count++;
	    }
	    in.seek(pos);
	    buf = "";

	    if (LOG.isTraceEnabled()) {
		LOG.trace("size() = " + count);
	    }
	    
	    return count;
	}

	synchronized private LogEntry readNextEntry() {
	    try {
		try {
		    while (true) {
			String line = in.readLine();
			if (line == null) {
			    break;
			}

			Matcher m = src.timep.matcher(line);
			if (m.lookingAt()) {
			    if (buf.length() > 0) {
				LogEntry e = new Log4JEntry(src.timestampFromText(dateformat, buf), src.getServerId(), buf);
				buf = line;
				return e;
			    }
			    buf = line;
			} else if (buf.length() > 0) {
			    buf += line + "\n";
			}
		    }
		} catch (EOFException eof) {
		    // ignore, we've simply come to the end of the file
		}
		if (buf.length() > 0) {
		    LogEntry e = new Log4JEntry(src.timestampFromText(dateformat, buf), src.getServerId(), buf);
		    buf = "";
		    return e;
		}
	    } catch (Exception e) {
		LOG.error("Error reading next entry in file (" + src.file + "): " + e);
		return null;
	    }
	    return null;
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
		    throw new NoSuchElementException(e.toString());
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
	    throw new UnsupportedOperationException("remove not supported for L4J logs");
	}
	
	public void close() throws IOException {
	    in.close();
	}
	
	public String toString() {
	    String size;
	    try {
		size = new Long(size()).toString();
	    } catch (IOException ioe) {
		size = "Unable to read";
	    }
	    return "Log4JSourceIterator(start=" + starttime + ", end=" + endtime + ", size=" + size + ")";
	}
    }

    public LogIterator iterator(long starttime, long endtime) throws IllegalArgumentException {
	try {
	    return iterator(starttime, endtime, null);
	} catch (FilterException fe) {
	    assert(false); //"This should never happen, you can't have a filter exception without a filter");
	    return null;
	}
    }

    public LogIterator iterator(long starttime, long endtime, FilterOp filter) throws IllegalArgumentException, FilterException{
	// sanitise start and end times
	if (endtime < starttime) {
	    throw new IllegalArgumentException("End time (" +  endtime + ") must be greater or equal to starttime (" + starttime + ")");
	}

	return new Log4JSourceIterator(this, starttime, endtime, filter);
    }

    public LogIterator iterator() throws IllegalArgumentException {
	return iterator(starttime, endtime+1);
    }
    
    public Log4JSource(String file) throws IOException {
	this.file=file;
	
	timep = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3})");
	skiplist = new LogSkipList();
	init();
    }
    
    private static long timestampFromText(SimpleDateFormat format, String s) {
	Date d = null;
	try {
	    d = format.parse(s);
	} catch (ParseException e) {
	    return 0;
	}
	Calendar c = new GregorianCalendar();
	c.setTime(d);
	return c.getTimeInMillis();
    }

    private void init() throws IOException {
	File f = new File(file);
	RandomAccessFileReader in = new RandomAccessFileReader(f);
	SimpleDateFormat dateformat = new SimpleDateFormat(DATE_FORMAT);
	Pattern idp = Pattern.compile("\\[myid:(\\d+)\\]");

	long lastFp = in.getPosition();
	String line = in.readLine();
	Matcher m = null;

	// if we have read data from the file, and it matchs the timep pattern
	if ((line != null) && (m = timep.matcher(line)).lookingAt()) {
	    starttime = timestampFromText(dateformat, m.group(1));
	} else {
	    throw new IOException("Invalid log4j format. First line doesn't start with time");
	}

	/*
	  Count number of log entries. Any line starting with a timestamp counts as an entry
	*/
	String lastentry = line;
	try {
	    while (line != null) {
		m = timep.matcher(line);
		if (m.lookingAt()) {
		    if (size % skipN == 0) {
			long time = timestampFromText(dateformat, m.group(1));
			skiplist.addMark(time, lastFp, size);
		    }
		    size++;
		    lastentry = line;
		} 
		if (serverid == 0 && (m = idp.matcher(line)).find()) {
		    serverid = Integer.valueOf(m.group(1));
		}

		lastFp = in.getPosition();
		line = in.readLine();
	    }
	} catch (EOFException eof) {
	    // ignore, simply end of file, though really (line!=null) should have caught this
	} finally {
	    in.close();
	}

	m = timep.matcher(lastentry);
	if (m.lookingAt()) {
	    endtime = timestampFromText(dateformat, m.group(1));
	} else {
	    throw new IOException("Invalid log4j format. Last line doesn't start with time");
	}
    }
    
    public String toString() {
	return "Log4JSource(file=" + file + ", size=" + size + ", start=" + starttime + ", end=" + endtime +", id=" + serverid +")";
    }

    public static void main(String[] args) throws IOException {
	final Log4JSource s = new Log4JSource(args[0]);
	System.out.println(s);

	LogIterator iter;

	if (args.length == 3) {
	    final long starttime = Long.valueOf(args[1]);
	    final long endtime = Long.valueOf(args[2]);
	    iter = s.iterator(starttime, endtime);
	    
	    Thread t1 = new Thread() { public void run () { 
		
		LogIterator iter = s.iterator(starttime, endtime);
		System.out.println(iter);
	    }; };
	    Thread t2 = new Thread() { public void run () { 
		
		LogIterator iter = s.iterator(starttime, endtime);
		System.out.println(iter);
	    }; };
	    Thread t3 = new Thread() { public void run () { 
		
		LogIterator iter = s.iterator(starttime, endtime);
		System.out.println(iter);
	    }; };
	    t1.start();
	    t2.start();
	    //	    t3.start();
	} else {
	    iter = s.iterator();
	}

	/*while (iter.hasNext()) {
	    System.out.println(iter.next());
	    }*/
	iter.close();
    }

    public int getServerId() {
	return serverid;
    }
}
