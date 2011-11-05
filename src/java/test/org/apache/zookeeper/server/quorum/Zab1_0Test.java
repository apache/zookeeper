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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.ZKUtil;
import org.apache.zookeeper.server.ByteBufferOutputStream;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooKeeperServer.DataTreeBuilder;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.LearnerInfo;
import org.apache.zookeeper.server.quorum.QuorumPacket;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.Zab1_0Test.LeaderConversation;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.junit.Assert;
import org.junit.Test;

public class Zab1_0Test {
    private static final class LeadThread extends Thread {
        private final Leader leader;

        private LeadThread(Leader leader) {
            this.leader = leader;
        }

        public void run() {
            try {
                leader.lead();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                leader.shutdown("lead ended");
            }
        }
    }

   public static final class FollowerMockThread extends Thread {
    	private final Leader leader;
    	private final long followerSid;
    	public long epoch = -1;
    	public String msg = null;
    	private boolean onlyGetEpochToPropose;
    	
    	private FollowerMockThread(long followerSid, Leader leader, boolean onlyGetEpochToPropose) {
            this.leader = leader;
            this.followerSid = followerSid;
            this.onlyGetEpochToPropose = onlyGetEpochToPropose;
        }

        public void run() {
            if (onlyGetEpochToPropose) {
	            try {
	            	epoch = leader.getEpochToPropose(followerSid, 0);
	            } catch (Exception e) {
	            }
            } else {	            
	            try{
	                leader.waitForEpochAck(followerSid, new StateSummary(0, 0)); 
	                msg = "FollowerMockThread (id = " + followerSid + ")  returned from waitForEpochAck";      
	            } catch (Exception e) {	            	
	            }
            }
        }       
    }
    @Test
    public void testLeaderInConnectingFollowers() throws Exception {    
        File tmpDir = File.createTempFile("test", "dir");
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        try {
            QuorumPeer peer = createQuorumPeer(tmpDir);
            leader = createLeader(tmpDir, peer);
            peer.leader = leader;
            peer.setAcceptedEpoch(5);
            
            FollowerMockThread f1 = new FollowerMockThread(1, leader, true);
            FollowerMockThread f2 = new FollowerMockThread(2, leader, true);
            f1.start();
            f2.start();
            
            // wait until followers time out in getEpochToPropose - they shouldn't return
            // normally because the leader didn't execute getEpochToPropose and so its epoch was not
            // accounted for
            f1.join(leader.self.getInitLimit()*leader.self.getTickTime() + 5000);
            f2.join(leader.self.getInitLimit()*leader.self.getTickTime() + 5000);
                
            // even though followers timed out, their ids are in connectingFollowers, and their
            // epoch were accounted for, so the leader should not block and since it started with 
            // accepted epoch = 5 it should now have 6
            try {
            	long epoch = leader.getEpochToPropose(leader.self.getId(), leader.self.getAcceptedEpoch());
            	Assert.assertEquals("leader got wrong epoch from getEpochToPropose", 6, epoch);	
            } catch (Exception e){ 
            	Assert.fail("leader timed out in getEpochToPropose");
            }
        } finally {
            recursiveDelete(tmpDir);
            if (leader != null) {
                leader.shutdown("end of test");
            }
        }
    }
    
    @Test
    public void testLeaderInElectingFollowers() throws Exception {    
        File tmpDir = File.createTempFile("test", "dir");
        tmpDir.delete();
        tmpDir.mkdir();
        Leader leader = null;
        try {
            QuorumPeer peer = createQuorumPeer(tmpDir);
            leader = createLeader(tmpDir, peer);
            peer.leader = leader;            
            
            FollowerMockThread f1 = new FollowerMockThread(1, leader, false);
            FollowerMockThread f2 = new FollowerMockThread(2, leader, false);

            // things needed for waitForEpochAck to run (usually in leader.lead(), but we're not running leader here)
            leader.readyToStart = true;
            leader.leaderStateSummary = new StateSummary(leader.self.getCurrentEpoch(), leader.zk.getLastProcessedZxid());
            
            f1.start();
            f2.start();         
            
            // wait until followers time out in waitForEpochAck - they shouldn't return
            // normally because the leader didn't execute waitForEpochAck
            f1.join(leader.self.getInitLimit()*leader.self.getTickTime() + 5000);
            f2.join(leader.self.getInitLimit()*leader.self.getTickTime() + 5000);
                        
            // make sure that they timed out and didn't return normally  
            Assert.assertTrue(f1.msg + " without waiting for leader", f1.msg == null);            
            Assert.assertTrue(f2.msg + " without waiting for leader", f2.msg == null);
        } finally {
            recursiveDelete(tmpDir);
            if (leader != null) {
                leader.shutdown("end of test");
            }
        }
    }

