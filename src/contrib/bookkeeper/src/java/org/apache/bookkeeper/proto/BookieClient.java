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
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.Enumeration;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import javax.crypto.Mac; 
import javax.crypto.spec.SecretKeySpec;

//import org.apache.bookkeeper.client.AsyncCallback.FailCallback;
import org.apache.bookkeeper.client.BookieHandle;
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
        startConnection(addr, recvTimeout);
    }
    
    public BookieClient(String host, int port, int recvTimeout)
    throws IOException, ConnectException {
        this(new InetSocketAddress(host, port), recvTimeout);
    }
    
    public void startConnection(InetSocketAddress addr, int recvTimeout)
    throws IOException, ConnectException {
        sock = SocketChannel.open(addr);
        setDaemon(true);
        //sock.configureBlocking(false);
        sock.socket().setSoTimeout(recvTimeout);
        sock.socket().setTcpNoDelay(true);
        start();        
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

    ConcurrentHashMap<CompletionKey, Completion<WriteCallback>> addCompletions = 
        new ConcurrentHashMap<CompletionKey, Completion<WriteCallback>>();
    
    ConcurrentHashMap<CompletionKey, Completion<ReadEntryCallback>> readCompletions =
        new ConcurrentHashMap<CompletionKey, Completion<ReadEntryCallback>>();
    
    /*
     * Use this semaphore to control the number of completion key in both addCompletions
     * and readCompletions. This is more of a problem for readCompletions because one
     * readEntries opertion is expanded into individual operations to read entries.
     */
    Semaphore completionSemaphore = new Semaphore(3000);
    
   
    /**
     * Message disgest instance
     * 
     */
    MessageDigest digest = null;
    
    /** 
     * Get digest instance if there is none.
     * 
     */
    public MessageDigest getDigestInstance(String alg)
    throws NoSuchAlgorithmException {
        if(digest == null){
            digest = MessageDigest.getInstance(alg);
        }
        
        return digest;
    }
    
    /**
     * Mac instance
     * 
     */
    Mac mac = null;
    
    public Mac getMac(String alg, byte[] key)
    throws NoSuchAlgorithmException, InvalidKeyException {
        if(mac == null){
            mac = Mac.getInstance(alg);
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
        }
        
        return mac;
    }
    
    /**
     * Send addEntry operation to bookie. It throws an IOException
     * if either the write to the socket fails or it takes too long
     * to obtain a permit to send another request, which possibly 
     * implies that the corresponding bookie is down.
     * 
     * @param ledgerId	ledger identifier
     * @param entryId 	entry identifier
     * @param cb		object implementing callback method
     * @param ctx		control object
     * @throws IOException
     * @throws InterruptedException
     */
    synchronized public void addEntry(long ledgerId, byte[] masterKey, long entryId,
            ByteBuffer entry, WriteCallback cb, Object ctx) 
    throws IOException, InterruptedException {
        
        if(cb == null)
            LOG.error("WriteCallback object is null: " + entryId);
        addCompletions.put(new CompletionKey(ledgerId, entryId),
                new Completion<WriteCallback>(cb, ctx));

        ByteBuffer tmpEntry = ByteBuffer.allocate(entry.remaining() + 44);

        tmpEntry.position(4);
        tmpEntry.putInt(BookieProtocol.ADDENTRY);
        tmpEntry.put(masterKey);
        tmpEntry.putLong(ledgerId);
        tmpEntry.putLong(entryId);
        tmpEntry.put(entry);
        tmpEntry.position(0);
        
        // 4 bytes for the message type
        tmpEntry.putInt(tmpEntry.remaining() - 4);
        tmpEntry.position(0);

        
        if(!sock.isConnected() || 
                !completionSemaphore.tryAcquire(1000, TimeUnit.MILLISECONDS)){ 
            throw new IOException();
        } else sock.write(tmpEntry);
    }
    
    /**
     * Send readEntry operation to bookie. It throws an IOException
     * if either the write to the socket fails or it takes too long
     * to obtain a permit to send another request, which possibly 
     * implies that the corresponding bookie is down.
     * 
     * @param ledgerId	ledger identifier
     * @param entryId	entry identifier
     * @param cb		object implementing callback method
     * @param ctx		control object
     * @throws IOException
     */
    synchronized public void readEntry(long ledgerId, long entryId,
            ReadEntryCallback cb, Object ctx) 
    throws IOException, InterruptedException {
        //LOG.info("Entry id: " + entryId);
    	//completionSemaphore.acquire();
        readCompletions.put(new CompletionKey(ledgerId, entryId),
                new Completion<ReadEntryCallback>(cb, ctx));
        
        ByteBuffer tmpEntry = ByteBuffer.allocate(8 + 8 + 8);
        tmpEntry.putInt(20);
        tmpEntry.putInt(BookieProtocol.READENTRY);
        tmpEntry.putLong(ledgerId);
        tmpEntry.putLong(entryId);
        tmpEntry.position(0);

        if(!sock.isConnected() || 
                !completionSemaphore.tryAcquire(1000, TimeUnit.MILLISECONDS)){ 
            throw new IOException();
        } else sock.write(tmpEntry);
    }
    
    private void readFully(ByteBuffer bb) throws IOException {
        while(bb.remaining() > 0) {
            sock.read(bb);
        }
    }
    
    Semaphore running = new Semaphore(0);
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

                    Completion<WriteCallback> ac;
                    ac = addCompletions.remove(new CompletionKey(ledgerId, entryId));
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
                    long ledgerId = bb.getLong();
                    long entryId = bb.getLong();
                    
                    bb.position(24);
                    byte[] data = new byte[bb.capacity() - 24];
                    bb.get(data);
                    ByteBuffer entryData = ByteBuffer.wrap(data);         
                    
                    CompletionKey key = new CompletionKey(ledgerId, entryId);
                    Completion<ReadEntryCallback> c;
                    
                    if(readCompletions.containsKey(key)){
                            c = readCompletions.remove(key);
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
        }
        running.release();
        
    }
    
    /**
     * Errors out pending entries. We call this method from one thread to avoid
     * concurrent executions to QuorumOpMonitor (implements callbacks). It seems
     * simpler to call it from BookieHandle instead of calling directly from here.
     */
    
    public void errorOut(){
        LOG.info("Erroring out pending entries");
    
        for (Enumeration<CompletionKey> e = addCompletions.keys() ; e.hasMoreElements() ;) {
            CompletionKey key = e.nextElement();
            Completion<WriteCallback> ac = addCompletions.remove(key);
            if(ac != null){
                completionSemaphore.release();
                ac.cb.writeComplete(-1, key.ledgerId, key.entryId, ac.ctx);
            }
        }
        
        LOG.info("Finished erroring out pending add entries");
         
        for (Enumeration<CompletionKey> e = readCompletions.keys() ; e.hasMoreElements() ;) {
            CompletionKey key = e.nextElement();
            Completion<ReadEntryCallback> ac = readCompletions.remove(key);
                
            if(ac != null){
                completionSemaphore.release();
                ac.cb.readEntryComplete(-1, key.ledgerId, key.entryId, null, ac.ctx);
            }
        }
        
        LOG.info("Finished erroring out pending read entries");
    }

    /**
     * Halts client.
     */
    
    public void halt() {
        try{
            sock.close();
        } catch(IOException e) {
            LOG.warn("Exception while closing socket");
        }
        
        try{
            running.acquire();
        } catch(InterruptedException e){
            LOG.error("Interrupted while waiting for running semaphore to acquire lock");
        }
    }
    
    /**
     * Returns the status of the socket of this bookie client.
     * 
     * @return boolean
     */
    public boolean isConnected(){
        return sock.isConnected();
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
            bc.addEntry(ledger, new byte[0], i, entry, cb, counter);
        }
        counter.wait(0);
        System.out.println("Total = " + counter.total());
    }
}
