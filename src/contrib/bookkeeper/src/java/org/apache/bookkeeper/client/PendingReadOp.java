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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.NoSuchElementException;
import java.util.Queue;
import org.apache.bookkeeper.client.AsyncCallback.ReadCallback;
import org.apache.bookkeeper.client.BKException.BKDigestMatchException;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.ReadEntryCallback;
import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;

import java.io.IOException;

/**
 * Sequence of entries of a ledger that represents a pending read operation.
 * When all the data read has come back, the application callback is called.
 * This class could be improved because we could start pushing data to the
 * application as soon as it arrives rather than waiting for the whole thing.
 * 
 */

class PendingReadOp implements Enumeration<LedgerEntry>, ReadEntryCallback {
    Logger LOG = Logger.getLogger(PendingReadOp.class);

    Queue<LedgerEntry> seq;
    ReadCallback cb;
    Object ctx;
    LedgerHandle lh;
    long numPendingReads;
    long startEntryId;
    long endEntryId;

    PendingReadOp(LedgerHandle lh, long startEntryId, long endEntryId, ReadCallback cb, Object ctx) {

        seq = new ArrayDeque<LedgerEntry>((int) (endEntryId - startEntryId));
        this.cb = cb;
        this.ctx = ctx;
        this.lh = lh;
        this.startEntryId = startEntryId;
        this.endEntryId = endEntryId;
        numPendingReads = endEntryId - startEntryId + 1;
    }

    public void initiate() throws InterruptedException {
        long nextEnsembleChange = startEntryId, i = startEntryId;

        ArrayList<InetSocketAddress> ensemble = null;
        do {

            if(LOG.isDebugEnabled()){
                LOG.debug("Acquiring lock: " + i);
            }
           
            lh.opCounterSem.acquire();
            
            if (i == nextEnsembleChange) {
                ensemble = lh.metadata.getEnsemble(i);
                nextEnsembleChange = lh.metadata.getNextEnsembleChange(i);
            }
            LedgerEntry entry = new LedgerEntry(lh.ledgerId, i);
            seq.add(entry);
            i++;
            sendRead(ensemble, entry, BKException.Code.ReadException);

        } while (i <= endEntryId);
    }

    void sendRead(ArrayList<InetSocketAddress> ensemble, LedgerEntry entry, int lastErrorCode) {
        if (entry.nextReplicaIndexToReadFrom >= lh.metadata.quorumSize) {
            // we are done, the read has failed from all replicas, just fail the
            // read
            submitCallback(lastErrorCode);
            return;
        }

        int bookieIndex = lh.distributionSchedule.getBookieIndex(entry.entryId, entry.nextReplicaIndexToReadFrom);
        entry.nextReplicaIndexToReadFrom++;
        lh.bk.bookieClient.readEntry(ensemble.get(bookieIndex), lh.ledgerId, entry.entryId, this, entry);
    }

    void logErrorAndReattemptRead(LedgerEntry entry, String errMsg, int rc) {
        ArrayList<InetSocketAddress> ensemble = lh.metadata.getEnsemble(entry.entryId);
        int bookeIndex = lh.distributionSchedule.getBookieIndex(entry.entryId, entry.nextReplicaIndexToReadFrom - 1);
        LOG.error(errMsg + " while reading entry: " + entry.entryId + " ledgerId: " + lh.ledgerId + " from bookie: "
                + ensemble.get(bookeIndex));
        sendRead(ensemble, entry, rc);
        return;
    }

    @Override
    public void readEntryComplete(int rc, long ledgerId, final long entryId, final ChannelBuffer buffer, Object ctx) {
        final LedgerEntry entry = (LedgerEntry) ctx;

        if (rc != BKException.Code.OK) {
            logErrorAndReattemptRead(entry, "Error: " + BKException.getMessage(rc), rc);
            return;
        }
        
        ChannelBufferInputStream is;
        try {
            is = lh.macManager.verifyDigestAndReturnData(entryId, buffer);
        } catch (BKDigestMatchException e) {
            logErrorAndReattemptRead(entry, "Mac mismatch", BKException.Code.DigestMatchException);
            return;
        }
        
        entry.entryDataStream = is;
        
        /*
         * The length is a long and it is the last field of the metadata of an entry.
         * Consequently, we have to subtract 8 from METADATA_LENGTH to get the length.
         */
        entry.length = buffer.getLong(DigestManager.METADATA_LENGTH - 8);

        numPendingReads--;
        if (numPendingReads == 0) {
            submitCallback(BKException.Code.OK);
        }
        
        if(LOG.isDebugEnabled()){
            LOG.debug("Releasing lock: " + entryId);
        }
        
        lh.opCounterSem.release();
        
        if(numPendingReads < 0)
            LOG.error("Read too many values");
    }

    private void submitCallback(int code){
        cb.readComplete(code, lh, PendingReadOp.this, PendingReadOp.this.ctx);
    }
    public boolean hasMoreElements() {
        return !seq.isEmpty();
    }

    public LedgerEntry nextElement() throws NoSuchElementException {
        return seq.remove();
    }

    public int size() {
        return seq.size();
    }
}
