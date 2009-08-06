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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import org.apache.jute.Record;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.ReplyHeader;

public interface ServerCnxn extends Watcher {
    
    /**
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    final static int ruokCmd = ByteBuffer.wrap("ruok".getBytes()).getInt();

    final static int dumpCmd = ByteBuffer.wrap("dump".getBytes()).getInt();
    
    final static int statCmd = ByteBuffer.wrap("stat".getBytes()).getInt();
    
    final static int reqsCmd = ByteBuffer.wrap("reqs".getBytes()).getInt();

    final static int setTraceMaskCmd = ByteBuffer.wrap("stmk".getBytes())
            .getInt();
    
    final static int getTraceMaskCmd = ByteBuffer.wrap("gtmk".getBytes())
            .getInt();
    
    final static int enviCmd = ByteBuffer.wrap("envi".getBytes()).getInt();
    
    final static int srstCmd = ByteBuffer.wrap("srst".getBytes()).getInt();
    
    final static ByteBuffer imok = ByteBuffer.wrap("imok".getBytes());

    // This is just an arbitrary object to represent requests issued by
    // (aka owned by) this class
    final public static Object me = new Object();

    public abstract int getSessionTimeout();

    public abstract void close();

    public abstract void sendResponse(ReplyHeader h, Record r, String tag)
            throws IOException;

    public void finishSessionInit(boolean valid);

    public abstract void process(WatchedEvent event);

    public abstract long getSessionId();

    public abstract void setSessionId(long sessionId);

    public abstract ArrayList<Id> getAuthInfo();

    public InetSocketAddress getRemoteAddress();
    
    public interface Stats{
        public long getOutstandingRequests();
        public long getPacketsReceived();
        public long getPacketsSent();
    }
    
    public Stats getStats();
}
