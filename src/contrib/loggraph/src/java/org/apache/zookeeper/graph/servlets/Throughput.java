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
package org.apache.zookeeper.graph.servlets;

import java.io.IOException;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

import org.apache.zookeeper.graph.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;


public class Throughput extends JsonServlet
{
    private static final int MS_PER_SEC = 1000;
    private static final int MS_PER_MIN = MS_PER_SEC*60;
    private static final int MS_PER_HOUR = MS_PER_MIN*60;

    private LogSource source = null;

    public Throughput(LogSource src) throws Exception {
	this.source = src; 
    }

    public String handleRequest(JsonRequest request) throws Exception {
	long starttime = 0;
	long endtime = 0;
	long period = 0;
	long scale = 0;
	
	starttime = request.getNumber("start", 0);
	endtime = request.getNumber("end", 0);
	period = request.getNumber("period", 0);
	

	if (starttime == 0) { starttime = source.getStartTime(); }
	if (endtime == 0) { 
	    if (period > 0) {
		endtime = starttime + period;
	    } else {
		endtime = source.getEndTime(); 
	    }
	}
	
	String scalestr = request.getString("scale", "minutes");
	if (scalestr.equals("seconds")) {
	    scale = MS_PER_SEC;
	} else if (scalestr.equals("hours")) {
	    scale = MS_PER_HOUR;
	} else {
	    scale = MS_PER_MIN;
	} 	
	
	LogIterator iter = source.iterator(starttime, endtime);
	
	long current = 0;
	long currentms = 0;
	Set<Long> zxids_ms = new HashSet<Long>();
	long zxidcount = 0;

	JSONArray events = new JSONArray();
	while (iter.hasNext()) {
	    LogEntry e = iter.next();
	    if (e.getType() != LogEntry.Type.TXN) {
		continue;
	    }

	    TransactionEntry cxn = (TransactionEntry)e;
	    
	    long ms = cxn.getTimestamp();
	    long inscale = ms/scale;

	    if (currentms != ms && currentms != 0) {
		zxidcount += zxids_ms.size();
		zxids_ms.clear();
	    }

	    if (inscale != current && current != 0) {
		JSONObject o = new JSONObject();
		o.put("time", current*scale);
		o.put("count", zxidcount);
		events.add(o);
		zxidcount = 0;
	    }
	    current = inscale;
	    currentms = ms;

	    zxids_ms.add(cxn.getZxid());
	}
	JSONObject o = new JSONObject();
	o.put("time", current*scale);
	o.put("count", zxidcount);
	events.add(o);

	iter.close();
	
	return JSONValue.toJSONString(events);
    }

};