    private static final class NullServerCnxnFactory extends ServerCnxnFactory {
        public void startup(ZooKeeperServer zkServer) throws IOException,
                InterruptedException {
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
        public int getLocalPort() {
            return 0;
        }
        public InetSocketAddress getLocalAddress() {
            return null;
        }
        public Iterable<ServerCnxn> getConnections() {
            return null;
        }
        public void configure(InetSocketAddress addr, int maxClientCnxns)
                throws IOException {
        }
        public void closeSession(long sessionId) {
        }
        public void closeAll() {
        }
    }
    static class MockDataTreeBuilder implements DataTreeBuilder {
        @Override
        public DataTree build() {
            return new DataTree();
        }
        
    }
    static Socket[] getSocketPair() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.bind(null);
        InetSocketAddress endPoint = (InetSocketAddress) ss.getLocalSocketAddress();
        Socket s = new Socket(endPoint.getAddress(), endPoint.getPort());
        return new Socket[] { s, ss.accept() };
    }
    static void readPacketSkippingPing(InputArchive ia, QuorumPacket qp) throws IOException {
        while(true) {
            ia.readRecord(qp, null);
            if (qp.getType() != Leader.PING) {
                return;
            }
        }
    }
    
    static public interface LeaderConversation {
        void converseWithLeader(InputArchive ia, OutputArchive oa, Leader l) throws Exception;
    }
    
    static public interface FollowerConversation {
        void converseWithFollower(InputArchive ia, OutputArchive oa) throws Exception;
    }
    
    public void testConversation(LeaderConversation conversation) throws Exception {
        Socket pair[] = getSocketPair();
        Socket leaderSocket = pair[0];
        Socket followerSocket = pair[1];
        File tmpDir = File.createTempFile("test", "dir");
        tmpDir.delete();
        tmpDir.mkdir();
        LeadThread leadThread = null;
        Leader leader = null;
        try {
            QuorumPeer peer = createQuorumPeer(tmpDir);
            leader = createLeader(tmpDir, peer);
            peer.leader = leader;
            leadThread = new LeadThread(leader);
            leadThread.start();

            while(!leader.readyToStart) {
                Thread.sleep(20);
            }
            
            LearnerHandler lh = new LearnerHandler(leaderSocket, leader);
            lh.start();
            leaderSocket.setSoTimeout(4000);

            InputArchive ia = BinaryInputArchive.getArchive(followerSocket
                    .getInputStream());
            OutputArchive oa = BinaryOutputArchive.getArchive(followerSocket
                    .getOutputStream());

            conversation.converseWithLeader(ia, oa, leader);
        } finally {
            recursiveDelete(tmpDir);
            if (leader != null) {
                leader.shutdown("end of test");
            }
            if (leadThread != null) {
                leadThread.interrupt();
                leadThread.join();
            }
        }
    }
        
    @Test
    public void testNormalRun() throws Exception {
        testConversation(new LeaderConversation() {
            public void converseWithLeader(InputArchive ia, OutputArchive oa, Leader l)
                    throws IOException {
                /* we test a normal run. everything should work out well. */
                LearnerInfo li = new LearnerInfo(1, 0x10000);
                byte liBytes[] = new byte[12];
                ByteBufferOutputStream.record2ByteBuffer(li,
                        ByteBuffer.wrap(liBytes));
                QuorumPacket qp = new QuorumPacket(Leader.FOLLOWERINFO, 0,
                        liBytes, null);
                oa.writeRecord(qp, null);
                readPacketSkippingPing(ia, qp);
                Assert.assertEquals(Leader.LEADERINFO, qp.getType());
                Assert.assertEquals(ZxidUtils.makeZxid(1, 0), qp.getZxid());
                Assert.assertEquals(ByteBuffer.wrap(qp.getData()).getInt(),
                        0x10000);
                qp = new QuorumPacket(Leader.ACKEPOCH, 0, new byte[4], null);
                oa.writeRecord(qp, null);
                readPacketSkippingPing(ia, qp);
                Assert.assertEquals(Leader.DIFF, qp.getType());
                readPacketSkippingPing(ia, qp);
                Assert.assertEquals(Leader.NEWLEADER, qp.getType());
                Assert.assertEquals(ZxidUtils.makeZxid(1, 0), qp.getZxid());
                qp = new QuorumPacket(Leader.ACK, qp.getZxid(), null, null);
                oa.writeRecord(qp, null);
                readPacketSkippingPing(ia, qp);
                Assert.assertEquals(Leader.UPTODATE, qp.getType());
            }
        });
    }
    
