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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;

import org.apache.log4j.Logger;

/**
 * This class implements a connection manager for leader election using TCP. It
 * maintains one coonection for every pair of servers. The tricky part is to
 * guarantee that there is exactly one connection for every pair of servers that
 * are operating correctly and that can communicate over the network.
 * 
 * If two servers try to start a connection concurrently, then the connection
 * manager uses a very simple tie-breaking mechanism to decide which connection
 * to drop based on the IP addressed of the two parties. 
 * 
 * For every peer, the manager maintains a queue of messages to send. If the
 * connection to any particular peer drops, then the sender thread puts the
 * message back on the list. As this implementation currently uses a queue
 * implementation to maintain messages to send to another peer, we add the
 * message to the tail of the queue, thus changing the order of messages.
 * Although this is not a problem for the leader election, it could be a problem
 * when consolidating peer communication. This is to be verified, though.
 * 
 */

class QuorumCnxManager extends Thread {
    private static final Logger LOG = Logger.getLogger(QuorumCnxManager.class);

    /*
     * Maximum capacity of thread queues
     */

    static final int CAPACITY = 100;

    /*
     * Maximum number of attempts to connect to a peer
     */

    static final int MAX_CONNECTION_ATTEMPTS = 2;

    /*
     * Packet size
     */
    int packetSize;

    /*
     * Port to listen on
     */
    int port;

    /*
     * Challenge to initiate connections
     */
    long challenge;

    /*
     * Local IP address
     */
    InetAddress localIP;

    /*
     * Mapping from Peer to Thread number
     */
    HashMap<InetAddress, SendWorker> senderWorkerMap;
    HashMap<InetAddress, ArrayBlockingQueue<ByteBuffer>> queueSendMap;

    /*
     * Reception queue
     */
    ArrayBlockingQueue<Message> recvQueue;

    /*
     * Shutdown flag
     */

    boolean shutdown = false;

    /*
     * Listener thread
     */
    Listener listener;

    class Message {
        Message(ByteBuffer buffer, InetAddress addr) {
            this.buffer = buffer;
            this.addr = addr;
        }

        ByteBuffer buffer;
        InetAddress addr;
    }

    QuorumCnxManager(int port) {
        this.port = port;
        this.recvQueue = new ArrayBlockingQueue<Message>(CAPACITY);
        this.queueSendMap = new HashMap<InetAddress, ArrayBlockingQueue<ByteBuffer>>();
        this.senderWorkerMap = new HashMap<InetAddress, SendWorker>();

        try {
            localIP = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            LOG.warn("Couldn't get local address");
        }

        // Generates a challenge to guarantee one connection between pairs of
        // servers
        genChallenge();

        // Starts listener thread that waits for connection requests 
        listener = new Listener();
        listener.start();
    }

    void genChallenge() {
        Random rand = new Random(System.currentTimeMillis()
                + localIP.hashCode());
        long newValue = rand.nextLong();
        challenge = newValue;
    }

    /**
     * If this server has initiated the connection, then it gives up on the
     * connection if it loses challenge. Otherwise, it keeps the connection.
     */

