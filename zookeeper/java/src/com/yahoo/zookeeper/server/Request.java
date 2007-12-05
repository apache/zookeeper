/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import com.yahoo.jute.Record;
import com.yahoo.zookeeper.ZooDefs.OpCode;
import com.yahoo.zookeeper.data.Id;
import com.yahoo.zookeeper.txn.TxnHeader;

/**
 * This is the structure that represents a request moving through a chain of
 * RequestProcessors. There are various pieces of information that is tacked
 * onto the request as it is processed.
 */
public class Request {
    public final static Request requestOfDeath = new Request(null, 0, 0, 0,
            null, null);

    /**
     * @param cnxn
     * @param sessionId
     * @param xid
     * @param type
     * @param bb
     */
    public Request(ServerCnxn cnxn, long sessionId, int xid, int type,
            ByteBuffer bb, ArrayList<Id> authInfo) {
        this.cnxn = cnxn;
        this.sessionId = sessionId;
        this.cxid = xid;
        this.type = type;
        this.request = bb;
        this.authInfo = authInfo;
    }

    public long sessionId;

    public int cxid;

    public int type;

    public ByteBuffer request;

    public ServerCnxn cnxn;

    public TxnHeader hdr;

    public Record txn;

    public long zxid = -1;

    public ArrayList<Id> authInfo;

    public long createTime = System.currentTimeMillis();

    /**
     * is the packet type a valid packet in zookeeper
     * 
     * @param type
     *                the type of the packet
     * @return true if a valid packet, false if not
     */
    static boolean isValid(int type) {
        // make sure this is always synchronized with Zoodefs!!
        switch (type) {
        case OpCode.notification:
            return false;
        case OpCode.create:
        case OpCode.delete:
        case OpCode.createSession:
        case OpCode.exists:
        case OpCode.getData:
        case OpCode.setData:
        case OpCode.sync:
        case OpCode.getACL:
        case OpCode.setACL:
        case OpCode.getChildren:
        case OpCode.ping:
        case OpCode.closeSession:
            return true;
        default:
            return false;
        }
    }

    static String op2String(int op) {
        switch (op) {
        case OpCode.notification:
            return "notification";
        case OpCode.create:
            return "create";
        case OpCode.delete:
            return "delete";
        case OpCode.exists:
            return "exists";
        case OpCode.getData:
            return "getDate";
        case OpCode.setData:
            return "setData";
        case OpCode.sync:
          	return "sync:";
        case OpCode.getACL:
            return "getACL";
        case OpCode.setACL:
            return "setACL";
        case OpCode.getChildren:
            return "getChildren";
        case OpCode.ping:
            return "ping";
        case OpCode.createSession:
            return "createSession";
        case OpCode.closeSession:
            return "closeSession";
        case OpCode.error:
            return "error";
        default:
            return "unknown " + op;
        }
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append(Long.toHexString(sessionId)).append(" ");
        sb.append(Long.toHexString(cxid)).append(" ");
        sb.append(Long.toHexString((hdr == null ? -2 : hdr.getZxid()))).append(
                " ");
        sb
                .append(
                        " txn type = "
                                + (hdr == null ? "unknown" : "" + hdr.getType()))
                .append(" ");
        sb.append(op2String(type)).append(" ");

        String path = "n/a";
        if (type != OpCode.createSession) {
            try {
                request.clear();
                int pathLen = request.getInt();
                byte b[] = new byte[pathLen];
                request.get(b);
                path = new String(b);
                request.clear();
            } catch (Exception e) {

            }
        }
        sb.append(path).append(" ");
        return sb.toString();
    }
}
