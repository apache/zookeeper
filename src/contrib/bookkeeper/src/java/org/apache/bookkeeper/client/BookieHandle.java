package org.apache.bookkeeper.client;
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
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import javax.crypto.Mac; 
import javax.crypto.spec.SecretKeySpec;

import org.apache.bookkeeper.client.BKException.Code;
import org.apache.bookkeeper.client.LedgerHandle.QMode;
import org.apache.bookkeeper.client.QuorumEngine.Operation;
import org.apache.bookkeeper.client.QuorumEngine.Operation.StopOp;
import org.apache.bookkeeper.client.QuorumEngine.SubOp;
import org.apache.bookkeeper.client.QuorumEngine.Operation.AddOp;
import org.apache.bookkeeper.client.QuorumEngine.SubOp.SubAddOp;
import org.apache.bookkeeper.client.QuorumEngine.SubOp.SubReadOp;
import org.apache.bookkeeper.client.QuorumEngine.SubOp.SubStopOp;
import org.apache.bookkeeper.proto.BookieClient;
import org.apache.log4j.Logger;


/**
 * Maintains a queue of request to a given bookie. For verifiable
 * ledgers, it computes the digest.
 * 
 */

public class BookieHandle extends Thread {
    static Logger LOG = Logger.getLogger(BookieClient.class);
    
    volatile boolean stop = false;
    boolean noreception = false;
    private BookieClient client;
    InetSocketAddress addr;
    static int recvTimeout = 2000;
    private ArrayBlockingQueue<ToSend> incomingQueue;
    private int refCount = 0;
    HashSet<LedgerHandle> ledgers;
    
    /**
     * Objects of this class are queued waiting to be
     * processed.
     */
    private static class ToSend {
    	LedgerHandle lh;
        long entry = -1;
        Object ctx;
        int type;
        
        ToSend(LedgerHandle lh, SubOp sop, long entry){
        	this.lh = lh;
            this.type = sop.op.type;
            this.entry = entry;
            this.ctx = sop;
        }
    }
    
    /**
     * @param addr	address of the bookkeeper server that this
     * handle should connect to.
     */
    BookieHandle(InetSocketAddress addr, boolean enabled) throws IOException {
        this.stop = !enabled;
        this.noreception = !enabled;
        if(!stop)
            this.client = new BookieClient(addr, recvTimeout);
        else
            this.client = null;
        
        this.addr = addr;
        this.incomingQueue = new ArrayBlockingQueue<ToSend>(2000);
        this.ledgers = new HashSet<LedgerHandle>();
    }
    
    
    /**
     * Restart BookieClient if can't talk to bookie
     * 
     * @return
     * @throws IOException
     */
    void restart() throws IOException {
        this.client = new BookieClient(addr, recvTimeout);
    }

    /**
     * Sending add operation to bookie. We have to synchronize the send to guarantee
     * that requests will either get a response or throw an exception. 
     * 
     * @param r
     * @param cb
     * @param ctx
     * @throws IOException
     */
    public synchronized void sendAdd(LedgerHandle lh, SubAddOp r, long entry)
    throws IOException, BKException {
        try{
            if(!noreception){
                ToSend ts = new ToSend(lh, r, entry);
                if(!incomingQueue.offer(ts, 1000, TimeUnit.MILLISECONDS))
                    throw BKException.create(Code.BookieHandleNotAvailableException);
            } else {
                throw BKException.create(Code.BookieHandleNotAvailableException);
            }
        } catch(InterruptedException e){
            LOG.warn("Interrupted while waiting for room in the incoming queue");
        }
    }
    
    private synchronized void sendStop(){
        try{
            noreception = true;
            LOG.debug("Sending stop signal");
            incomingQueue.put(new ToSend(null, new SubStopOp(new StopOp()), -1));
            LOG.debug("Sent stop signal");
        } catch(InterruptedException e) {
            LOG.fatal("Interrupted while sending stop signal to bookie handle");
        }       
    }
    /**
     * MAC instance
     * 
     */
    Mac mac = null;
    
    Mac getMac(byte[] macKey, String alg)
    throws NoSuchAlgorithmException, InvalidKeyException {
        if(mac == null){
            mac = Mac.getInstance(alg);
            mac.init(new SecretKeySpec(macKey, "HmacSHA1"));
        }
        
        return mac;
    }
    
    /**
     * Sending read operation to bookie
     * 
     * @param r
     * @param entry
     * @param cb
     * @param ctx
     * @throws IOException
     */
    
    public synchronized void sendRead(LedgerHandle lh, SubReadOp r, long entry)
    throws IOException, BKException {
        try{
            if(!noreception){           
                ToSend ts = new ToSend(lh, r, entry);
                if(!incomingQueue.offer(ts, 1000, TimeUnit.MILLISECONDS))
                    throw BKException.create(Code.BookieHandleNotAvailableException);
            } else {
                throw BKException.create(Code.BookieHandleNotAvailableException);
            }
        } catch(InterruptedException e){
            LOG.warn("Interrupted while waiting for room in the incoming queue");
        }
    }
    
