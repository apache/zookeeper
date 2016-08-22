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
package org.apache.zookeeper.graph.filterops;

import org.apache.zookeeper.graph.LogEntry;
import org.apache.zookeeper.graph.FilterOp;
import org.apache.zookeeper.graph.FilterException;

public class LessThanOp extends FilterOp {
    public boolean matches(LogEntry entry) throws FilterException {
	Arg first = args.get(0);
	
	if (first != null) {
	    FilterOp.ArgType type = first.getType();
	    if (type == FilterOp.ArgType.SYMBOL) {
		String key = (String)first.getValue();
		Object v = entry.getAttribute(key);
		if (v instanceof String) {
		    type = FilterOp.ArgType.STRING;
		} else if (v instanceof Double || v instanceof Long || v instanceof Integer || v instanceof Short) {
		    type = FilterOp.ArgType.NUMBER;
		} else {
		    throw new FilterException("LessThanOp: Invalid argument, first argument resolves to neither a String nor a Number");
		}
	    }
	    
	    Object last = null;
	    for (Arg a : args) {
		Object v = a.getValue();
		if (a.getType() == FilterOp.ArgType.SYMBOL) {
		    String key = (String)a.getValue();
		    v = entry.getAttribute(key);
		}

		if (last != null) {
		    if (type == FilterOp.ArgType.STRING) {
			if (((String)last).compareTo((String)v) >= 0) {
			    return false;
			}
		    } else if (type == FilterOp.ArgType.NUMBER) {
			if (((Number)last).doubleValue() >= ((Number)v).doubleValue()) {
			    return false;
			}
		    }
		}
		last = v;
	    }
	    return true;
	} else { 
	    return true; 
	}
    }
	
}    
