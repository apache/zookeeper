package org.apache.bookkeeper.bookie;
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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.apache.log4j.Logger;



/**
 * Implements a ledger inside a bookie. In particular, it implements operations
 * to write entries to a ledger and read entries from a ledger.
 *
 */
public class LedgerDescriptor {
    Logger LOG = Logger.getLogger(LedgerDescriptor.class);
    LedgerDescriptor(long ledgerId, FileChannel ledger, FileChannel ledgerIndex) {
        this.ledgerId = ledgerId;
        this.ledger = ledger;
        this.ledgerIndex = ledgerIndex;
    }
    
    private ByteBuffer masterKey = null;
    
    void setMasterKey(ByteBuffer masterKey){
        this.masterKey = masterKey;
    }
    
    boolean cmpMasterKey(ByteBuffer masterKey){
        return this.masterKey.equals(masterKey);
    }
    
    private long ledgerId;
    private FileChannel ledger;
    private FileChannel ledgerIndex;
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
    static private final long calcEntryOffset(long entryId) {
        return 8L*entryId;
    }
    long addEntry(ByteBuffer entry) throws IOException {
        ByteBuffer offsetBuffer = ByteBuffer.wrap(new byte[8]);
        long ledgerId = entry.getLong();
        if (ledgerId != this.ledgerId) {
            throw new IOException("Entry for ledger " + ledgerId + " was sent to " + this.ledgerId);
        }
        /*
         * Get entry id
         */
                
        long entryId = entry.getLong();
        entry.rewind();
        
        /*
         * Set offset of entry id to be the current ledger position
         */
        offsetBuffer.rewind();
        offsetBuffer.putLong(ledger.position());
        //LOG.debug("Offset: " + ledger.position() + ", " + entry.position() + ", " + calcEntryOffset(entryId) + ", " + entryId);
        offsetBuffer.flip();
        
        /*
         * Write on the index entry corresponding to entryId the position
         * of this entry.
         */
        ledgerIndex.write(offsetBuffer, calcEntryOffset(entryId));
        ByteBuffer lenBuffer = ByteBuffer.allocate(4);
        
        
        lenBuffer.putInt(entry.remaining());
        lenBuffer.flip();
        
        /*
         * Write length of entry first, then the entry itself
         */
        ledger.write(lenBuffer);
        ledger.write(entry);
        //entry.position(24);
        //LOG.debug("Entry: " + entry.position() + ", " + new String(entry.array()));
     
        return entryId;
    }
    ByteBuffer readEntry(long entryId) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[8]);
        long offset;
        /*
         * If entryId is -1, then return the last written.
         */
        if (entryId == -1) {
            offset = ledgerIndex.size()-8; 
        } else {
            offset = calcEntryOffset(entryId);
        }
        int len = ledgerIndex.read(buffer, offset);
        buffer.flip();
        if (len != buffer.limit()) {
            throw new Bookie.NoEntryException(ledgerId, entryId);
        }
        offset = buffer.getLong();
        if (offset == 0) {
            throw new Bookie.NoEntryException(ledgerId, entryId);
        }
        LOG.debug("Offset: " + offset);

        buffer.limit(4);
        buffer.rewind();
        /*
         * Read the length
         */
        ledger.read(buffer, offset);
        buffer.flip();
        len = buffer.getInt();
        LOG.debug("Length of buffer: " + len);
        buffer = ByteBuffer.allocate(len);
        /*
         * Read the rest. We add 4 to skip the length
         */
        ledger.read(buffer, offset + 4);
        buffer.flip();
        return buffer;
    }
    void close() {
        try {
            ledger.close();
        } catch (IOException e) {
            LOG.warn("Error closing ledger " + ledgerId, e);
        }
        try {
            ledgerIndex.close();
        } catch (IOException e) {
            LOG.warn("Error closing index for ledger " + ledgerId, e);
        }
    }
}
