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


import java.util.List;
import java.util.ArrayList;
import java.util.NoSuchElementException;

import org.apache.log4j.Logger;

/**
 * Sequence of entries of a ledger. Used to return a sequence of entries
 * upon an asynchornous read call.
 * 
 * This is feature is under construction.
 * 
 */

public class LedgerStream {
    Logger LOG = Logger.getLogger(LedgerStream.class);
    
    private ArrayList<LedgerEntry> pending;
    private ArrayList<LedgerEntry> toDeliver;
    private long index;
    
    
    /**
     * Constructor takes the first entry id expected.
     * 
     *  @param first    long
     */
    public LedgerStream(long first){
        pending = new ArrayList<LedgerEntry>();
        toDeliver = new ArrayList<LedgerEntry>();
        index = first;
    }
    
    /**
     * Read the next entry if available. It blocks if the next entry
     * is not yet available.
     */
    
    public LedgerEntry readEntry(){
        synchronized(toDeliver){
            if(toDeliver.size() == 0){
                try{
                    toDeliver.wait();
                } catch(InterruptedException e){
                    LOG.info("Received an interrupted exception", e);
                }
            }
            return toDeliver.get(0);
        }
    }
    
    /**
     * Invoked upon reception of a new ledger entry.
     * 
     * @param le    a new ledger entry to deliver.
     */
    
    public void addEntry(LedgerEntry le){
        synchronized(toDeliver){
            if(index == le.getEntryId()){
                toDeliver.add(le);
                index++;
                
                boolean noMore = false;
                while(!noMore){
                    noMore = true;
                    for(int i = 0; i < pending.size(); i++){
                        if(pending.get(i).getEntryId() == index){
                            toDeliver.add(pending.get(i));
                            index++;
                            noMore = false;
                        }
                    }   
                }
                toDeliver.notify();
            } else {
                pending.add(le);
            }
        }
    }
}
