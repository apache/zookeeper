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

package org.apache.bookkeeper.bookie;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.log4j.Logger;



/**
 * Implements a ledger inside a bookie. In particular, it implements operations
 * to write entries to a ledger and read entries from a ledger.
 *
 */
public class LedgerDescriptor {
    final static Logger LOG = Logger.getLogger(LedgerDescriptor.class);
    LedgerCache ledgerCache;
    LedgerDescriptor(long ledgerId, EntryLogger entryLogger, LedgerCache ledgerCache) {
        this.ledgerId = ledgerId;
        this.entryLogger = entryLogger;
        this.ledgerCache = ledgerCache;
    }
    
    private ByteBuffer masterKey = null;
    
    void setMasterKey(ByteBuffer masterKey){
        this.masterKey = masterKey;
    }
    
    boolean cmpMasterKey(ByteBuffer masterKey){
        return this.masterKey.equals(masterKey);
    }
    
    private long ledgerId;
    EntryLogger entryLogger;
    private int refCnt;
    synchronized public void incRef() {
        refCnt++;
    }
    synchronized public void decRef() {
        refCnt--;
    }
    synchronized public int getRefCnt() {
        return refCnt;
    }
    long addEntry(ByteBuffer entry) throws IOException {
        long ledgerId = entry.getLong();
        if (ledgerId != this.ledgerId) {
            throw new IOException("Entry for ledger " + ledgerId + " was sent to " + this.ledgerId);
        }
        long entryId = entry.getLong();
        entry.rewind();
        
        /*
         * Log the entry
         */
        long pos = entryLogger.addEntry(ledgerId, entry);
        
        
        /*
         * Set offset of entry id to be the current ledger position
         */
        ledgerCache.putEntryOffset(ledgerId, entryId, pos);
        return entryId;
    }
    ByteBuffer readEntry(long entryId) throws IOException {
        long offset;
        /*
         * If entryId is -1, then return the last written.
         */
        if (entryId == -1) {
            long lastEntry = ledgerCache.getLastEntry(ledgerId);
            FileInfo fi = null;
            try {
                fi = ledgerCache.getFileInfo(ledgerId, false);
                long size = fi.size();
                // we may not have the last entry in the cache
                if (size > lastEntry*8) {
                    ByteBuffer bb = ByteBuffer.allocate(LedgerEntryPage.PAGE_SIZE);
                    long position = size-LedgerEntryPage.PAGE_SIZE;
                    if (position < 0) {
                        position = 0;
                    }
                    fi.read(bb, position);
                    bb.flip();
                    long startingEntryId = position/8;
                    for(int i = LedgerEntryPage.ENTRIES_PER_PAGES-1; i >= 0; i--) {
                        if (bb.getLong(i*8) != 0) {
                            if (lastEntry < startingEntryId+i) {
                                lastEntry = startingEntryId+i;
                            }
                            break;
                        }
                    }
                }
            } finally {
                if (fi != null) {
                    fi.release();
                }
            }
            entryId = lastEntry;
        }
        
        offset = ledgerCache.getEntryOffset(ledgerId, entryId);
        if (offset == 0) {
            throw new Bookie.NoEntryException(ledgerId, entryId);
        }
        return ByteBuffer.wrap(entryLogger.readEntry(ledgerId, entryId, offset));
    }
    void close() {
    }
}
