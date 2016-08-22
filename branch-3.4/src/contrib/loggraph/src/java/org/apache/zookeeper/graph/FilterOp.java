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

import java.util.ArrayList;
import org.apache.zookeeper.graph.filterops.*;

public abstract class FilterOp {
    protected ArrayList<FilterOp> subOps;
    protected ArrayList<Arg> args;

    public enum ArgType {
	STRING, NUMBER, SYMBOL
    }

    public FilterOp() {
	subOps = new ArrayList<FilterOp>();
	args = new ArrayList<Arg>();
    }

    public static FilterOp newOp(String op) throws FilterException {
	if (op.equals("or")) 
	    return new OrOp();
	if (op.equals("and"))
	    return new AndOp();
	if (op.equals("not"))
	    return new NotOp();
	if (op.equals("xor"))
	    return new XorOp();
	if (op.equals("="))
	    return new EqualsOp();
	if (op.equals("<"))
	    return new LessThanOp();
	if (op.equals(">")) 
	    return new GreaterThanOp();

	throw new FilterException("Invalid operation '"+op+"'");
    }

    public void addSubOp(FilterOp op) {
	subOps.add(op);
    }
    
    public void addArg(Arg arg) {
	args.add(arg); 
    }

    public abstract boolean matches(LogEntry entry) throws FilterException;
    
    public String toString() {
	String op = "(" + getClass().getName();
	for (FilterOp f :  subOps) {
	    op += " " + f;
	}
	for (Arg a : args) {
	    op += " " + a;
	}
	return op + ")";
    }
}
