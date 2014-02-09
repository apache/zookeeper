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

import java.io.DataOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.net.Socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumCnxManager;
import org.apache.zookeeper.server.quorum.QuorumCnxManager.Message;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CnxManagerTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(CnxManagerTest.class);
    protected static final int THRESHOLD = 4;

    int count;
    HashMap<Long,QuorumServer> peers;
    File peerTmpdir[];
    int peerQuorumPort[];
    int peerClientPort[];
    @Before
    public void setUp() throws Exception {

        this.count = 3;
        this.peers = new HashMap<Long,QuorumServer>(count); 
        peerTmpdir = new File[count];
        peerQuorumPort = new int[count];
        peerClientPort = new int[count];
        
        for(int i = 0; i < count; i++) {
            peerQuorumPort[i] = PortAssignment.unique();
            peerClientPort[i] = PortAssignment.unique();
            peers.put(Long.valueOf(i),
                    new QuorumServer(i,
                            new InetSocketAddress(peerQuorumPort[i]),
                    new InetSocketAddress(PortAssignment.unique())));
            peerTmpdir[i] = ClientBase.createTmpDir();
        }
    }

    ByteBuffer createMsg(int state, long leader, long zxid, long epoch){
        return FastLeaderElection.buildMsg(state, leader, zxid, 0, epoch);
    }

    class CnxManagerThread extends Thread {

        boolean failed;
        CnxManagerThread(){
            failed = false;
        }

        public void run(){
            try {
                QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[0], peerTmpdir[0], peerClientPort[0], 3, 0, 1000, 2, 2);
                QuorumCnxManager cnxManager = new QuorumCnxManager(peer);
                QuorumCnxManager.Listener listener = cnxManager.listener;
                if(listener != null){
                    listener.start();
                } else {
                    LOG.error("Null listener when initializing cnx manager");
                }

                long sid = 1;
                cnxManager.toSend(sid, createMsg(ServerState.LOOKING.ordinal(), 0, -1, 1));

                Message m = null;
                int numRetries = 1;
                while((m == null) && (numRetries++ <= THRESHOLD)){
                    m = cnxManager.pollRecvQueue(3000, TimeUnit.MILLISECONDS);
                    if(m == null) cnxManager.connectAll();
                }

                if(numRetries > THRESHOLD){
                    failed = true;
                    return;
                }

                cnxManager.testInitiateConnection(sid);

                m = cnxManager.pollRecvQueue(3000, TimeUnit.MILLISECONDS);
                if(m == null){
                    failed = true;
                    return;
                }
            } catch (Exception e) {
                LOG.error("Exception while running mock thread", e);
                Assert.fail("Unexpected exception");
            }
        }
    }

    @Test
    public void testCnxManager() throws Exception {
        CnxManagerThread thread = new CnxManagerThread();

        thread.start();
        
        QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[1], peerTmpdir[1], peerClientPort[1], 3, 1, 1000, 2, 2);
        QuorumCnxManager cnxManager = new QuorumCnxManager(peer);
        QuorumCnxManager.Listener listener = cnxManager.listener;
        if(listener != null){
            listener.start();
        } else {
            LOG.error("Null listener when initializing cnx manager");
        }

        cnxManager.toSend(new Long(0), createMsg(ServerState.LOOKING.ordinal(), 1, -1, 1));

        Message m = null;
        int numRetries = 1;
        while((m == null) && (numRetries++ <= THRESHOLD)){
            m = cnxManager.pollRecvQueue(3000, TimeUnit.MILLISECONDS);
            if(m == null) cnxManager.connectAll();
        }
        
        Assert.assertTrue("Exceeded number of retries", numRetries <= THRESHOLD);

        thread.join(5000);
        if (thread.isAlive()) {
            Assert.fail("Thread didn't join");
        } else {
            if(thread.failed)
                Assert.fail("Did not receive expected message");
        }
        
    }

    @Test
    public void testCnxManagerTimeout() throws Exception {
        Random rand = new Random();
        byte b = (byte) rand.nextInt();
        int deadPort = PortAssignment.unique();
        String deadAddress = new String("10.1.1." + b);
            
        LOG.info("This is the dead address I'm trying: " + deadAddress);
            
        peers.put(Long.valueOf(2),
                new QuorumServer(2,
                        new InetSocketAddress(deadAddress, deadPort),
                        new InetSocketAddress(deadAddress, PortAssignment.unique())));
        peerTmpdir[2] = ClientBase.createTmpDir();
    
        QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[1], peerTmpdir[1], peerClientPort[1], 3, 1, 1000, 2, 2);
        QuorumCnxManager cnxManager = new QuorumCnxManager(peer);
        QuorumCnxManager.Listener listener = cnxManager.listener;
        if(listener != null){
            listener.start();
        } else {
            LOG.error("Null listener when initializing cnx manager");
        }

        long begin = System.currentTimeMillis();
        cnxManager.toSend(new Long(2), createMsg(ServerState.LOOKING.ordinal(), 1, -1, 1));
        long end = System.currentTimeMillis();
            
        if((end - begin) > 6000) Assert.fail("Waited more than necessary");
        
    }       
    
    /**
     * Tests a bug in QuorumCnxManager that causes a spin lock
     * when a negative value is sent. This test checks if the 
     * connection is being closed upon a message with negative
     * length.
     * 
     * @throws Exception
     */
    @Test
    public void testCnxManagerSpinLock() throws Exception {               
        QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[1], peerTmpdir[1], peerClientPort[1], 3, 1, 1000, 2, 2);
        QuorumCnxManager cnxManager = new QuorumCnxManager(peer);
        QuorumCnxManager.Listener listener = cnxManager.listener;
        if(listener != null){
            listener.start();
        } else {
            LOG.error("Null listener when initializing cnx manager");
        }
        
        int port = peers.get(peer.getId()).electionAddr.getPort();
        LOG.info("Election port: " + port);
        InetSocketAddress addr = new InetSocketAddress(port);
        
        Thread.sleep(1000);
        
        SocketChannel sc = SocketChannel.open();
        sc.socket().connect(peers.get(new Long(1)).electionAddr, 5000);
        
        /*
         * Write id first then negative length.
         */
        byte[] msgBytes = new byte[8];
        ByteBuffer msgBuffer = ByteBuffer.wrap(msgBytes);
        msgBuffer.putLong(new Long(2));
        msgBuffer.position(0);
        sc.write(msgBuffer);
        
        msgBuffer = ByteBuffer.wrap(new byte[4]);
        msgBuffer.putInt(-20);
        msgBuffer.position(0);
        sc.write(msgBuffer);
        
        Thread.sleep(1000);
        
        try{
            /*
             * Write a number of times until it
             * detects that the socket is broken.
             */
            for(int i = 0; i < 100; i++){
                msgBuffer.position(0);
                sc.write(msgBuffer);
            }
            Assert.fail("Socket has not been closed");
        } catch (Exception e) {
            LOG.info("Socket has been closed as expected");
        }
        peer.shutdown();
        cnxManager.halt();
    }   

    /*
     * Class used with testCnxFromFutureVersion
     */
    class TestCnxManager extends QuorumCnxManager {

        TestCnxManager(QuorumPeer self) {
            super(self);
        }
        
        boolean senderWorkerMapContains(Long l){
            return senderWorkerMap.containsKey(l);
        }
        
        long getSid(Message m){
            return m.sid;
        }
        
        String getMsgString(Message m){
            return new String(m.buffer.array());
        }
    }
    
    /**
     * Before 3.5.0 a server sends its id when connecting to another server.
     * Starting with 3.5.0 a server will send a protocol version, followed by
     * its id, then number of bytes in the remainder of the message and finally
     * the rest of the message. The test makes sure that a 3.4.6 server is able
     * to detect that a connection message has this new format, extract the id,
     * and skip the remainder of the message. 
     * 
     * {@link https://issues.apache.org/jira/browse/ZOOKEEPER-1633}
     * 
     * @throws Exception
     */
    @Test
    public void testCnxFromFutureVersion() throws Exception {               
        QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[1], peerTmpdir[1], peerClientPort[1], 3, 1, 1000, 2, 2);
        TestCnxManager cnxManager = new TestCnxManager(peer);
        QuorumCnxManager.Listener listener = cnxManager.listener;
        if(listener != null){
            listener.start();
        } else {
            Assert.fail("Null listener when initializing cnx manager");
        }
        
        int port = peers.get(peer.getId()).electionAddr.getPort();
        LOG.info("Election port: " + port);
        
        Thread.sleep(1000);
        
        SocketChannel sc = SocketChannel.open();
        sc.socket().connect(peers.get(new Long(1)).electionAddr, 5000);
        
        InetSocketAddress otherAddr = peers.get(new Long(2)).electionAddr;
        DataOutputStream dout = new DataOutputStream(sc.socket().getOutputStream());
        // protocol version - a negative number
        dout.writeLong(0xffff0000);
        // server id
        dout.writeLong(new Long(2));
        // other stuff that a 3.5.0 server will send - not important for 3.4.6
        // the 3.4.6 server should just skip it
        String addr = otherAddr.getHostName()+ ":" + otherAddr.getPort();
        byte[] addr_bytes = addr.getBytes();
        dout.writeInt(addr_bytes.length);
        dout.write(addr_bytes);
        dout.flush();
        
        Thread.sleep(1000);
        
        Assert.assertEquals("Server 1 got connection request from server 2", 
                true, cnxManager.senderWorkerMapContains(new Long(2)));
      
        // send another message to make sure the connection message was processed
        // properly (mainly that its suffix was removed from the stream)
        String testStr = "this is a test message string";
        byte[] testStr_bytes = testStr.getBytes();
        dout.writeInt(testStr_bytes.length);
        dout.write(testStr_bytes);
        dout.flush();
        
        Message m = null;
        int numRetries = 1;
        while((m == null) && (numRetries++ <= THRESHOLD)){
            m = cnxManager.pollRecvQueue(3000, TimeUnit.MILLISECONDS);
            if(m == null) cnxManager.connectAll();
        }

        if(numRetries > THRESHOLD){
            Assert.fail("Test message hasn't been found in recvQueue");
        }

        //Assert.assertEquals("Message sender should be 2", 2, m.sid);
        Assert.assertEquals("Message sender should be 2", 2, cnxManager.getSid(m));
        Assert.assertEquals("Message from 2 doesn't match test sring", testStr, 
                cnxManager.getMsgString(m));
      
        peer.shutdown();
        cnxManager.halt();
    }   

    
    /*
     * Test if a receiveConnection is able to timeout on socket errors
     */
    @Test
    public void testSocketTimeout() throws Exception {
        QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[1], peerTmpdir[1], peerClientPort[1], 3, 1, 2000, 2, 2);
        QuorumCnxManager cnxManager = new QuorumCnxManager(peer);
        QuorumCnxManager.Listener listener = cnxManager.listener;
        if(listener != null){
            listener.start();
        } else {
            LOG.error("Null listener when initializing cnx manager");
        }
        int port = peers.get(peer.getId()).electionAddr.getPort();
        LOG.info("Election port: " + port);
        InetSocketAddress addr = new InetSocketAddress(port);
        Thread.sleep(1000);
        
        Socket sock = new Socket();
        sock.connect(peers.get(new Long(1)).electionAddr, 5000);
        long begin = System.currentTimeMillis();
        // Read without sending data. Verify timeout.
        cnxManager.receiveConnection(sock);
        long end = System.currentTimeMillis();
        if((end - begin) > ((peer.getSyncLimit() * peer.getTickTime()) + 500)) Assert.fail("Waited more than necessary");
    }

    /*
     * Test if Worker threads are getting killed after connection loss
     */
    @Test
    public void testWorkerThreads() throws Exception {
        ArrayList<QuorumPeer> peerList = new ArrayList<QuorumPeer>();
        try {
            for (int sid = 0; sid < 3; sid++) {
                QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[sid], peerTmpdir[sid],
                                                 peerClientPort[sid], 3, sid, 1000, 2, 2);
                LOG.info("Starting peer {}", peer.getId());
                peer.start();
                peerList.add(sid, peer);
            }
            String failure = verifyThreadCount(peerList, 4);
            if (failure != null) {
                Assert.fail(failure);
            }
            for (int myid = 0; myid < 3; myid++) {
                for (int i = 0; i < 5; i++) {
                    // halt one of the listeners and verify count
                    QuorumPeer peer = peerList.get(myid);
                    LOG.info("Round {}, halting peer {}", new Object[] { i,
                            peer.getId() });
                    peer.shutdown();
                    peerList.remove(myid);
                    failure = verifyThreadCount(peerList, 2);
                    Assert.assertNull(failure, failure);
                    // Restart halted node and verify count
                    peer = new QuorumPeer(peers, peerTmpdir[myid], peerTmpdir[myid],
                                            peerClientPort[myid], 3, myid, 1000, 2, 2);
                    LOG.info("Round {}, restarting peer {}"
                            + new Object[] { i, peer.getId() });
                    peer.start();
                    peerList.add(myid, peer);
                    failure = verifyThreadCount(peerList, 4);
                    Assert.assertNull(failure, failure);
                }
            }
        } finally {
            for (QuorumPeer quorumPeer : peerList) {
                quorumPeer.shutdown();
            }
        }
    }

    /**
     * Returns null on success, otw the message assoc with the failure 
     * @throws InterruptedException
     */
    public String verifyThreadCount(ArrayList<QuorumPeer> peerList, long ecnt)
        throws InterruptedException
    {
        String failure = null;
        for (int i = 0; i < 480; i++) {
            Thread.sleep(500);

            failure = _verifyThreadCount(peerList, ecnt);
            if (failure == null) {
                return null;
            }
        }
        return failure;
    }
    public String _verifyThreadCount(ArrayList<QuorumPeer> peerList, long ecnt) {
        for (int myid = 0; myid < peerList.size(); myid++) {
            QuorumPeer peer = peerList.get(myid);
            QuorumCnxManager cnxManager = peer.getQuorumCnxManager();
            long cnt = cnxManager.getThreadCount();
            if (cnt != ecnt) {
                return new String(new Date()
                    + " Incorrect number of Worker threads for sid=" + myid
                    + " expected " + ecnt + " found " + cnt);
            }
        }
        return null;
    }
}
