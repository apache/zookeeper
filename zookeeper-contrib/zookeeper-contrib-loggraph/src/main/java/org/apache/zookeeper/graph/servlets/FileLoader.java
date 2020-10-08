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

import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.zookeeper.graph.*;

public class FileLoader extends JsonServlet
{
    private MergedLogSource source = null;
    
    public FileLoader(MergedLogSource src) throws Exception {
		source = src;
    }

    String handleRequest(JsonRequest request) throws Exception {
		String file = request.getString("path", "/");
		ObjectMapper mapper = new ObjectMapper();
		JsonNode rootNode = mapper.createObjectNode();
		try {
	    	this.source.addSource(file);
			((ObjectNode) rootNode).put("status", "OK");
		}
		catch (Exception e) {
			((ObjectNode) rootNode).put("status", "ERR");
			((ObjectNode) rootNode).put("error", e.toString());
		}
		String jsonString = mapper.writer(new MinimalPrettyPrinter()).writeValueAsString(rootNode);
		return jsonString;
    }
}
