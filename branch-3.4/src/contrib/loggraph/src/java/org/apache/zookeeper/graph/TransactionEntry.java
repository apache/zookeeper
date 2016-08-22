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

public class TransactionEntry extends LogEntry {
    public TransactionEntry(long timestamp, long clientId, long Cxid, long Zxid, String op) {
	this(timestamp, clientId, Cxid, Zxid, op, "");
    }

    public TransactionEntry(long timestamp, long clientId, long Cxid, long Zxid, String op, String extra) {
	super(timestamp);
	setAttribute("client-id", new Long(clientId));
	setAttribute("cxid", new Long(Cxid));
	setAttribute("zxid", new Long(Zxid));
	setAttribute("operation", op);
	setAttribute("extra", extra);
    }

    public long getClientId() {
	return (Long)getAttribute("client-id");
    }

    public long getCxid() {
	return (Long)getAttribute("cxid");
    }

    public long getZxid() {
	return (Long)getAttribute("zxid");
    }

    public String getOp() {
	return (String)getAttribute("operation");
    }

    public String getExtra() {
	return (String)getAttribute("extra");
    }

    public String toString() {
	return getTimestamp() + ":::session(0x" + Long.toHexString(getClientId()) + ") cxid(0x" + Long.toHexString(getCxid()) + ") zxid(0x" + Long.toHexString(getZxid()) + ") op(" + getOp() + ") extra(" + getExtra() +")";
    }

    public Type getType() { return LogEntry.Type.TXN; }
}
