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

import org.apache.zookeeper.graph.*;

public class FileLoader extends JsonServlet
{
    private MergedLogSource source = null;
    
    public FileLoader(MergedLogSource src) throws Exception {
	source = src;
    }

    String handleRequest(JsonRequest request) throws Exception
    {
	String output = "";
		
	String file = request.getString("path", "/");
	JSONObject o = new JSONObject();
	try {
	    this.source.addSource(file);
	    o.put("status", "OK");
	
	} catch (Exception e) {
	    o.put("status", "ERR");
	    o.put("error",  e.toString());
	}
	
	return JSONValue.toJSONString(o);
    }
}
