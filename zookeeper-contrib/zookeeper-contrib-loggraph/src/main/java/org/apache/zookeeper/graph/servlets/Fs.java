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
import java.io.FileNotFoundException;

import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

public class Fs extends JsonServlet
{
    String handleRequest(JsonRequest request) throws Exception {
		File base = new File(request.getString("path", "/"));
		if (!base.exists() || !base.isDirectory()) {
			throw new FileNotFoundException("Couldn't find [" + request + "]");
		}
		File[] files = base.listFiles();
		Arrays.sort(files, new Comparator<File>() {
			public int compare(File o1, File o2) {
				if (o1.isDirectory() != o2.isDirectory()) {
				if (o1.isDirectory()) {
					return -1;
				} else {
					return 1;
				}
				}
				return o1.getName().compareToIgnoreCase(o2.getName());
			}
			});

		String jsonString = generateJSON(files);
		return jsonString;
    }

    protected static String generateJSON(File[] files) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		ArrayNode fileList = mapper.createArrayNode();

		for (File f : files) {
			JsonNode node = mapper.createObjectNode().objectNode();
			((ObjectNode) node).put("file", f.getName());
			((ObjectNode) node).put("type", f.isDirectory() ? "D" : "F");
			((ObjectNode) node).put("path", f.getCanonicalPath());
			fileList.add(node);
		}

		String jsonString = mapper.writer(new MinimalPrettyPrinter()).writeValueAsString(fileList);
		return jsonString;
	}
}
