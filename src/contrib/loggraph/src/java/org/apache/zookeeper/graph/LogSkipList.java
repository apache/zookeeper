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

import java.util.List;
import java.util.LinkedList;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
Generic skip list for holding a rough index of a log file. When the log file is loaded, this 
index is built by adding a mark every n entries. Then when a specific time position is requested
from the file, a point at most n-1 entries before the time position can be jumped to.

*/
public class LogSkipList {
    private static final Logger LOG = LoggerFactory.getLogger(LogSkipList.class);
    
    private LinkedList<Mark> marks;

    public class Mark {
	private long time;
	private long bytes;
	private long skipped;

	public Mark(long time, long bytes, long skipped) {
	    this.time = time;
	    this.bytes = bytes;
	    this.skipped = skipped;
	}

	public long getTime() { return this.time; }
	public long getBytes() { return this.bytes; }
	public long getEntriesSkipped() { return this.skipped; }

	public String toString() {
	    return "Mark(time=" + time + ", bytes=" + bytes + ", skipped=" + skipped + ")";
	}
    };

    public LogSkipList() {
	if (LOG.isTraceEnabled()) {
	    LOG.trace("New skip list");
	}
	marks = new LinkedList<Mark>();
    }

    public void addMark(long time, long bytes, long skipped) {
	if (LOG.isTraceEnabled()) {
	    LOG.trace("addMark (time:" + time + ", bytes: " + bytes + ", skipped: " + skipped + ")");
	}
	marks.add(new Mark(time, bytes, skipped));
    }

    /** 
	Find the last mark in the skip list before time.
     */
    public Mark findMarkBefore(long time) throws NoSuchElementException {
	if (LOG.isTraceEnabled()) {
	    LOG.trace("findMarkBefore(" + time + ")");
	}
		    
	Mark last = marks.getFirst();
	for (Mark m: marks) {
	    if (m.getTime() > time) {
		break;
	    } 
	    last = m;
	}
	
	if (LOG.isTraceEnabled()) {
	    LOG.trace("return " + last );
	}
	
	return last;
    }

};
