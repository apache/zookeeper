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

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.DateFormat;
import java.util.Date;

import org.apache.zookeeper.ZooDefs.OpCode;

public class TraceFormatter {

    public static String op2String(int op) {
        switch (op) {
        case OpCode.notification:
            return "notification";
        case OpCode.create:
            return "create";
        case OpCode.create2:
            return "create2";
        case OpCode.createTTL:
            return "createTtl";
        case OpCode.createContainer:
            return "createContainer";
        case OpCode.delete:
            return "delete";
        case OpCode.deleteContainer:
            return "deleteContainer";
        case OpCode.exists:
            return "exists";
        case OpCode.getData:
            return "getDate";
        case OpCode.setData:
            return "setData";
        case OpCode.multi:
            return "multi";
        case OpCode.getACL:
            return "getACL";
        case OpCode.setACL:
            return "setACL";
        case OpCode.getChildren:
            return "getChildren";
        case OpCode.getChildren2:
            return "getChildren2";
        case OpCode.ping:
            return "ping";
        case OpCode.createSession:
            return "createSession";
        case OpCode.closeSession:
            return "closeSession";
        case OpCode.error:
            return "error";
        case OpCode.reconfig:
           return "reconfig";
        default:
            return "unknown " + op;
        }
    }

    /**
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("USAGE: TraceFormatter trace_file");
            System.exit(2);
        }
        FileChannel fc = new FileInputStream(args[0]).getChannel();
        while (true) {
            ByteBuffer bb = ByteBuffer.allocate(41);
            fc.read(bb);
            bb.flip();

            byte app = bb.get();
            long time = bb.getLong();
            long id = bb.getLong();
            int cxid = bb.getInt();
            long zxid = bb.getLong();
            int txnType = bb.getInt();
            int type = bb.getInt();
            int len = bb.getInt();
            bb = ByteBuffer.allocate(len);
            fc.read(bb);
            bb.flip();
            String path = "n/a";
            if (bb.remaining() > 0) {
                if (type != OpCode.createSession) {
                    int pathLen = bb.getInt();
                    byte b[] = new byte[pathLen];
                    bb.get(b);
                    path = new String(b);
                }
            }
            System.out.println(DateFormat.getDateTimeInstance(DateFormat.SHORT,
                    DateFormat.LONG).format(new Date(time))
                    + ": "
                    + (char) app
                    + " id=0x"
                    + Long.toHexString(id)
                    + " cxid="
                    + cxid
                    + " op="
                    + op2String(type)
                    + " zxid=0x"
                    + Long.toHexString(zxid)
                    + " txnType="
                    + txnType
                    + " len="
                    + len + " path=" + path);
        }
    }

}
