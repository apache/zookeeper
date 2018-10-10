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

import java.io.File;
import java.io.IOException;
import java.io.FileNotFoundException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.apache.zookeeper.graph.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class NumEvents extends JsonServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(NumEvents.class);
    private static final int DEFAULT_PERIOD = 1000;

    private LogSource source = null;

    public NumEvents(LogSource src) throws Exception {
	this.source = src;
    }

    String handleRequest(JsonRequest request) throws Exception {
	String output = "";

	long starttime = 0;
	long endtime = 0;
	long period = 0;

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
	
	LogIterator iter = source.iterator(starttime, endtime);
	JSONObject data = new JSONObject();
	data.put("startTime", starttime);
	data.put("endTime", endtime);
	long size = 0;
	
	size = iter.size();
	
	data.put("numEntries",  size);
	if (LOG.isDebugEnabled()) {
	    LOG.debug("handle(start= " + starttime + ", end=" + endtime + ", numEntries=" + size +")");
	}
	iter.close();
	return JSONValue.toJSONString(data);
    }
}

