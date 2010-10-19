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

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.quorum.QuorumCnxManager;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumCnxManager.Message;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.junit.Test;


/**
 * This test uses two mock servers, each running an instance of QuorumCnxManager.
 * It simulates the situation in which a peer P sends a message to another peer Q 
 * while Q is trying to open a connection to P. In this test, Q iniates a connection
 * to P as soon as it receives a message from P, and verifies that it receives a 
 * copy of the message.
 * 
 * This simple tests verifies that the new mechanism that duplicates the last message
 * sent upon a re-connection works. 
 *
 */
public class CnxManagerTest extends TestCase {
    protected static final Logger LOG = Logger.getLogger(FLENewEpochTest.class);
    protected static final int THRESHOLD = 4;
    
    int count;
    HashMap<Long,QuorumServer> peers;
    File tmpdir[];
    int port[];
    
    public void setUp() throws Exception {
        
        this.count = 3;
        this.peers = new HashMap<Long,QuorumServer>(count); 
        tmpdir = new File[count];
        port = new int[count];
        
        for(int i = 0; i < count; i++) {
            int clientport = PortAssignment.unique();
            peers.put(Long.valueOf(i),
                    new QuorumServer(i,
                            new InetSocketAddress(clientport),
                    new InetSocketAddress(PortAssignment.unique())));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = clientport;
        }
    }
    
    public void tearDown() {
        
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
                QuorumPeer peer = new QuorumPeer(peers, tmpdir[0], tmpdir[0], port[0], 3, 0, 2, 2, 2);
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
                    m = cnxManager.recvQueue.poll(3000, TimeUnit.MILLISECONDS);
                    if(m == null) cnxManager.connectAll();
                }
                
                if(numRetries > THRESHOLD){
                    failed = true;
                    return;
                }
                
                cnxManager.testInitiateConnection(sid);
            
                m = cnxManager.recvQueue.poll(3000, TimeUnit.MILLISECONDS);
                if(m == null){
                    failed = true;
                    return;
                }
            } catch (Exception e) {
                LOG.error("Exception while running mock thread", e);
                fail("Unexpected exception");
            }
        }
    }
    
    @Test
    public void testCnxManager() throws Exception {
        CnxManagerThread thread = new CnxManagerThread();
        
        thread.start();
        
        QuorumPeer peer = new QuorumPeer(peers, tmpdir[1], tmpdir[1], port[1], 3, 1, 2, 2, 2);
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
            m = cnxManager.recvQueue.poll(3000, TimeUnit.MILLISECONDS);
            if(m == null) cnxManager.connectAll();
        }
        
        assertTrue("Exceeded number of retries", numRetries <= THRESHOLD);

        thread.join(5000);
        if (thread.isAlive()) {
            fail("Thread didn't join");
        } else {
            if(thread.failed)
                fail("Did not receive expected message");
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
        tmpdir[2] = ClientBase.createTmpDir();
        port[2] = deadPort;
    
        QuorumPeer peer = new QuorumPeer(peers, tmpdir[1], tmpdir[1], port[1], 3, 1, 2, 2, 2);
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
    
        if((end - begin) > 6000) fail("Waited more than necessary");
    
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
        QuorumPeer peer = new QuorumPeer(peers, tmpdir[1], tmpdir[1], port[1], 3, 1, 2, 2, 2);
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
            fail("Socket has not been closed");
        } catch (Exception e) {
            LOG.info("Socket has been closed as expected");
        }
    }   
}