    boolean initiateConnection(SocketChannel s) {
        boolean challenged = true;
        boolean wins = false;
        long newChallenge;

        // Compare IP addresses based on their hash codes 
        //int hashCodeRemote = s.socket().getInetAddress().hashCode();
        //if(hashCodeRemote >= localIP.hashCode()){
        //    wins = false;
        //} else {
        //    wins = true;
        //} 
        //LOG.warn("Hash codes: " + hashCodeRemote + ", " + localIP.hashCode());
        
        try {
            while (challenged && s.isConnected()) {
                // Sending challenge
                byte[] msgBytes = new byte[8];
                ByteBuffer msgBuffer = ByteBuffer.wrap(msgBytes);
                msgBuffer.putLong(challenge);
                msgBuffer.position(0);
                s.write(msgBuffer);
        
                // Reading challenge
                msgBuffer.position(0);
                s.read(msgBuffer);
        
                msgBuffer.position(0);
                newChallenge = msgBuffer.getLong();
                if (challenge > newChallenge) {
                   wins = true;
                    challenged = false;
                } else if (challenge == newChallenge) {
                    genChallenge();
                } else {
                    challenged = false;
                }
            }
        } catch (IOException e) {
            LOG.warn("Exception reading or writing challenge: "
                    + e.toString());
            return false;
        }

        // If lost the challenge, then drop the new connection
        if (!wins) {
            try {
                //LOG.warn("lost cause (initiate");
                s.socket().close();
            } catch (IOException e) {
                LOG.warn("Error when closing socket or trying to reopen connection: "
                                + e.toString());

            }
        // Otherwise proceed with the connection
        } else
            synchronized (senderWorkerMap) {
                /*
                 * It may happen that a thread from a previous connection to the same
                 * server is still active. In this case, we terminate the thread by
                 * calling finish(). Note that senderWorkerMap is a map from IP 
                 * addresses to worker thread.
                 */
                if (senderWorkerMap.get(s.socket().getInetAddress()) != null) {
                    senderWorkerMap.get(s.socket().getInetAddress()).finish();
                }

                /*
                 * Start new worker thread with a clean state.
                 */
                if (s != null) {
                    SendWorker sw = new SendWorker(s);
                    RecvWorker rw = new RecvWorker(s);
                    sw.setRecv(rw);

                    if (senderWorkerMap
                            .containsKey(s.socket().getInetAddress())) {
                        InetAddress addr = s.socket().getInetAddress();
                        senderWorkerMap.get(addr).finish();
                    }

                    senderWorkerMap.put(s.socket().getInetAddress(), sw);
                    sw.start();
                    rw.start();

                    return true;
                } else {
                    LOG.warn("Channel null");
                    return false;
                }
            }

        return false;
    }

    /**
     * If this server receives a connection request, then it gives up on the new
     * connection if it wins. Notice that it checks whether it has a connection
     * to this server already or not. If it does, then it sends the smallest
     * possible long value to lose the challenge.
     * 
     */
    boolean receiveConnection(SocketChannel s) {
        boolean challenged = true;
        boolean wins = false;
        long newChallenge;
       
        
        //Compare IP addresses based on their hash codes.
        //int hashCodeRemote = s.socket().getInetAddress().hashCode();
        //if(hashCodeRemote >= localIP.hashCode()){
        //    wins = false;
        //} else {
        //    wins = true;
        //} 
        
        //LOG.warn("Hash codes: " + hashCodeRemote + ", " + localIP.hashCode());
        
        
        try {
            while (challenged && s.isConnected()) {
               // Sending challenge
                byte[] msgBytes = new byte[8];
                ByteBuffer msgBuffer = ByteBuffer.wrap(msgBytes);
                long vsent;
                if (senderWorkerMap.get(s.socket().getInetAddress()) == null)
                    vsent = Long.MIN_VALUE;
                else
                    vsent = challenge;
                msgBuffer.putLong(vsent);
                msgBuffer.position(0);
                s.write(msgBuffer);
        
                // Reading challenge
                msgBuffer.position(0);
                s.read(msgBuffer);
        
                msgBuffer.position(0);
                newChallenge = msgBuffer.getLong();
                if (vsent > newChallenge) {
                    wins = true;
                    challenged = false;
                } else if (challenge == newChallenge) {
                    genChallenge();
                } else {
                    challenged = false;
                }
            }
        } catch (IOException e) {
            LOG.warn("Exception reading or writing challenge: "
                    + e.toString());
            return false;
        }

        //If wins the challenge, then close the new connection.
        if (wins) {
            try {
                InetAddress addr = s.socket().getInetAddress();
                SendWorker sw = senderWorkerMap.get(addr);

                //LOG.warn("Keep connection (received)");
                //sw.connect();
                s.socket().close();
                sw.finish();
                SocketChannel channel = SocketChannel.open(new InetSocketAddress(addr, port));
                if (channel.isConnected()) {
                    initiateConnection(channel);
                }
                
                
            } catch (IOException e) {
                LOG.warn("Error when closing socket or trying to reopen connection: "
                                + e.toString());
            }
        //Otherwise start worker threads to receive data.
        } else
            synchronized (senderWorkerMap) {
                if (senderWorkerMap.get(s.socket().getInetAddress()) != null) {
                    senderWorkerMap.get(s.socket().getInetAddress()).finish();       
                }
                
                if (s != null) {
                    SendWorker sw = new SendWorker(s);
                    RecvWorker rw = new RecvWorker(s);
                    sw.setRecv(rw);

                    if (senderWorkerMap
                            .containsKey(s.socket().getInetAddress())) {
                        InetAddress addr = s.socket().getInetAddress();
                        senderWorkerMap.get(addr).finish();
                    }

                    senderWorkerMap.put(s.socket().getInetAddress(), sw);
                    sw.start();
                    rw.start();

                    return true;
                } else {
                    LOG.warn("Channel null");
                    return false;
                }
            }

        return false;
    }

