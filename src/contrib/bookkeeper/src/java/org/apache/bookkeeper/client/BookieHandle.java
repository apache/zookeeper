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
import java.security.MessageDigest;

import org.apache.bookkeeper.client.LedgerHandle.QMode;
import org.apache.bookkeeper.client.QuorumEngine.Operation;
import org.apache.bookkeeper.client.QuorumEngine.SubOp;
import org.apache.bookkeeper.client.QuorumEngine.Operation.AddOp;
import org.apache.bookkeeper.client.QuorumEngine.Operation.ReadOp;
import org.apache.bookkeeper.client.QuorumEngine.SubOp.SubAddOp;
import org.apache.bookkeeper.client.QuorumEngine.SubOp.SubReadOp;
import org.apache.bookkeeper.proto.BookieClient;
import org.apache.bookkeeper.proto.ReadEntryCallback;
import org.apache.bookkeeper.proto.WriteCallback;
import org.apache.log4j.Logger;


/**
 * Maintains a queue of request to a given bookie. For verifiable
 * ledgers, it computes the digest.
 * 
 */

public class BookieHandle extends Thread{
    Logger LOG = Logger.getLogger(BookieClient.class);
    
    boolean stop = false;
    LedgerHandle self;
    BookieClient client;
    InetSocketAddress addr;
    static int recvTimeout = 2000;
    ArrayBlockingQueue<ToSend> incomingQueue;
    
    /**
     * Objects of this class are queued waiting to be
     * processed.
     */
    class ToSend {
        long entry = -1;
        Object ctx;
        int type;
        
        ToSend(SubOp sop, long entry){
            this.type = sop.op.type;
            this.entry = entry;
            this.ctx = sop;
        }
    }
    
    /**
     * @param lh	ledger handle
     * @param addr	address
     */
    BookieHandle(LedgerHandle lh, InetSocketAddress addr) throws IOException {
        this.client = new BookieClient(addr, recvTimeout);
        this.self = lh;
        this.addr = addr;
        this.incomingQueue = new ArrayBlockingQueue<ToSend>(2000);
        
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
    public void sendAdd(SubAddOp r, long entry)
    throws IOException {
        try{
            incomingQueue.put(new ToSend(r, entry));
        } catch(InterruptedException e){
            e.printStackTrace();
        }
        //client.addEntry(self.getId(), 
        //        r.entry, 
        //        ByteBuffer.wrap(r.data), 
        //        cb,
        //        ctx);
    }
    
    /**
     * Message disgest instance
     * 
     */
    MessageDigest digest = null;
    
    /** 
     * Get digest instance if there is none.
     * 
     */
    MessageDigest getDigestInstance(String alg)
    throws NoSuchAlgorithmException {
        if(digest == null){
            digest = MessageDigest.getInstance(alg);
        }
        
        return digest;
    }
    
    /**
     * Computes the digest for a given ByteBuffer.
     * 
     */
    
    public ByteBuffer addDigest(long entryId, ByteBuffer data)
    throws NoSuchAlgorithmException, IOException {
        if(digest == null)
            getDigestInstance(self.getDigestAlg());
        
        ByteBuffer bb = ByteBuffer.allocate(8 + 8);
        bb.putLong(self.getId());
        bb.putLong(entryId);
        
        byte[] msgDigest;
        
        // synchronized(LedgerHandle.digest){
        digest.update(self.getPasswdHash());
        digest.update(bb.array());
        digest.update(data.array());
            
        //baos.write(data);
        //baos.write(Operation.digest.digest());
        msgDigest = digest.digest();
        //}
        ByteBuffer extendedData = ByteBuffer.allocate(data.capacity() + msgDigest.length);
        data.rewind();
        extendedData.put(data);
        extendedData.put(msgDigest);
        
        //LOG.debug("Data length (" + self.getId() + ", " + entryId + "): " + data.capacity());
        //LOG.debug("Digest: " + new String(msgDigest));
        
        return extendedData;
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
    public void sendRead(SubReadOp r, long entry)
    throws IOException {
        //LOG.debug("readEntry: " + entry);
        try{
            incomingQueue.put(new ToSend(r, entry));
        } catch(InterruptedException e){
            e.printStackTrace();
        }
    }
    
    public void run(){
        while(!stop){
            try{
                ToSend ts = incomingQueue.poll(1000, TimeUnit.MILLISECONDS);
                if(ts != null){
                    switch(ts.type){
                    case Operation.ADD:
                        SubAddOp aOp = (SubAddOp) ts.ctx;
                        AddOp op = ((AddOp) aOp.op);
                        
                        /*
                         * TODO: Really add the confirmed add to the op
                         */
                        long confirmed = self.getAddConfirmed();
                        //LOG.info("Confirmed: " + confirmed);
                        ByteBuffer extendedData = ByteBuffer.allocate(op.data.length + 8);
                        extendedData.putLong(confirmed);
                        extendedData.put(op.data);
                        extendedData.rewind();
                        
                        if(self.getQMode() == QMode.VERIFIABLE){
                            extendedData = addDigest(ts.entry, extendedData);
                        }
                        
                        //LOG.debug("Extended data: " + extendedData.capacity());
                        client.addEntry(self.getId(), 
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
            }
        }
    }
    
    void halt(){
        stop = true;
    }
}

    
