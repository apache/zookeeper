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

package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.test.ClientBase;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public class ZabUtils {

    private ZabUtils() {}

    public static final int SYNC_LIMIT = 2;

    public static QuorumPeer createQuorumPeer(File tmpDir) throws IOException, FileNotFoundException {
        HashMap<Long, QuorumPeer.QuorumServer> peers = new HashMap<Long, QuorumPeer.QuorumServer>();
        QuorumPeer peer = QuorumPeer.testingQuorumPeer();
        peer.syncLimit = SYNC_LIMIT;
        peer.initLimit = 2;
        peer.tickTime = 2000;

        peers.put(0L, new QuorumPeer.QuorumServer(
                0, new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                new InetSocketAddress("127.0.0.1", PortAssignment.unique())));
        peers.put(1L, new QuorumPeer.QuorumServer(
                1, new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                new InetSocketAddress("127.0.0.1", PortAssignment.unique())));
        peers.put(2L, new QuorumPeer.QuorumServer(
                2, new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                new InetSocketAddress("127.0.0.1", PortAssignment.unique()),
                new InetSocketAddress("127.0.0.1", PortAssignment.unique())));

        peer.setQuorumVerifier(new QuorumMaj(peers), false);
        peer.setCnxnFactory(new NullServerCnxnFactory());
        File version2 = new File(tmpDir, "version-2");
        version2.mkdir();
        ClientBase.createInitializeFile(tmpDir);
        FileOutputStream fos = new FileOutputStream(new File(version2, "currentEpoch"));
        fos.write("0\n".getBytes());
        fos.close();
        fos = new FileOutputStream(new File(version2, "acceptedEpoch"));
        fos.write("0\n".getBytes());
        fos.close();
        return peer;
    }

    public static Leader createLeader(File tmpDir, QuorumPeer peer)
            throws IOException, NoSuchFieldException, IllegalAccessException, X509Exception {
        LeaderZooKeeperServer zk = prepareLeader(tmpDir, peer);
        return new Leader(peer, zk);
    }

    public static Leader createMockLeader(File tmpDir, QuorumPeer peer)
            throws IOException, NoSuchFieldException, IllegalAccessException, X509Exception {
        LeaderZooKeeperServer zk = prepareLeader(tmpDir, peer);
        return new MockLeader(peer, zk);
    }

    private static LeaderZooKeeperServer prepareLeader(File tmpDir, QuorumPeer peer)
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileTxnSnapLog logFactory = new FileTxnSnapLog(tmpDir, tmpDir);
        peer.setTxnFactory(logFactory);
        ZKDatabase zkDb = new ZKDatabase(logFactory);
        LeaderZooKeeperServer zk = new LeaderZooKeeperServer(logFactory, peer, zkDb);
        return zk;
    }

    private static final class NullServerCnxnFactory extends ServerCnxnFactory {
        public void startup(ZooKeeperServer zkServer, boolean startServer)
                throws IOException, InterruptedException {
        }
        public void start() {
        }
        public void shutdown() {
        }
        public void setMaxClientCnxnsPerHost(int max) {
        }
        public void join() throws InterruptedException {
        }
        public int getMaxClientCnxnsPerHost() {
            return 0;
        }
        public int getSocketListenBacklog() {
            return -1;
        }
        public int getLocalPort() {
            return 0;
        }
        public InetSocketAddress getLocalAddress() {
            return null;
        }
        public Iterable<ServerCnxn> getConnections() {
            return null;
        }
        public void configure(InetSocketAddress addr, int maxcc, int listenBacklog,
                boolean secure) throws IOException {
        }

        public boolean closeSession(long sessionId) {
            return false;
        }
        public void closeAll() {
        }
        @Override
        public int getNumAliveConnections() {
            return 0;
        }
        @Override
        public void reconfigure(InetSocketAddress addr) {
        }
        @Override
        public void resetAllConnectionStats() {
        }
        @Override
        public Iterable<Map<String, Object>> getAllConnectionInfo(boolean brief) {
            return null;
        }
    }

    public static final class MockLeader extends Leader {

        MockLeader(QuorumPeer qp, LeaderZooKeeperServer zk)
                throws IOException, X509Exception {
            super(qp, zk);
        }

        /**
         * This method returns the value of the variable that holds the epoch
         * to be proposed and that has been proposed, depending on the point
         * of the execution in which it is called.
         *
         * @return epoch
         */
        public long getCurrentEpochToPropose() {
            return epoch;
        }
    }
}
