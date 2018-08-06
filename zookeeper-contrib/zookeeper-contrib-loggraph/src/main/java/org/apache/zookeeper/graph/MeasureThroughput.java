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

import java.io.IOException;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;

import java.util.HashSet;

public class MeasureThroughput {
    private static final int MS_PER_SEC = 1000;
    private static final int MS_PER_MIN = MS_PER_SEC*60;
    private static final int MS_PER_HOUR = MS_PER_MIN*60;
    
    public static void main(String[] args) throws IOException {	
	MergedLogSource source = new MergedLogSource(args);

	PrintStream ps_ms = new PrintStream(new BufferedOutputStream(new FileOutputStream("throughput-ms.out")));
	PrintStream ps_sec = new PrintStream(new BufferedOutputStream(new FileOutputStream("throughput-sec.out")));
	PrintStream ps_min = new PrintStream(new BufferedOutputStream(new FileOutputStream("throughput-min.out")));
	PrintStream ps_hour = new PrintStream(new BufferedOutputStream(new FileOutputStream("throughput-hour.out")));
	LogIterator iter;
	
	System.out.println(source);
	iter = source.iterator();
	long currentms = 0;
	long currentsec = 0;
	long currentmin = 0;
	long currenthour = 0;
	HashSet<Long> zxids_ms = new HashSet<Long>();
	long zxid_sec = 0;
	long zxid_min = 0;
	long zxid_hour = 0;

	while (iter.hasNext()) {
	    LogEntry e = iter.next();
	    TransactionEntry cxn = (TransactionEntry)e;
	    
	    long ms = cxn.getTimestamp();
	    long sec = ms/MS_PER_SEC;
	    long min = ms/MS_PER_MIN;
	    long hour = ms/MS_PER_HOUR;

	    if (currentms != ms && currentms != 0) {
		ps_ms.println("" + currentms + " " + zxids_ms.size());

		zxid_sec += zxids_ms.size();
		zxid_min += zxids_ms.size();
		zxid_hour += zxids_ms.size();
		zxids_ms.clear();
	    }

	    if (currentsec != sec && currentsec != 0) {
		ps_sec.println("" + currentsec*MS_PER_SEC + " " + zxid_sec);

		zxid_sec = 0;
	    }

	    if (currentmin != min && currentmin != 0) {
		ps_min.println("" + currentmin*MS_PER_MIN + " " + zxid_min);
		
		zxid_min = 0;
	    }

	    if (currenthour != hour && currenthour != 0) {
		ps_hour.println("" + currenthour*MS_PER_HOUR + " " + zxid_hour);
		
		zxid_hour = 0;
	    }

	    currentms = ms;
	    currentsec = sec;
	    currentmin = min;
	    currenthour = hour;

	    zxids_ms.add(cxn.getZxid());
	}

	iter.close();
	ps_ms.close();
	ps_sec.close();
	ps_min.close();
	ps_hour.close();
    }
};