    /**
     * Processes invoke this message to send a message. Currently, only leader
     * election uses it.
     */
    void toSend(InetAddress addr, ByteBuffer b) {
        /*
         * If sending message to myself, then simply enqueue it (loopback).
         */
        if (addr.equals(localIP)) {
            try {
                b.position(0);
                recvQueue.put(new Message(b.duplicate(), addr));
            } catch (InterruptedException e) {
                LOG.warn("Exception when loopbacking");
            }
        /*
         * Otherwise send to the corresponding thread to send. 
         */
        } else
            try {
                /*
                 * Start a new connection if doesn't have one already.
                 */
                if (!queueSendMap.containsKey(addr)) {
                    queueSendMap.put(addr, new ArrayBlockingQueue<ByteBuffer>(
                            CAPACITY));
                    queueSendMap.get(addr).put(b);

                } else {
                    if (queueSendMap.get(addr).remainingCapacity() == 0) {
                        queueSendMap.get(addr).take();
                    }
                    queueSendMap.get(addr).put(b);
                }
                
                synchronized (senderWorkerMap) {
                    if (senderWorkerMap.get(addr) == null) {
                        SocketChannel channel;
                        try {
                            channel = SocketChannel
                                    .open(new InetSocketAddress(addr, port));
                            channel.socket().setTcpNoDelay(true);
                            initiateConnection(channel);
                        } catch (IOException e) {
                            LOG.warn("Cannot open channel to "
                                    + addr.toString() + "( " + e.toString()
                                    + ")");
                        }
                    }
                }     
            } catch (InterruptedException e) {
                LOG.warn("Interrupted while waiting to put message in queue."
                                + e.toString());
            }
    }

    /**
     * Check if all queues are empty, indicating that all messages have been delivered.
     */
    boolean haveDelivered() {
        for (ArrayBlockingQueue<ByteBuffer> queue : queueSendMap.values()) {
            if (queue.size() == 0)
                return true;
        }

        return false;
    }

    /**
     * Flag that it is time to wrap up all activities and interrupt the listener.
     */
    public void shutdown() {
        shutdown = true;
        listener.interrupt();
    }

    /**
     * Thread to listen on some port
     */
    class Listener extends Thread {

        /**
         * Sleeps on accept().
         */
        public void run() {
            ServerSocketChannel ss = null;
            try {
                ss = ServerSocketChannel.open();
                ss.socket().bind(new InetSocketAddress(port));

                while (!shutdown) {
                    SocketChannel client = ss.accept();
                    client.socket().setTcpNoDelay(true);
                    /*
                     * This synchronized block guarantees that if
                     * both parties try to connect to each other
                     * simultaneously, then only one will succeed.
                     * If we don't have this block, then there 
                     * are runs in which both parties act as if they
                     * don't have any connection starting or started.
                     * In receiveConnection(), a server sends the minimum
                     * value for a challenge, if they believe they must
                     * accept the connection because they don't have one.
                     * 
                     * This synchronized block prevents that the same server
                     * invokes receiveConnection() and initiateConnection() 
                     * simultaneously.
                     */
                    synchronized(senderWorkerMap){
                        LOG.warn("Connection request");
                        receiveConnection(client);
                    }
                }
            } catch (IOException e) {
                System.err.println("Listener.run: " + e.getMessage());
            }
        }
    }