    @Test
    public void testLeaderBehind() throws Exception {
        testConversation(new LeaderConversation() {
            public void converseWithLeader(InputArchive ia, OutputArchive oa, Leader l)
                    throws IOException {
                /* we test a normal run. everything should work out well. */
                LearnerInfo li = new LearnerInfo(1, 0x10000);
                byte liBytes[] = new byte[12];
                ByteBufferOutputStream.record2ByteBuffer(li,
                        ByteBuffer.wrap(liBytes));
                /* we are going to say we last acked epoch 20 */
                QuorumPacket qp = new QuorumPacket(Leader.FOLLOWERINFO, ZxidUtils.makeZxid(20, 0),
                        liBytes, null);
                oa.writeRecord(qp, null);
                readPacketSkippingPing(ia, qp);
                Assert.assertEquals(Leader.LEADERINFO, qp.getType());
                Assert.assertEquals(ZxidUtils.makeZxid(21, 0), qp.getZxid());
                Assert.assertEquals(ByteBuffer.wrap(qp.getData()).getInt(),
                        0x10000);
                qp = new QuorumPacket(Leader.ACKEPOCH, 0, new byte[4], null);
                oa.writeRecord(qp, null);
                readPacketSkippingPing(ia, qp);
                Assert.assertEquals(Leader.DIFF, qp.getType());
                readPacketSkippingPing(ia, qp);
                Assert.assertEquals(Leader.NEWLEADER, qp.getType());
                Assert.assertEquals(ZxidUtils.makeZxid(21, 0), qp.getZxid());
                qp = new QuorumPacket(Leader.ACK, qp.getZxid(), null, null);
                oa.writeRecord(qp, null);
                readPacketSkippingPing(ia, qp);
                Assert.assertEquals(Leader.UPTODATE, qp.getType());
            }
        });
    }

    /**
     * Tests that when a quorum of followers send LearnerInfo but do not ack the epoch (which is sent
     * by the leader upon receipt of LearnerInfo from a quorum), the leader does not start using this epoch
     * as it would in the normal case (when a quorum do ack the epoch). This tests ZK-1192
     * @throws Exception
     */
    @Test
    public void testAbandonBeforeACKEpoch() throws Exception {
        testConversation(new LeaderConversation() {
            public void converseWithLeader(InputArchive ia, OutputArchive oa, Leader l)
                    throws IOException, InterruptedException {
                /* we test a normal run. everything should work out well. */            	
                LearnerInfo li = new LearnerInfo(1, 0x10000);
                byte liBytes[] = new byte[12];
                ByteBufferOutputStream.record2ByteBuffer(li,
                        ByteBuffer.wrap(liBytes));
                QuorumPacket qp = new QuorumPacket(Leader.FOLLOWERINFO, 0,
                        liBytes, null);
                oa.writeRecord(qp, null);
                readPacketSkippingPing(ia, qp);
                Assert.assertEquals(Leader.LEADERINFO, qp.getType());
                Assert.assertEquals(ZxidUtils.makeZxid(1, 0), qp.getZxid());
                Assert.assertEquals(ByteBuffer.wrap(qp.getData()).getInt(),
                        0x10000);                
                Thread.sleep(l.self.getInitLimit()*l.self.getTickTime() + 5000);
                
                // The leader didn't get a quorum of acks - make sure that leader's current epoch is not advanced
                Assert.assertEquals(0, l.self.getCurrentEpoch());			
            }
        });
    }
    
    private void recursiveDelete(File file) {
        if (file.isFile()) {
            file.delete();
        } else {
            for(File c: file.listFiles()) {
                recursiveDelete(c);
            }
            file.delete();
        }
    }

    private Leader createLeader(File tmpDir, QuorumPeer peer)
            throws IOException, NoSuchFieldException, IllegalAccessException {
        FileTxnSnapLog logFactory = new FileTxnSnapLog(tmpDir, tmpDir);
        peer.setTxnFactory(logFactory);
        Field addrField = peer.getClass().getDeclaredField("myQuorumAddr");
        addrField.setAccessible(true);
        addrField.set(peer, new InetSocketAddress(33556));
        ZKDatabase zkDb = new ZKDatabase(logFactory);
        DataTreeBuilder treeBuilder = new MockDataTreeBuilder();
        LeaderZooKeeperServer zk = new LeaderZooKeeperServer(logFactory, peer, treeBuilder, zkDb);
        return new Leader(peer, zk);
    }
    private QuorumPeer createQuorumPeer(File tmpDir) throws IOException,
            FileNotFoundException {
        QuorumPeer peer = new QuorumPeer();
        peer.syncLimit = 2;
        peer.initLimit = 2;
        peer.tickTime = 2000;
        peer.quorumPeers = new HashMap<Long, QuorumServer>();
        peer.quorumPeers.put(1L, new QuorumServer(0, new InetSocketAddress(33221)));
        peer.quorumPeers.put(1L, new QuorumServer(1, new InetSocketAddress(33223)));
        peer.setQuorumVerifier(new QuorumMaj(3));
        peer.setCnxnFactory(new NullServerCnxnFactory());
        File version2 = new File(tmpDir, "version-2");
        version2.mkdir();
        new FileOutputStream(new File(version2, "currentEpoch")).write("0\n".getBytes());
        new FileOutputStream(new File(version2, "acceptedEpoch")).write("0\n".getBytes());
        return peer;
    }
}
