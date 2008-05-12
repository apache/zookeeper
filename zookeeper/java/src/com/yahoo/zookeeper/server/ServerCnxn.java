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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import com.yahoo.jute.Record;
import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.data.Id;
import com.yahoo.zookeeper.proto.ReplyHeader;
import com.yahoo.zookeeper.proto.WatcherEvent;

public interface ServerCnxn extends Watcher {
    final static int killCmd = ByteBuffer.wrap("kill".getBytes()).getInt();

    final static int ruokCmd = ByteBuffer.wrap("ruok".getBytes()).getInt();

    final static int dumpCmd = ByteBuffer.wrap("dump".getBytes()).getInt();

    final static int statCmd = ByteBuffer.wrap("stat".getBytes()).getInt();

    final static int reqsCmd = ByteBuffer.wrap("reqs".getBytes()).getInt();

    final static int setTraceMaskCmd = ByteBuffer.wrap("stmk".getBytes())
            .getInt();

    final static int getTraceMaskCmd = ByteBuffer.wrap("gtmk".getBytes())
            .getInt();

    final static ByteBuffer imok = ByteBuffer.wrap("imok".getBytes());

    public abstract int getSessionTimeout();

    public abstract void close();

    public abstract void sendResponse(ReplyHeader h, Record r, String tag)
            throws IOException;

    public void finishSessionInit(boolean valid);

    public abstract void process(WatcherEvent event);

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
