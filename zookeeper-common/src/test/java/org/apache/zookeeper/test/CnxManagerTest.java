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

package org.apache.zookeeper.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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

import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumCnxManager;
import org.apache.zookeeper.server.quorum.QuorumCnxManager.Message;
import org.apache.zookeeper.server.quorum.QuorumCnxManager.InitialMessage;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CnxManagerTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(FLENewEpochTest.class);
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
                    new InetSocketAddress(
                        "127.0.0.1", peerQuorumPort[i]),
                    new InetSocketAddress(
                        "127.0.0.1", PortAssignment.unique()),
                    new InetSocketAddress(
                        "127.0.0.1", peerClientPort[i])));
            peerTmpdir[i] = ClientBase.createTmpDir();
        }
    }

    ByteBuffer createMsg(int state, long leader, long zxid, long epoch){
        byte requestBytes[] = new byte[28];
        ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);

        /*
         * Building notification packet to send
         */

        requestBuffer.clear();
        requestBuffer.putInt(state);
        requestBuffer.putLong(leader);
        requestBuffer.putLong(zxid);
        requestBuffer.putLong(epoch);

        return requestBuffer;
    }

    class CnxManagerThread extends Thread {

        boolean failed;
        CnxManagerThread(){
            failed = false;
        }

        public void run(){
            try {
                QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[0], peerTmpdir[0], peerClientPort[0], 3, 0, 1000, 2, 2);
                QuorumCnxManager cnxManager = peer.createCnxnManager();
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
        QuorumCnxManager cnxManager = peer.createCnxnManager();
        QuorumCnxManager.Listener listener = cnxManager.listener;
        if(listener != null){
            listener.start();
        } else {
            LOG.error("Null listener when initializing cnx manager");
        }

        cnxManager.toSend(0L, createMsg(ServerState.LOOKING.ordinal(), 1, -1, 1));

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
        cnxManager.halt();
        Assert.assertFalse(cnxManager.listener.isAlive());
    }

    @Test
    public void testCnxManagerTimeout() throws Exception {
        Random rand = new Random();
        byte b = (byte) rand.nextInt();
        int deadPort = PortAssignment.unique();
        String deadAddress = "10.1.1." + b;

        LOG.info("This is the dead address I'm trying: " + deadAddress);

        peers.put(Long.valueOf(2),
                new QuorumServer(2,
                        new InetSocketAddress(deadAddress, deadPort),
                        new InetSocketAddress(deadAddress, PortAssignment.unique()),
                        new InetSocketAddress(deadAddress, PortAssignment.unique())));
        peerTmpdir[2] = ClientBase.createTmpDir();

        QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[1], peerTmpdir[1], peerClientPort[1], 3, 1, 1000, 2, 2);
        QuorumCnxManager cnxManager = peer.createCnxnManager();
        QuorumCnxManager.Listener listener = cnxManager.listener;
        if(listener != null){
            listener.start();
        } else {
            LOG.error("Null listener when initializing cnx manager");
        }

        long begin = Time.currentElapsedTime();
        cnxManager.toSend(2L, createMsg(ServerState.LOOKING.ordinal(), 1, -1, 1));
        long end = Time.currentElapsedTime();

        if((end - begin) > 6000) Assert.fail("Waited more than necessary");
        cnxManager.halt();
        Assert.assertFalse(cnxManager.listener.isAlive());
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
        QuorumCnxManager cnxManager = peer.createCnxnManager();
        QuorumCnxManager.Listener listener = cnxManager.listener;
        if(listener != null){
            listener.start();
        } else {
            LOG.error("Null listener when initializing cnx manager");
        }

        int port = peers.get(peer.getId()).electionAddr.getPort();
        LOG.info("Election port: " + port);

        Thread.sleep(1000);

        SocketChannel sc = SocketChannel.open();
        sc.socket().connect(peers.get(1L).electionAddr, 5000);

        InetSocketAddress otherAddr = peers.get(new Long(2)).electionAddr;
        DataOutputStream dout = new DataOutputStream(sc.socket().getOutputStream());
        dout.writeLong(QuorumCnxManager.PROTOCOL_VERSION);
        dout.writeLong(new Long(2));
        String addr = otherAddr.getHostString()+ ":" + otherAddr.getPort();
        byte[] addr_bytes = addr.getBytes();
        dout.writeInt(addr_bytes.length);
        dout.write(addr_bytes);
        dout.flush();
        

        ByteBuffer msgBuffer = ByteBuffer.wrap(new byte[4]);
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
        Assert.assertFalse(cnxManager.listener.isAlive());
    }

    /**
     * Tests a bug in QuorumCnxManager that causes a NPE when a 3.4.6
     * observer connects to a 3.5.0 server. 
     * {@link https://issues.apache.org/jira/browse/ZOOKEEPER-1789}
     * 
     * @throws Exception
     */
    @Test
    public void testCnxManagerNPE() throws Exception {
        // the connecting peer (id = 2) is a 3.4.6 observer
        peers.get(2L).type = LearnerType.OBSERVER;
        QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[1], peerTmpdir[1],
                peerClientPort[1], 3, 1, 1000, 2, 2);
        QuorumCnxManager cnxManager = peer.createCnxnManager();
        QuorumCnxManager.Listener listener = cnxManager.listener;
        if (listener != null) {
            listener.start();
        } else {
            LOG.error("Null listener when initializing cnx manager");
        }
        int port = peers.get(peer.getId()).electionAddr.getPort();
        LOG.info("Election port: " + port);

        Thread.sleep(1000);

        SocketChannel sc = SocketChannel.open();
        sc.socket().connect(peers.get(1L).electionAddr, 5000);

        /*
         * Write id (3.4.6 protocol). This previously caused a NPE in
         * QuorumCnxManager.
         */
        byte[] msgBytes = new byte[8];
        ByteBuffer msgBuffer = ByteBuffer.wrap(msgBytes);
        msgBuffer.putLong(2L);
        msgBuffer.position(0);
        sc.write(msgBuffer);

        msgBuffer = ByteBuffer.wrap(new byte[8]);
        // write length of message
        msgBuffer.putInt(4);
        // write message
        msgBuffer.putInt(5);
        msgBuffer.position(0);
        sc.write(msgBuffer);

        Message m = cnxManager.pollRecvQueue(1000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(m);

        peer.shutdown();
        cnxManager.halt();
        Assert.assertFalse(cnxManager.listener.isAlive());
    }

    /*
     * Test if a receiveConnection is able to timeout on socket errors
     */
    @Test
    public void testSocketTimeout() throws Exception {
        QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[1], peerTmpdir[1], peerClientPort[1], 3, 1, 2000, 2, 2);
        QuorumCnxManager cnxManager = peer.createCnxnManager();
        QuorumCnxManager.Listener listener = cnxManager.listener;
        if(listener != null){
            listener.start();
        } else {
            LOG.error("Null listener when initializing cnx manager");
        }
        int port = peers.get(peer.getId()).electionAddr.getPort();
        LOG.info("Election port: " + port);
        Thread.sleep(1000);

        Socket sock = new Socket();
        sock.connect(peers.get(1L).electionAddr, 5000);
        long begin = Time.currentElapsedTime();
        // Read without sending data. Verify timeout.
        cnxManager.receiveConnection(sock);
        long end = Time.currentElapsedTime();
        if((end - begin) > ((peer.getSyncLimit() * peer.getTickTime()) + 500)) Assert.fail("Waited more than necessary");
        cnxManager.halt();
        Assert.assertFalse(cnxManager.listener.isAlive());
    }

    /*
     * Test if Worker threads are getting killed after connection loss
     */
    @Test
    public void testWorkerThreads() throws Exception {
        ArrayList<QuorumPeer> peerList = new ArrayList<QuorumPeer>();
        try {
            for (int sid = 0; sid < 3; sid++) {
                QuorumPeer peer = new QuorumPeer(peers, peerTmpdir[sid],
                        peerTmpdir[sid], peerClientPort[sid], 3, sid, 1000, 2,
                        2);
                LOG.info("Starting peer {}", peer.getId());
                peer.start();
                peerList.add(sid, peer);
            }
            String failure = verifyThreadCount(peerList, 4);
            Assert.assertNull(failure, failure);
            for (int myid = 0; myid < 3; myid++) {
                for (int i = 0; i < 5; i++) {
                    // halt one of the listeners and verify count
                    QuorumPeer peer = peerList.get(myid);
                    LOG.info("Round {}, halting peer ",
                            new Object[] { i, peer.getId() });
                    peer.shutdown();
                    peerList.remove(myid);
                    failure = verifyThreadCount(peerList, 2);
                    Assert.assertNull(failure, failure);
                    // Restart halted node and verify count
                    peer = new QuorumPeer(peers, peerTmpdir[myid],
                            peerTmpdir[myid], peerClientPort[myid], 3, myid,
                            1000, 2, 2);
                    LOG.info("Round {}, restarting peer ",
                            new Object[] { i, peer.getId() });
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
                return new Date()
                    + " Incorrect number of Worker threads for sid=" + myid
                    + " expected " + ecnt + " found " + cnt;
            }
        }
        return null;
    }

    @Test
    public void testInitialMessage() throws Exception {
        InitialMessage msg;
        ByteArrayOutputStream bos;
        DataInputStream din;
        DataOutputStream dout;
        String hostport;

        // message with bad protocol version
        try {

            // the initial message (without the protocol version)
            hostport = "10.0.0.2:3888";
            bos = new ByteArrayOutputStream();
            dout = new DataOutputStream(bos);
            dout.writeLong(5L); // sid
            dout.writeInt(hostport.getBytes().length);
            dout.writeBytes(hostport);

            // now parse it
            din = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
            msg = InitialMessage.parse(-65530L, din);
            Assert.fail("bad protocol version accepted");
        } catch (InitialMessage.InitialMessageException ex) {}

        // message too long
        try {

            hostport = createLongString(1048576);
            bos = new ByteArrayOutputStream();
            dout = new DataOutputStream(bos);
            dout.writeLong(5L); // sid
            dout.writeInt(hostport.getBytes().length);
            dout.writeBytes(hostport);

            din = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
            msg = InitialMessage.parse(QuorumCnxManager.PROTOCOL_VERSION, din);
            Assert.fail("long message accepted");
        } catch (InitialMessage.InitialMessageException ex) {}

        // bad hostport string
        try {

            hostport = "what's going on here?";
            bos = new ByteArrayOutputStream();
            dout = new DataOutputStream(bos);
            dout.writeLong(5L); // sid
            dout.writeInt(hostport.getBytes().length);
            dout.writeBytes(hostport);

            din = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
            msg = InitialMessage.parse(QuorumCnxManager.PROTOCOL_VERSION, din);
            Assert.fail("bad hostport accepted");
        } catch (InitialMessage.InitialMessageException ex) {}

        // good message
        try {

            hostport = "10.0.0.2:3888";
            bos = new ByteArrayOutputStream();
            dout = new DataOutputStream(bos);
            dout.writeLong(5L); // sid
            dout.writeInt(hostport.getBytes().length);
            dout.writeBytes(hostport);

            // now parse it
            din = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
            msg = InitialMessage.parse(QuorumCnxManager.PROTOCOL_VERSION, din);
        } catch (InitialMessage.InitialMessageException ex) {
            Assert.fail(ex.toString());
        }
    }

    private String createLongString(int size) {
        StringBuilder sb = new StringBuilder(size);
        for (int i=0; i < size; i++) {
            sb.append('x');
        }
        return sb.toString();
    }
}