    /**
     * Thread to send messages. Instance waits on a queue, and send a message as
     * soon as there is one available. If connection breaks, then opens a new
     * one.
     */

    class SendWorker extends Thread {
        // Send msgs to peer
        InetAddress addr;
        SocketChannel channel;
        RecvWorker recvWorker;
        boolean running = true;

        SendWorker(SocketChannel channel) {
            this.addr = channel.socket().getInetAddress();
            this.channel = channel;
            recvWorker = null;
            
            LOG.debug("Address of remote peer: " + this.addr);
        }

        void setRecv(RecvWorker recvWorker) {
            this.recvWorker = recvWorker;
        }

        boolean finish() {
            running = false;

            this.interrupt();
            if (recvWorker != null)
                recvWorker.finish();
            senderWorkerMap.remove(channel.socket().getInetAddress());
            return running;
        }

        public void run() {

            while (running && !shutdown) {

                ByteBuffer b = null;
                try {
                    b = queueSendMap.get(addr).take();
                } catch (InterruptedException e) {
                    LOG.warn("Interrupted while waiting for message on queue ("
                                    + e.toString() + ")");
                    continue;
                }

                try {
                    byte[] msgBytes = new byte[b.capacity()
                            + (Integer.SIZE / 8)];
                    ByteBuffer msgBuffer = ByteBuffer.wrap(msgBytes);
                    msgBuffer.putInt(b.capacity());

                    msgBuffer.put(b.array(), 0, b.capacity());
                    msgBuffer.position(0);
                    channel.write(msgBuffer);

                } catch (IOException e) {
                    /*
                     * If reconnection doesn't work, then put the
                     * message back to the beginning of the queue and leave.
                     */
                    LOG.warn("Exception when using channel: " + addr
                            + ")" + e.toString());
                    running = false;
                    synchronized (senderWorkerMap) {
                        recvWorker.finish();
                        recvWorker = null;
                    
                        senderWorkerMap.remove(channel.socket().getInetAddress());
                    
                        if (queueSendMap.get(channel.socket().getInetAddress())
                                    .size() == 0)
                            queueSendMap.get(channel.socket().getInetAddress())
                                    .offer(b);
                    }
                }
            }
            LOG.warn("Leaving thread");
        }
    }

    /**
     * Thread to receive messages. Instance waits on a socket read. If the
     * channel breaks, then removes itself from the pool of receivers.
     */

    class RecvWorker extends Thread {
        InetAddress addr;
        SocketChannel channel;
        boolean running = true;

        RecvWorker(SocketChannel channel) {
            this.addr = channel.socket().getInetAddress();
            this.channel = channel;
        }

        boolean finish() {
            running = false;
            this.interrupt();
            return running;
        }

        public void run() {
            try {
                byte[] size = new byte[4];
                ByteBuffer msgLength = ByteBuffer.wrap(size);
                while (running && !shutdown && channel.isConnected()) {
                    /**
                     * Reads the first int to determine the length of the
                     * message
                     */
                    while (msgLength.hasRemaining()) {
                        channel.read(msgLength);
                    }
                    msgLength.position(0);
                    int length = msgLength.getInt();

                    /**
                     * Allocates a new ByteBuffer to receive the message
                     */
                    if (length > 0) {
                        byte[] msgArray = new byte[length];
                        ByteBuffer message = ByteBuffer.wrap(msgArray);
                        int numbytes = 0;
                        while (message.hasRemaining()) {
                            numbytes += channel.read(message);
                        }
                        message.position(0);
                        synchronized (recvQueue) {
                            recvQueue
                                    .put(new Message(message.duplicate(), addr));
                        }
                        msgLength.position(0);
                    }
                }

            } catch (IOException e) {
                LOG.warn("Connection broken: " + e.toString());

            } catch (InterruptedException e) {
                LOG.warn("Interrupted while trying to add new "
                        + "message to the reception queue (" + e.toString()
                        + ")");
            }
        }
    }
}
