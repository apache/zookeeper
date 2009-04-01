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



import org.apache.log4j.Logger;

/**
 * Ledger entry. Currently only holds the necessary
 * fields to identify a ledger entry, and the entry
 * content.
 * 
 */

public class LedgerEntry {
    Logger LOG = Logger.getLogger(LedgerEntry.class);
    
    private long lId;
    private long eId;
    private byte[] entry;
    
    LedgerEntry(long lId, long eId, byte[] entry){
        this.lId = lId;
        this.eId = eId;
        this.entry = entry;
    }
    
    public long getLedgerId(){
        return lId;
    }
    
    public long getEntryId(){
        return eId;
    }
    
    public byte[] getEntry(){
        return entry;
    }
}
