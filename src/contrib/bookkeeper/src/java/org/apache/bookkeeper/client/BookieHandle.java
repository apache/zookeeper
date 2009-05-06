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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import javax.crypto.Mac; 
import javax.crypto.spec.SecretKeySpec;

import org.apache.bookkeeper.client.LedgerHandle.QMode;
import org.apache.bookkeeper.client.QuorumEngine.Operation;
import org.apache.bookkeeper.client.QuorumEngine.SubOp;
import org.apache.bookkeeper.client.QuorumEngine.Operation.AddOp;
import org.apache.bookkeeper.client.QuorumEngine.SubOp.SubAddOp;
import org.apache.bookkeeper.client.QuorumEngine.SubOp.SubReadOp;
import org.apache.bookkeeper.proto.BookieClient;
import org.apache.log4j.Logger;


/**
 * Maintains a queue of request to a given bookie. For verifiable
 * ledgers, it computes the digest.
 * 
 */

class BookieHandle extends Thread{
    Logger LOG = Logger.getLogger(BookieClient.class);
    
    boolean stop = false;
    private BookieClient client;
    InetSocketAddress addr;
    static int recvTimeout = 2000;
    private ArrayBlockingQueue<ToSend> incomingQueue;
    private int refCount = 0;
    
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
    BookieHandle(InetSocketAddress addr) throws IOException {
        this.client = new BookieClient(addr, recvTimeout);
        this.addr = addr;
        this.incomingQueue = new ArrayBlockingQueue<ToSend>(2000);
        
        //genSecurePadding();
        start();
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
     * Sending add operation to bookie
     * 
     * @param r
     * @param cb
     * @param ctx
     * @throws IOException
     */
    public void sendAdd(LedgerHandle lh, SubAddOp r, long entry)
    throws IOException {
        try{
            incomingQueue.put(new ToSend(lh, r, entry));
        } catch(InterruptedException e){
            e.printStackTrace();
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
    
    public void sendRead(LedgerHandle lh, SubReadOp r, long entry)
    throws IOException {
        try{
            incomingQueue.put(new ToSend(lh, r, entry));
        } catch(InterruptedException e){
            e.printStackTrace();
        }
    }
    
    public void run(){
        while(!stop){
            try{
                ToSend ts = incomingQueue.poll(1000, TimeUnit.MILLISECONDS);
                if(ts != null){
                	LedgerHandle self = ts.lh;
                    switch(ts.type){
                    case Operation.ADD:
                        SubAddOp aOp = (SubAddOp) ts.ctx;
                        AddOp op = ((AddOp) aOp.op);
                        
                        /*
                         * TODO: Really add the confirmed add to the op
                         */
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
                            //extendedData.limit(extendedData.capacity() - 20);
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
                        client.readEntry(self.getId(),
                            ts.entry,
                            ((SubReadOp) ts.ctx).rcb,
                            ts.ctx);
                        break;
                    }
                }
            } catch (InterruptedException e){
                LOG.error(e);
            } catch (IOException e){
                LOG.error(e);
            } catch (NoSuchAlgorithmException e){
                LOG.error(e);
            } catch (InvalidKeyException e) {
                LOG.error(e);
            }
        }
    }
    
    /**
     * Multiple ledgers may use the same BookieHandle object, so we keep
     * a count on the number of references.
     */
    int incRefCount(){
        return ++refCount;
    }
    
    /**
     * Halts if there is no ledger using this object.
     */
    int halt(){
        int currentCount = --refCount;
        if(currentCount <= 0){
            stop = true;
        }
        
        if(currentCount < 0)
            LOG.warn("Miscalculated the number of reference counts: " + addr);
        
        return currentCount;
    }
}

    
