/*
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
import java.security.cert.Certificate;
import java.util.List;
import org.apache.jute.Record;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.ReplyHeader;

/**
 * A empty watcher implementation used in bench and unit test.
 */
public class DumbWatcher extends ServerCnxn {

    private long sessionId;

    public DumbWatcher() {
        this(0);
    }

    public DumbWatcher(long sessionId) {
        super(null);
        this.sessionId = sessionId;
    }

    @Override
    void setSessionTimeout(int sessionTimeout) {
    }

    @Override
    public void process(WatchedEvent event, List<ACL> znodeAcl) {
    }

    @Override
    int getSessionTimeout() {
        return 0;
    }

    @Override
    public void close(DisconnectReason reason) {
    }

    @Override
    public int sendResponse(ReplyHeader h, Record r, String tag,
                             String cacheKey, Stat stat, int opCode) throws IOException {
        return 0;
    }

    @Override
    public void sendCloseSession() {
    }

    @Override
    public long getSessionId() {
        return sessionId;
    }

    @Override
    void setSessionId(long sessionId) {
    }

    @Override
    void sendBuffer(ByteBuffer... closeConn) {
    }

    @Override
    void enableRecv() {
    }

    @Override
    void disableRecv(boolean waitDisableRecv) {
    }

    @Override
    protected ServerStats serverStats() {
        return null;
    }

    @Override
    public long getOutstandingRequests() {
        return 0;
    }

    @Override
    public InetSocketAddress getRemoteSocketAddress() {
        return null;
    }

    @Override
    public int getInterestOps() {
        return 0;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public Certificate[] getClientCertificateChain() {
        return null;
    }

    @Override
    public void setClientCertificateChain(Certificate[] chain) {
    }

}
