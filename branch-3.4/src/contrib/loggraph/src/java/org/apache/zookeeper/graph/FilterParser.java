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

import java.io.PushbackReader;
import java.io.StringReader;
import java.io.IOException;
import java.util.ArrayList;

import org.apache.zookeeper.graph.filterops.*;

public class FilterParser {
    private PushbackReader reader;

    public FilterParser(String s) {
	reader = new PushbackReader(new StringReader(s));
    }

    private String readUntilSpace() throws IOException {
	StringBuffer buffer = new StringBuffer();

	int c = reader.read();
	while (!Character.isWhitespace(c) && c != ')' && c != '(') {
	    buffer.append((char)c);
	    c = reader.read();
	    if (c == -1) {
		break;
	    }
	}	
	reader.unread(c);

	return buffer.toString().trim();
    }

    private StringArg readStringArg() throws IOException, FilterException {
	int c = reader.read();
	int last = 0;
	if (c != '"') {
	    throw new FilterException("Check the parser, trying to read a string that doesn't begin with quotes");
	}
	StringBuffer buffer = new StringBuffer();
	while (reader.ready()) {
	    last = c;
	    c = reader.read();
	    if (c == -1) {
		break;
	    }
	    
	    if (c == '"' && last != '\\') {
		return new StringArg(buffer.toString());
	    } else {
		buffer.append((char)c);
	    }
	}
	throw new FilterException("Unterminated string");
    }

    private NumberArg readNumberArg() throws IOException, FilterException {
	String strval = readUntilSpace();
	
	try {
	    if (strval.startsWith("0x")) {
		return new NumberArg(Long.valueOf(strval.substring(2), 16));
	    } else {
		return new NumberArg(Long.valueOf(strval));
	    }
	} catch (NumberFormatException e) {
	    throw new FilterException("Not a number [" + strval + "]\n" + e);
	}
    }

    private SymbolArg readSymbolArg() throws IOException, FilterException {
	return new SymbolArg(readUntilSpace());
    }

    public FilterOp parse() throws IOException, FilterException {
	int c = reader.read();
	if (c != '(') {
	    throw new FilterException("Invalid format");
	}

	String opstr = readUntilSpace();
	FilterOp op = FilterOp.newOp(opstr);

	while (reader.ready()) {
	    c = reader.read();
	    if (c == -1) {
		break;
	    }
	    if (c == '(') {
		reader.unread(c);
		op.addSubOp(parse());
	    } else if (c == ')') {
		return op;
	    } else if (c == '"') {
		reader.unread(c);
		op.addArg(readStringArg());
	    } else if (Character.isDigit(c) || c == '-' || c == '+') {
		reader.unread(c);
		op.addArg(readNumberArg());
	    } else if (Character.isJavaIdentifierStart(c)) {
		reader.unread(c);
		op.addArg(readSymbolArg());
	    }
	}
	throw new FilterException("Incomplete filter");
    }

    public static void main(String[] args) throws IOException, FilterException {
	if (args.length == 1) {
	    System.out.println(new FilterParser(args[0]).parse());
	} else {
	    System.out.println(new FilterParser("(or (and (= session foobar) (= session barfoo)) (= session sdfs))").parse());
	}
    }
};
