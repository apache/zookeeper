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
import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;

abstract public class JsonServlet extends HttpServlet {
    abstract String handleRequest(JsonRequest request) throws Exception;

    protected class JsonRequest {
		private Map map;

		public JsonRequest(ServletRequest request) {
			map = request.getParameterMap();
		}

		public long getNumber(String name, long defaultnum) {
			String[] vals = (String[])map.get(name);
			if (vals == null || vals.length == 0) {
			return defaultnum;
			}

			try {
				return Long.valueOf(vals[0]);
			}
			catch (NumberFormatException e) {
				return defaultnum;
			}
		}

		public String getString(String name, String defaultstr) {
			String[] vals = (String[])map.get(name);
			if (vals == null || vals.length == 0) {
			return defaultstr;
			}
			else {
				return vals[0];
			}
		}
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/plain;charset=utf-8");
        response.setStatus(HttpServletResponse.SC_OK);

		try {
			String req = request.getRequestURI().substring(request.getServletPath().length());

			response.getWriter().println(handleRequest(new JsonRequest(request)));
		}
		catch (Exception e) {
			ObjectMapper mapper = new ObjectMapper();
			JsonNode rootNode = mapper.createObjectNode();
			((ObjectNode) rootNode).put("error", e.toString());
			String jsonString = mapper.writer(new MinimalPrettyPrinter()).writeValueAsString(rootNode);

			response.getWriter().println(jsonString);
		}
		catch (java.lang.OutOfMemoryError oom) {
			ObjectMapper mapper = new ObjectMapper();
			JsonNode rootNode = mapper.createObjectNode();
			((ObjectNode) rootNode).put("error", "Out of memory. Perhaps you've requested too many logs. Try narrowing you're filter criteria.");
			String jsonString = mapper.writer(new MinimalPrettyPrinter()).writeValueAsString(rootNode);

			response.getWriter().println(jsonString);
		}
    }
}
