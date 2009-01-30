package org.apache.bookkeeper.proto;
/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */


import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;


import org.apache.bookkeeper.proto.ReadEntryCallback;
import org.apache.bookkeeper.proto.WriteCallback;
import org.apache.log4j.Logger;



/**
 * Implements the client-side part of the BookKeeper protocol. 
 * 
 */
public class BookieClient extends Thread {
	Logger LOG = Logger.getLogger(BookieClient.class);
    SocketChannel sock;
    int myCounter = 0;

    public BookieClient(InetSocketAddress addr, int recvTimeout)
    throws IOException, ConnectException {
        sock = SocketChannel.open(addr);
        setDaemon(true);
        //sock.configureBlocking(false);
        sock.socket().setSoTimeout(recvTimeout);
        sock.socket().setTcpNoDelay(true);
        start();
    }
    
    public BookieClient(String host, int port, int recvTimeout)
    throws IOException, ConnectException {
        this(new InetSocketAddress(host, port), recvTimeout);
    }
    
    private static class Completion<T> {
        Completion(T cb, Object ctx) {
            this.cb = cb;
            this.ctx = ctx;
        }

        T cb;

        Object ctx;
    }

    private static class CompletionKey {
        long ledgerId;

        long entryId;

        CompletionKey(long ledgerId, long entryId) {
            this.ledgerId = ledgerId;
            this.entryId = entryId;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof CompletionKey) || obj == null) {
                return false;
            }
            CompletionKey that = (CompletionKey) obj;
            return this.ledgerId == that.ledgerId && this.entryId == that.entryId;
        }

        @Override
        public int hashCode() {
            return ((int) ledgerId << 16) ^ ((int) entryId);
        }

    }

    ConcurrentHashMap<CompletionKey, Completion<WriteCallback>> addCompletions = new ConcurrentHashMap<CompletionKey, Completion<WriteCallback>>();
    ConcurrentHashMap<CompletionKey, Completion<ReadEntryCallback>> readCompletions = new ConcurrentHashMap<CompletionKey, Completion<ReadEntryCallback>>();
    
    Object writeLock = new Object();
    Object readLock = new Object();
    
    /*
     * Use this semaphore to control the number of completion key in both addCompletions
     * and readCompletions. This is more of a problem for readCompletions because one
     * readEntries opertion is expanded into individual operations to read entries.
     */
    Semaphore completionSemaphore = new Semaphore(1000);
    
   
    /**
     * Send addEntry operation to bookie.
     * 
     * @param ledgerId	ledger identifier
     * @param entryId 	entry identifier
     * @param cb		object implementing callback method
     * @param ctx		control object
     * @throws IOException
     * @throws InterruptedException
     */
    public void addEntry(long ledgerId, long entryId,
            ByteBuffer entry, WriteCallback cb, Object ctx) 
    throws IOException, InterruptedException {
        
        //LOG.info("Data length: " + entry.capacity());
    	completionSemaphore.acquire();
        addCompletions.put(new CompletionKey(ledgerId, entryId),
                new Completion<WriteCallback>(cb, ctx));
        //entry = entry.duplicate();
        entry.position(0);
        
        ByteBuffer tmpEntry = ByteBuffer.allocate(entry.capacity() + 8 + 8 + 8);

        tmpEntry.position(4);
        tmpEntry.putInt(BookieProtocol.ADDENTRY);
        tmpEntry.putLong(ledgerId);
        tmpEntry.putLong(entryId);
        tmpEntry.put(entry);
        tmpEntry.position(0);
        
        //ByteBuffer len = ByteBuffer.allocate(4);
        // 4 bytes for the message type
        tmpEntry.putInt(tmpEntry.remaining() - 4);
        tmpEntry.position(0);
        synchronized(writeLock) {
            //sock.write(len);
            //len.clear();
            //len.putInt(BookieProtocol.ADDENTRY);
            //len.flip();
            //sock.write(len);
            sock.write(tmpEntry);
        }
        //LOG.debug("addEntry:finished");
    }
    
    /**
     * Send readEntry operation to bookie.
     * 
     * @param ledgerId	ledger identifier
     * @param entryId	entry identifier
     * @param cb		object implementing callback method
     * @param ctx		control object
     * @throws IOException
     */
    public void readEntry(long ledgerId, long entryId,
            ReadEntryCallback cb, Object ctx) 
    throws IOException, InterruptedException {
    	
    	completionSemaphore.acquire();
        readCompletions.put(new CompletionKey(ledgerId, entryId),
                new Completion<ReadEntryCallback>(cb, ctx));
        ByteBuffer tmpEntry = ByteBuffer.allocate(8 + 8);
        tmpEntry.putLong(ledgerId);
        tmpEntry.putLong(entryId);
        tmpEntry.position(0);
        
        ByteBuffer len = ByteBuffer.allocate(4);
        len.putInt(tmpEntry.remaining() + 4);
        len.flip();
        //LOG.debug("readEntry: Writing to socket");
        synchronized(readLock) {
            sock.write(len);
            len.clear();
            len.putInt(BookieProtocol.READENTRY);
            len.flip();
            sock.write(len);
            sock.write(tmpEntry);
        }
        //LOG.error("Size of readCompletions: " + readCompletions.size());
    }
    
    private void readFully(ByteBuffer bb) throws IOException {
        while(bb.remaining() > 0) {
            sock.read(bb);
        }
    }
    
    public void run() {
        int len = -1;
        ByteBuffer lenBuffer = ByteBuffer.allocate(4);
        int type = -1, rc = -1;
        try {
            while(sock.isConnected()) {
                lenBuffer.clear();
                readFully(lenBuffer);
                lenBuffer.flip();
                len = lenBuffer.getInt();
                ByteBuffer bb = ByteBuffer.allocate(len);
                readFully(bb);
                bb.flip();
                type = bb.getInt();
                rc = bb.getInt();
 
                switch(type) {
                case BookieProtocol.ADDENTRY:
                {
                    long ledgerId = bb.getLong();
                    long entryId = bb.getLong();
                    Completion<WriteCallback> ac = addCompletions.remove(new CompletionKey(ledgerId, entryId));
                    completionSemaphore.release();
                    
                    if (ac != null) {
                        ac.cb.writeComplete(rc, ledgerId, entryId, ac.ctx);
                    } else {
                        LOG.error("Callback object null: " + ledgerId + " : " + entryId);
                    }
                    break;
                }
                case BookieProtocol.READENTRY:
                {
                    //ByteBuffer entryData = bb.slice();
                    long ledgerId = bb.getLong();
                    long entryId = bb.getLong();
                    
                    bb.position(24);
                    byte[] data = new byte[bb.capacity() - 24];
                    bb.get(data);
                    ByteBuffer entryData = ByteBuffer.wrap(data);
                    
                    LOG.info("Received entry: " + ledgerId + ", " + entryId + ", " + rc + ", " + entryData.array().length + ", " + bb.array().length + ", " + bb.remaining());
          
                    
                    CompletionKey key = new CompletionKey(ledgerId, entryId);
                    Completion<ReadEntryCallback> c;
                    
                    if(readCompletions.containsKey(key)){
                        c = readCompletions.remove(key);
                        //LOG.error("Found key");
                    }
                    else{    
                        /*
                         * This is a special case. When recovering a ledger, a client submits
                         * a read request with id -1, and receives a response with a different
                         * entry id.
                         */
                        c = readCompletions.remove(new CompletionKey(ledgerId, -1));
                    }
                    completionSemaphore.release();
                    
                    if (c != null) {
                        c.cb.readEntryComplete(rc, 
                                ledgerId, 
                                entryId, 
                                entryData, 
                                c.ctx);
                    }
                    break;
                }
                default:
                    System.err.println("Got error " + rc + " for type " + type);
                }
            }
        } catch(Exception e) {
            LOG.error("Len = " + len + ", Type = " + type + ", rc = " + rc);
            e.printStackTrace();
        }
    }

    private static class Counter {
        int i;
        int total;
        synchronized void inc() {
            i++;
            total++;
        }
        synchronized void dec() {
            i--;
            notifyAll();
        }
        synchronized void wait(int limit) throws InterruptedException {
            while(i > limit) {
                wait();
            }
        }
        synchronized int total() {
            return total;
        }
    }
    /**
     * @param args
     * @throws IOException 
     * @throws NumberFormatException 
     * @throws InterruptedException 
     */
    public static void main(String[] args) throws NumberFormatException, IOException, InterruptedException {
        if (args.length != 3) {
            System.err.println("USAGE: BookieClient bookieHost port ledger#");
            return;
        }
        WriteCallback cb = new WriteCallback() {

            public void writeComplete(int rc, long ledger, long entry, Object ctx) {
                Counter counter = (Counter)ctx;
                counter.dec();
                if (rc != 0) {
                    System.out.println("rc = " + rc + " for " + entry + "@" + ledger);
                }
            }
        };
        Counter counter = new Counter();
        byte hello[] = "hello".getBytes();
        long ledger = Long.parseLong(args[2]);
        BookieClient bc = new BookieClient(args[0], Integer.parseInt(args[1]), 5000);
        for(int i = 0; i < 100000; i++) {
            ByteBuffer entry = ByteBuffer.allocate(100);
            entry.putLong(ledger);
            entry.putLong(i);
            entry.putInt(0);
            entry.put(hello);
            entry.flip();
            counter.inc();
            bc.addEntry(ledger, i, entry, cb, counter);
        }
        counter.wait(0);
        System.out.println("Total = " + counter.total());
    }
}
