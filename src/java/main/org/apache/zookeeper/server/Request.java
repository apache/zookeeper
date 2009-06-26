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

package org.apache.zookeeper.server;

import java.nio.ByteBuffer;
import java.util.List;

import org.apache.jute.Record;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * This is the structure that represents a request moving through a chain of
 * RequestProcessors. There are various pieces of information that is tacked
 * onto the request as it is processed.
 */
public class Request {
    private static final Logger LOG = Logger.getLogger(Request.class);

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
            ByteBuffer bb, List<Id> authInfo) {
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

    public List<Id> authInfo;

    public long createTime = System.currentTimeMillis();
    
    private Object owner;
    
    private KeeperException e;
    
    public Object getOwner() {
        return owner;
    }
    
    public void setOwner(Object owner) {
        this.owner = owner;
    }

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
        case OpCode.setWatches:
            return true;
        default:
            return false;
        }
    }

    static boolean isQuorum(int type) {
        switch (type) {
        case OpCode.exists:
        case OpCode.getACL:
        case OpCode.getChildren:
        case OpCode.getData:
            return false;
        case OpCode.error:
        case OpCode.closeSession:
        case OpCode.create:
        case OpCode.createSession:
        case OpCode.delete:
        case OpCode.setACL:
        case OpCode.setData:
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
        case OpCode.setWatches:
            return "setWatches";
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

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("sessionid:0x").append(Long.toHexString(sessionId));
        sb.append(" type:").append(op2String(type));
        sb.append(" cxid:0x").append(Long.toHexString(cxid));
        sb.append(" zxid:0x").append(Long.toHexString((hdr == null ?
                -2 : hdr.getZxid())));
        sb.append(" txntype:" + (hdr == null ?
                "unknown" : "" + hdr.getType()));
        sb.append(" ");

        String path = "n/a";
        if (type != OpCode.createSession && request != null
                && request.remaining() >= 4)
        {
            try {
                request.clear();
                int pathLen = request.getInt();
                byte b[] = new byte[pathLen];
                request.get(b);
                path = new String(b);
                request.clear();
            } catch (Exception e) {
                LOG.warn("Ignoring exception during toString", e);
            }
        }
        sb.append(path).append(" ");

        return sb.toString();
    }

    public void setException(KeeperException e) {
        this.e = e;
    }
	
    public KeeperException getException() {
        return e;
    }
}
