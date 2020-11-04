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
import java.util.HashSet;
import java.util.Set;
import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.zookeeper.graph.*;

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
		long startTime = 0;
		long endTime = 0;
		long period = 0;
		long scale = 0;

		startTime = request.getNumber("start", 0);
		endTime = request.getNumber("end", 0);
		period = request.getNumber("period", 0);


		if (startTime == 0) { startTime = source.getStartTime(); }
		if (endTime == 0) {
			if (period > 0) {
			endTime = startTime + period;
			} else {
			endTime = source.getEndTime();
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

		LogIterator iter = source.iterator(startTime, endTime);
		String jsonString = getJSON(iter, scale);
		iter.close();
		return jsonString;
	}

	protected String getJSON(final LogIterator iter, final long scale) throws IOException {
		long current = 0;
		long currentms = 0;
		Set<Long> zxids_ms = new HashSet<Long>();
		long zxidCount = 0;

		ObjectMapper mapper = new ObjectMapper();
		ArrayNode events = mapper.createArrayNode();

		while (iter.hasNext()) {
			LogEntry e = iter.next();
			if (e.getType() != LogEntry.Type.TXN) {
			continue;
			}

			TransactionEntry cxn = (TransactionEntry)e;

			long ms = cxn.getTimestamp();
			long inscale = ms/ scale;

			if (currentms != ms && currentms != 0) {
				zxidCount += zxids_ms.size();
				zxids_ms.clear();
			}

			if (inscale != current && current != 0) {
				JsonNode node = mapper.createObjectNode();
				((ObjectNode) node).put("time", current * scale);
				((ObjectNode) node).put("count", zxidCount);
				events.add(node);
				zxidCount = 0;
			}
			current = inscale;
			currentms = ms;

			zxids_ms.add(cxn.getZxid());
		}

		JsonNode node = mapper.createObjectNode();
		((ObjectNode) node).put("time", current * scale);
		((ObjectNode) node).put("count", zxidCount);
		events.add(node);

		String jsonString = mapper.writer(new MinimalPrettyPrinter()).writeValueAsString(events);
		return jsonString;
	}
}
