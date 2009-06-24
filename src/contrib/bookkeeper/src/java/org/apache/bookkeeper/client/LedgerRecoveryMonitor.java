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


import java.lang.Math;
import java.lang.InterruptedException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.TreeMap;

//import org.apache.bookkeeper.client.AsyncCallback.FailCallback;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerSequence;
import org.apache.bookkeeper.client.LedgerHandle.QMode;
import org.apache.bookkeeper.proto.BookieClient;
import org.apache.bookkeeper.proto.ReadEntryCallback;
import org.apache.log4j.Logger;

import org.apache.zookeeper.KeeperException;

/**
 * Implements the mechanism to recover a ledger that was not closed 
 * correctly. It reads entries from the ledger using the hint field
 * until it finds the last entry written. It then writes to ZooKeeper. 
 * 
 */

class LedgerRecoveryMonitor implements ReadEntryCallback {
    Logger LOG = Logger.getLogger(LedgerRecoveryMonitor.class);
    
    BookKeeper self;
    long lId;
    int qSize;
    QMode qMode;
    ArrayList<InetSocketAddress> bookies;
    ArrayList<BookieClient> clients;
    HashMap<Long, ArrayList<ByteBuffer> > votes;
    TreeMap<Long, Integer > hints;
    AtomicInteger counter;
    
    private int minimum;
    
    /**
     * Constructor simply initiates data structures
     * 
     * @param self  Instance of BookKeeper
     * @param lId   Ledger identifier
     * @param qSize Quorum size
     * @param bookies   List of bookie addresses
     * @param qMode     Quorum mode
     */
    LedgerRecoveryMonitor(BookKeeper self,
            long lId, 
            int qSize, 
            ArrayList<InetSocketAddress> bookies, 
            QMode qMode){
        this.self = self;
        this.lId = lId;
        this.qSize = qSize;
        this.qMode = qMode;
        this.bookies = bookies;
        this.clients = new ArrayList<BookieClient>();
        this.votes = new HashMap<Long, ArrayList<ByteBuffer> >();
        this.hints = new TreeMap<Long, Integer>();
        this.counter = new AtomicInteger(0);
        
        this.minimum = bookies.size();
        if(qMode == QMode.VERIFIABLE){
            this.minimum += 1 - qSize; 
        } else if(qMode == QMode.GENERIC){
            this.minimum -= Math.floor(qSize/2);
        } 
        
    }
    
    
    /**
     * Determines the last entry written to a ledger not closed properly
     * due to a client crash
     * 
     * @param   passwd  
     */
    boolean recover(byte[] passwd) throws 
    IOException, InterruptedException, BKException, KeeperException {
        /*
         * Create BookieClient objects and send a request to each one.
         */
        
        for(InetSocketAddress s : bookies){
            LOG.info(s);
            BookieClient client = new BookieClient(s, 3000);
            clients.add(client);
            client.readEntry(lId,
                    -1,
                    this,
                    null);
        }        
        
        /*
         * Wait until I have received enough responses
         */
        synchronized(counter){
            LOG.info("Counter: " + counter.get() + ", " + minimum + ", " + qMode);
            if(counter.get() < minimum){
                LOG.info("Waiting...");
                counter.wait(5000);
            }
        }
        
        /*
         * Obtain largest hint 
         */ 
        LedgerHandle lh = new LedgerHandle(self, lId, 0, qSize, qMode, passwd);
        for(InetSocketAddress addr : bookies){
            lh.addBookieForReading(addr);
        }
        
        boolean notLegitimate = true;
        long readCounter = 0;
        while(notLegitimate){
            readCounter = getNextHint();
            if(readCounter > -1){
                lh.setLast(readCounter);
                boolean hasMore = true;
                while(hasMore){
                    hasMore = false;
                    LOG.debug("Recovering: " + lh.getLast());
                    LedgerSequence ls = lh.readEntries(lh.getLast(), lh.getLast());
                    LOG.debug("Received entry for: " + lh.getLast());
                    
                    byte[] le = ls.nextElement().getEntry();
                    if(le != null){
                        if(notLegitimate) notLegitimate = false;
                        lh.addEntry(le);
                        hasMore = true;
                    }
                }
            } else break;   
        }
        
        /*
         * Write counter as the last entry of ledger
         */
        if(!notLegitimate){
            lh.setAddConfirmed(readCounter);
            lh.close();
            
            return true;
        } else {
        	lh.setLast(0);
        	lh.close();
        	
        	return false;
        }
                
    }
    
    /**
     * Read callback implementation
     * 
     * @param rc    return code
     * @param ledgerId  Ledger identifier
     * @param entryId   Entry identifier
     * @param bb        Data
     * @param ctx       Control object
     * 
     */
    public void readEntryComplete(int rc, long ledgerId, long entryId, ByteBuffer bb, Object ctx){
        if(rc == 0){
            bb.rewind();
        
            /*
             * Collect new vote
             */
            if(!votes.containsKey(entryId)){            
                votes.put(entryId, new ArrayList<ByteBuffer>());
            }
            votes.get(entryId).add(bb);
         
            /*
             * Extract hint
             */
        
            bb.position(16);
            long hint = bb.getLong();
        
            LOG.info("Received a response: " + rc + ", " + entryId + ", " + hint);
        
            if(!hints.containsKey(hint)){
                hints.put(hint, 0);
            }
            hints.put(hint, hints.get(hint) + 1);
        
            synchronized(counter){
                if(counter.incrementAndGet() >= minimum);
                counter.notify();
            }
        } else {
            LOG.debug("rc != 0");
        }
        
    }
    
    /**
     * Returns one hint at a time. We add a new hint to
     * the "hints" TreeMap used in this method upon a read
     * callback. Such callbacks correspond to returned values from bookies upon a request
     * for the last entry written hint.
     * 
     * @return long next hint
     */
    private long getNextHint(){
        if(hints.size() == 0) return -1;
        
        long hint = hints.lastKey();
        hints.remove(hint);
        
        return hint;
    }
    
}
