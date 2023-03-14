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

import org.apache.zookeeper.graph.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GraphData extends JsonServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(GraphData.class);
    private static final int DEFAULT_PERIOD = 1000;

    private LogSource source = null;

    public GraphData(LogSource src) throws Exception {
	this.source = src; 
    }

    String handleRequest(JsonRequest request) throws Exception {
	

	long starttime = 0;
	long endtime = 0;
	long period = 0;
	FilterOp fo = null;

	starttime = request.getNumber("start", 0);
	endtime = request.getNumber("end", 0);
	period = request.getNumber("period", 0);
	String filterstr = request.getString("filter", "");

	if (filterstr.length() > 0) {
	    fo = new FilterParser(filterstr).parse();
	}
	
	if (starttime == 0) { starttime = source.getStartTime(); }
	if (endtime == 0) { 
	    if (period > 0) {
		endtime = starttime + period;
	    } else {
		endtime = starttime + DEFAULT_PERIOD; 
	    }
	}

	if (LOG.isDebugEnabled()) {
	    LOG.debug("handle(start= " + starttime + ", end=" + endtime + ", period=" + period + ")");
	}
	
	LogIterator iterator = (fo != null) ? 
	    source.iterator(starttime, endtime, fo) : source.iterator(starttime, endtime);
	return new JsonGenerator(iterator).toString();
    }
}
