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
import java.util.Arrays;
import java.util.Comparator;

public class Fs extends JsonServlet
{
    String handleRequest(JsonRequest request) throws Exception
    {
	String output = "";
	JSONArray filelist = new JSONArray();

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
	
	for (File f : files) {
	    JSONObject o = new JSONObject();
	    o.put("file", f.getName());
	    o.put("type", f.isDirectory() ? "D" : "F");
	    o.put("path", f.getCanonicalPath());
	    filelist.add(o);
	}
	return JSONValue.toJSONString(filelist);
    }
}