    public void run(){
        ToSend ts;
        
        try{
            while(!stop){
                ts = incomingQueue.poll(1000, TimeUnit.MILLISECONDS);
                    
                if(ts != null){
                	LedgerHandle self = ts.lh;
                    switch(ts.type){
                    case Operation.STOP:
                        LOG.info("Stopping BookieHandle: " + addr);
                        client.errorOut();                   
                        cleanQueue();
                        LOG.debug("Stopped");
                        break;
                    case Operation.ADD:
                        SubAddOp aOp = (SubAddOp) ts.ctx;
                        AddOp op = ((AddOp) aOp.op);
                        
                        long confirmed = self.getAddConfirmed();
                        ByteBuffer extendedData;
    
                        if(self.getQMode() == QMode.VERIFIABLE){
                            extendedData = ByteBuffer.allocate(op.data.length + 28 + 16);
                            extendedData.putLong(self.getId());
                            extendedData.putLong(ts.entry);
                            extendedData.putLong(confirmed);
                            extendedData.put(op.data);
                        
                        
                            extendedData.rewind();
                            byte[] toProcess = new byte[op.data.length + 24];
                            extendedData.get(toProcess, 0, op.data.length + 24);
                            extendedData.position(extendedData.capacity() - 20);
                            if(mac == null)
                                getMac(self.getMacKey(), "HmacSHA1");
                            extendedData.put(mac.doFinal(toProcess));
                            extendedData.position(16);
                        } else {
                            extendedData = ByteBuffer.allocate(op.data.length + 8);
                            extendedData.putLong(confirmed);
                            extendedData.put(op.data);
                            extendedData.flip();
                        }
                        
                        client.addEntry(self.getId(),
                                self.getLedgerKey(),
                                ts.entry, 
                                extendedData, 
                                aOp.wcb,
                                ts.ctx);
                        break;
                    case Operation.READ:
                        if(client != null)
                            client.readEntry(self.getId(),
                                    ts.entry,
                                    ((SubReadOp) ts.ctx).rcb,
                                    ts.ctx);
                        else ((SubReadOp) ts.ctx).rcb.readEntryComplete(-1, ts.lh.getId(), ts.entry, null, ts.ctx);
                        break;
                    }
                } else LOG.debug("Empty queue: " + addr);
            }
        } catch (Exception e){
            LOG.error("Handling exception before halting BookieHandle", e);
            for(LedgerHandle lh : ledgers)
                lh.removeBookie(this);
            
            /*
             * We only need to synchronize when setting noreception to avoid that
             * a client thread add another request to the incomingQueue after we
             * have cleaned it.
             */
            synchronized(this){
                noreception = true;
            }
            client.halt();
            client.errorOut();
            cleanQueue();
        } 
        
        LOG.info("Exiting bookie handle thread: " + addr);
    }
        
    
    /**
     * Multiple ledgers may use the same BookieHandle object, so we keep
     * a count on the number of references.
     */
    int incRefCount(LedgerHandle lh){
        ledgers.add(lh);
        return ++refCount;
    }
    
    /**
     * Halts if there is no ledger using this object.
     *
     * @return  int reference counter
     */
    synchronized int halt(LedgerHandle lh){
        LOG.info("Calling halt");
        ledgers.remove(lh);
        int currentCount = --refCount;
        if(currentCount <= 0){
            shutdown();
        }
        
        if(currentCount < 0)
            LOG.warn("Miscalculated the number of reference counts: " + addr);

        return currentCount;
    }
    
    /**
     * Halt this bookie handle independent of the number of ledgers using it. Called upon a 
     * failure to write. This method cannot be called by this thread because it may cause a
     * deadlock as shutdown invokes sendStop. The deadlock comes from sendAdd blocking on
     * incomingQueue when the queue is full and the thread also blocking on it when
     * trying to send the stop marker. Because this thread is actually the consumer, if it
     * does not make progress, then we have a deadlock. 
     * 
     * @return int  reference counter
     */
    synchronized public int halt(){
        if(!stop){
            LOG.info("Calling halt");
            for(LedgerHandle lh : ledgers)
                lh.removeBookie(this);
            refCount = 0;
            shutdown();
        }
     
        return refCount;
    }
    
    /**
     * Stop this bookie handle completely.
     * 
     */
    public void shutdown(){
        if(!stop){
            LOG.info("Calling shutdown");
            LOG.debug("Halting client");
            client.halt();
            LOG.debug("Cleaning queue");
            sendStop();
            LOG.debug("Finished shutdown"); 
        }
    }
    
    /**
     * Invokes the callback method for pending requests in the queue
     * of this BookieHandle.
     */
    private void cleanQueue(){
        stop = true;
        ToSend ts = incomingQueue.poll();
        while(ts != null){
            switch(ts.type){
            case Operation.ADD:
                SubAddOp aOp = (SubAddOp) ts.ctx;
                aOp.wcb.writeComplete(-1, ts.lh.getId(), ts.entry, ts.ctx);
     
                break;
            case Operation.READ:                
                ((SubReadOp) ts.ctx).rcb.readEntryComplete(-1, ts.lh.getId(), ts.entry, null, ts.ctx);
                break;
            }
            ts = incomingQueue.poll();
        }
    }
                
    /**
     * Returns the negated value of stop, which gives the status of the
     * BookieHandle.
     */
    
    boolean isEnabled(){
        return !stop;
    }
}

    
