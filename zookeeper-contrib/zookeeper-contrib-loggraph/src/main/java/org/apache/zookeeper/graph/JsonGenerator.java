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


import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.io.Writer;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;

public class JsonGenerator {
    private JSONObject root;
    private HashSet<Integer> servers;

    private class Message {
	private int from;
	private int to;
	private long zxid;

	public Message(int from, int to, long zxid) {
	    this.from = from;
	    this.to = to;
	    this.zxid = zxid;
	}
	
	public boolean equals(Message m) {
	    return (m.from == this.from 
		    && m.to == this.to
		    && m.zxid == this.zxid);
	}
    };

    public JSONObject txnEntry(TransactionEntry e) {
	JSONObject event = new JSONObject();

	event.put("time", Long.toString(e.getTimestamp()));
	event.put("client", Long.toHexString(e.getClientId()));
	event.put("cxid", Long.toHexString(e.getCxid()));
	event.put("zxid", Long.toHexString(e.getZxid()));
	event.put("op", e.getOp());
	event.put("extra", e.getExtra());
	event.put("type", "transaction");

	return event;
    }

    /**
       Assumes entries are sorted by timestamp.
     */
    public JsonGenerator(LogIterator iter) {
	servers = new HashSet<Integer>();

	Pattern stateChangeP = Pattern.compile("- (LOOKING|FOLLOWING|LEADING)");
	Pattern newElectionP = Pattern.compile("New election. My id =  (\\d+), Proposed zxid = (\\d+)");
	Pattern receivedProposalP = Pattern.compile("Notification: (\\d+) \\(n.leader\\), (\\d+) \\(n.zxid\\), (\\d+) \\(n.round\\), .+ \\(n.state\\), (\\d+) \\(n.sid\\), .+ \\(my state\\)");
	Pattern exceptionP = Pattern.compile("xception");
	
	root = new JSONObject();
	Matcher m = null;
	JSONArray events = new JSONArray();
	root.put("events", events);
	
	long starttime = Long.MAX_VALUE;
	long endtime = 0;

	int leader = 0;
	long curEpoch = 0;
	boolean newEpoch = false;

	while (iter.hasNext()) {
	    LogEntry ent = iter.next();
	    
	    if (ent.getTimestamp() < starttime) {
		starttime = ent.getTimestamp();
	    }
	    if (ent.getTimestamp() > endtime) {
		endtime = ent.getTimestamp();
	    }
	    
	    if (ent.getType() == LogEntry.Type.TXN) {
		events.add(txnEntry((TransactionEntry)ent));
	    } else {
		Log4JEntry e = (Log4JEntry)ent;
		servers.add(e.getNode());
		
		if ((m = stateChangeP.matcher(e.getEntry())).find()) {
		    JSONObject stateChange = new JSONObject();
		    stateChange.put("type", "stateChange");
		    stateChange.put("time", e.getTimestamp());
		    stateChange.put("server", e.getNode());
		    stateChange.put("state", m.group(1));
		    events.add(stateChange);
		    
		    if (m.group(1).equals("LEADING")) {
			leader = e.getNode();
		    }
		} else if ((m = newElectionP.matcher(e.getEntry())).find()) {
		    Iterator<Integer> iterator = servers.iterator();
		    long zxid = Long.valueOf(m.group(2));
		    int count = (int)zxid;// & 0xFFFFFFFFL;
		    int epoch = (int)Long.rotateRight(zxid, 32);// >> 32;
		    
		    if (leader != 0 && epoch > curEpoch) {
			JSONObject stateChange = new JSONObject();
			stateChange.put("type", "stateChange");
			stateChange.put("time", e.getTimestamp());
			stateChange.put("server", leader);
			stateChange.put("state", "INIT");
			events.add(stateChange);
			leader = 0;
		    }
		    
		    while (iterator.hasNext()) {
			int dst = iterator.next();
			if (dst != e.getNode()) {
			    JSONObject msg = new JSONObject();
			    msg.put("type", "postmessage");
			    msg.put("src", e.getNode());
			    msg.put("dst", dst);
			    msg.put("time", e.getTimestamp());
			    msg.put("zxid", m.group(2));
			    msg.put("count", count);
			    msg.put("epoch", epoch);
			    
			    events.add(msg);
			}
		    }
		} else if ((m = receivedProposalP.matcher(e.getEntry())).find()) {
		    // Pattern.compile("Notification: \\d+, (\\d+), (\\d+), \\d+, [^,]*, [^,]*, (\\d+)");//, LOOKING, LOOKING, 2
		    int src = Integer.valueOf(m.group(4));
		    long zxid = Long.valueOf(m.group(2));
		    int dst = e.getNode();
		    long epoch2 = Long.valueOf(m.group(3));
		    
		    int count = (int)zxid;// & 0xFFFFFFFFL;
		    int epoch = (int)Long.rotateRight(zxid, 32);// >> 32;
		    
		    if (leader != 0 && epoch > curEpoch) {
			JSONObject stateChange = new JSONObject();
			stateChange.put("type", "stateChange");
			stateChange.put("time", e.getTimestamp());
			stateChange.put("server", leader);
			stateChange.put("state", "INIT");
			events.add(stateChange);
			leader = 0;
		    }
		    
		    if (src != dst) {
			JSONObject msg = new JSONObject();
			msg.put("type", "delivermessage");
			msg.put("src", src);
			msg.put("dst", dst);
			msg.put("time", e.getTimestamp());
			msg.put("zxid", zxid);
			msg.put("epoch", epoch);
			msg.put("count", count);
			msg.put("epoch2", epoch2);
			
			events.add(msg);
		    }
		} else if ((m = exceptionP.matcher(e.getEntry())).find()) {
		    JSONObject ex = new JSONObject();
		    ex.put("type", "exception");
		    ex.put("server", e.getNode());
		    ex.put("time", e.getTimestamp());
		    ex.put("text", e.getEntry());
		    events.add(ex);
		} 
	    }
	    JSONObject ex = new JSONObject();
	    ex.put("type", "text");
	    ex.put("time", ent.getTimestamp());
	    String txt = ent.toString();
	    ex.put("text", txt);
	    events.add(ex);
	}
	//	System.out.println("pending messages: "+pendingMessages.size());
	root.put("starttime", starttime);
	root.put("endtime", endtime);

	JSONArray serversarray = new JSONArray();
	root.put("servers", serversarray);
	
	Iterator<Integer> iterator = servers.iterator();
	while (iterator.hasNext()) {
	    serversarray.add(iterator.next());
	}
    }

    public String toString() {
	return JSONValue.toJSONString(root);
    }

    public static void main(String[] args) throws Exception {
	MergedLogSource src = new MergedLogSource(args);
	LogIterator iter = src.iterator();
	System.out.println(new JsonGenerator(iter));
    }
}
