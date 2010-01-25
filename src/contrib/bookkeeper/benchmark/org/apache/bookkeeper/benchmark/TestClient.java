package org.apache.bookkeeper.benchmark;
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


import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;

import org.apache.bookkeeper.client.AddCallback;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerEntry;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.LedgerSequence;
import org.apache.bookkeeper.client.QuorumEngine;
import org.apache.bookkeeper.client.ReadCallback;
import org.apache.bookkeeper.client.LedgerHandle.QMode;
import org.apache.log4j.Logger;

import org.apache.zookeeper.KeeperException;

/**
 * This is a simple test program to compare the performance of writing to
 * BookKeeper and to the local file system.
 * 
 */

public class TestClient 
    implements AddCallback, ReadCallback{
    private static final Logger LOG = Logger.getLogger(TestClient.class);
    
    BookKeeper x;
    LedgerHandle lh;
    Integer entryId;
    HashMap<Integer, Integer> map;
    
    FileOutputStream fStream;
    FileOutputStream fStreamLocal;
    long start, lastId;
    
    public TestClient() {
        entryId = 0;
        map = new HashMap<Integer, Integer>();
    }
    
    public TestClient(String servers) throws KeeperException, IOException, InterruptedException{
        this();
        x = new BookKeeper(servers);
        try{
        lh = x.createLedger(new byte[] {'a', 'b'});
        } catch (BKException e) {
            LOG.error(e.toString());
        }
    }
    
    public TestClient(String servers, int ensSize, int qSize)
    throws KeeperException, IOException, InterruptedException{
        this();
        x = new BookKeeper(servers);
        try{
        lh = x.createLedger(ensSize, qSize, QMode.VERIFIABLE, new byte[] {'a', 'b'});
        } catch (BKException e) {
            LOG.error(e.toString());
        }
    }
    
    public TestClient(FileOutputStream fStream)
    throws FileNotFoundException {
        this.fStream = fStream;
        this.fStreamLocal = new FileOutputStream("./local.log");
    }
    
    
    public Integer getFreshEntryId(int val){
        ++this.entryId;
        synchronized (map) {
            map.put(this.entryId, val);
        }
        return this.entryId;
    }
    
    public boolean removeEntryId(Integer id){
        boolean retVal = false;
        synchronized (map) {
                map.remove(id);
                retVal = true;
     
            if(map.size() == 0) map.notifyAll();
            else{
                if(map.size() < 4)
                    LOG.error(map.toString());
            }
        }
        return retVal;
    }

    public void closeHandle() throws KeeperException, InterruptedException{
        x.closeLedger(lh);
    }
    /**
     * First says if entries should be written to BookKeeper (0) or to the local
     * disk (1). Second parameter is an integer defining the length of a ledger entry. 
     * Third parameter is the number of writes.
     * 
     * @param args
     */
    public static void main(String[] args) {
        
        int lenght = Integer.parseInt(args[1]);
        StringBuilder sb = new StringBuilder();
        while(lenght-- > 0){
            sb.append('a');
        }
        
        Integer selection = Integer.parseInt(args[0]);
        switch(selection){
        case 0:           
            StringBuilder servers_sb = new StringBuilder();
            for (int i = 4; i < args.length; i++){
                servers_sb.append(args[i] + " ");
            }
        
            String servers = servers_sb.toString().trim().replace(' ', ',');
            try {
                TestClient c = new TestClient(servers, Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                c.writeSameEntryBatch(sb.toString().getBytes(), Integer.parseInt(args[2]));
                //c.writeConsecutiveEntriesBatch(Integer.parseInt(args[0]));
                c.closeHandle();
            } catch (NumberFormatException e) {
                LOG.error(e);
            } catch (InterruptedException e) {
                LOG.error(e);
            } catch (KeeperException e) {
                LOG.error(e);
            } catch (IOException e) {
                LOG.error(e);
            }
            break;
        case 1:
            
            try{
                TestClient c = new TestClient(new FileOutputStream(args[2]));
                c.writeSameEntryBatchFS(sb.toString().getBytes(), Integer.parseInt(args[3]));
            } catch(FileNotFoundException e){
                LOG.error(e);
            }
            break;
        case 2:
            break;
        }
    }

    void writeSameEntryBatch(byte[] data, int times) throws InterruptedException{
        start = System.currentTimeMillis();
        int count = times;
        LOG.debug("Data: " + new String(data) + ", " + data.length);
        while(count-- > 0){
            x.asyncAddEntry(lh, data, this, this.getFreshEntryId(2));
        }
        LOG.debug("Finished " + times + " async writes in ms: " + (System.currentTimeMillis() - start));       
        synchronized (map) {
            if(map.size() != 0)
                map.wait();
        }
        LOG.debug("Finished processing in ms: " + (System.currentTimeMillis() - start));
        
        LOG.debug("Ended computation");
    }
    
    void writeConsecutiveEntriesBatch(int times) throws InterruptedException{
        start = System.currentTimeMillis();
        int count = times;
        while(count-- > 0){
            byte[] write = new byte[2];
            int j = count%100;
            int k = (count+1)%100;
            write[0] = (byte) j;
            write[1] = (byte) k;
            x.asyncAddEntry(lh, write, this, this.getFreshEntryId(2));
        }
        LOG.debug("Finished " + times + " async writes in ms: " + (System.currentTimeMillis() - start));       
        synchronized (map) {
            if(map.size() != 0)
                map.wait();
        }
        LOG.debug("Finished processing writes (ms): " + (System.currentTimeMillis() - start));
        
        Integer mon = Integer.valueOf(0);
        synchronized(mon){
            try{
                x.asyncReadEntries(lh, 1, times - 1, this, mon);
                mon.wait();
            } catch (BKException e){
                LOG.error(e);
            }
        }
        LOG.error("Ended computation");
    }

    void writeSameEntryBatchFS(byte[] data, int times) {
        int count = times;
        LOG.debug("Data: " + data.length + ", " + times);
        try{
            start = System.currentTimeMillis();
            while(count-- > 0){
                fStream.write(data);
                fStreamLocal.write(data);
                fStream.flush();
            }
            fStream.close();
            System.out.println("Finished processing writes (ms): " + (System.currentTimeMillis() - start));
        } catch(IOException e){
            LOG.error(e);
        }
    }
        
   
    public void addComplete(int rc, long ledgerId, long entryId, Object ctx) {
        this.removeEntryId((Integer) ctx);
    }
   
    public void readComplete(int rc, long ledgerId, LedgerSequence seq, Object ctx){
        System.out.println("Read callback: " + rc);
        while(seq.hasMoreElements()){
            LedgerEntry le = seq.nextElement();
            LOG.debug(new String(le.getEntry()));
        }
        synchronized(ctx){
            ctx.notify();
        }
    }
}
