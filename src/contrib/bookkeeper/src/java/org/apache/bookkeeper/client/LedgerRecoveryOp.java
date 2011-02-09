package org.apache.bookkeeper.client;

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.Enumeration;

import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.AsyncCallback.ReadCallback;
import org.apache.bookkeeper.client.BKException.BKDigestMatchException;
import org.apache.bookkeeper.client.LedgerHandle.NoopCloseCallback;
import org.apache.bookkeeper.client.DigestManager.RecoveryData;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.ReadEntryCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffer;

/**
 * This class encapsulated the ledger recovery operation. It first does a read
 * with entry-id of -1 to all bookies. Then starting from the last confirmed
 * entry (from hints in the ledger entries), it reads forward until it is not
 * able to find a particular entry. It closes the ledger at that entry.
 * 
 */
class LedgerRecoveryOp implements ReadEntryCallback, ReadCallback, AddCallback {
    static final Logger LOG = Logger.getLogger(LedgerRecoveryOp.class);
    LedgerHandle lh;
    int numResponsesPending;
    boolean proceedingWithRecovery = false;
    long maxAddPushed = -1;
    long maxAddConfirmed = -1;
    long maxLength = 0;

    GenericCallback<Void> cb;

    public LedgerRecoveryOp(LedgerHandle lh, GenericCallback<Void> cb) {
        this.cb = cb;
        this.lh = lh;
        numResponsesPending = lh.metadata.ensembleSize;
    }

    public void initiate() {
        for (int i = 0; i < lh.metadata.currentEnsemble.size(); i++) {
            lh.bk.bookieClient.readEntry(lh.metadata.currentEnsemble.get(i), lh.ledgerId, -1, this, i);
        }
    }

    public synchronized void readEntryComplete(final int rc, final long ledgerId, final long entryId,
            final ChannelBuffer buffer, final Object ctx) {

        // Already proceeding with recovery, nothing to do
        if (proceedingWithRecovery) {
            return;
        }

        int bookieIndex = (Integer) ctx;

        numResponsesPending--;

        boolean heardValidResponse = false;

        if (rc == BKException.Code.OK) {
            try {
                RecoveryData recoveryData = lh.macManager.verifyDigestAndReturnLastConfirmed(buffer);
                maxAddConfirmed = Math.max(maxAddConfirmed, recoveryData.lastAddConfirmed);
                maxAddPushed = Math.max(maxAddPushed, recoveryData.entryId);
                heardValidResponse = true;
            } catch (BKDigestMatchException e) {
                // Too bad, this bookie didnt give us a valid answer, we
                // still might be able to recover though so continue
                LOG.error("Mac mismatch while reading last entry from bookie: "
                        + lh.metadata.currentEnsemble.get(bookieIndex));
            }
        }

        if (rc == BKException.Code.NoSuchLedgerExistsException || rc == BKException.Code.NoSuchEntryException) {
            // this still counts as a valid response, e.g., if the
            // client
            // crashed without writing any entry
            heardValidResponse = true;
        }

        // other return codes dont count as valid responses
        if (heardValidResponse && lh.distributionSchedule.canProceedWithRecovery(bookieIndex)) {
            proceedingWithRecovery = true;
            lh.lastAddPushed = lh.lastAddConfirmed = maxAddConfirmed;
            lh.length = maxLength;
            doRecoveryRead();
            return;
        }

        if (numResponsesPending == 0) {
            // Have got all responses back but was still not enough to
            // start
            // recovery, just fail the operation
            LOG.error("While recovering ledger: " + ledgerId + " did not hear success responses from all quorums");
            cb.operationComplete(BKException.Code.LedgerRecoveryException, null);
        }

    }

    /**
     * Try to read past the last confirmed.
     */
    private void doRecoveryRead() {
        lh.lastAddConfirmed++;
        lh.asyncReadEntries(lh.lastAddConfirmed, lh.lastAddConfirmed, this, null);
    }

    @Override
    public void readComplete(int rc, LedgerHandle lh, Enumeration<LedgerEntry> seq, Object ctx) {
        // get back to prev value
        lh.lastAddConfirmed--;
        if (rc == BKException.Code.OK) {
            LedgerEntry entry = seq.nextElement(); 
            byte[] data = entry.getEntry();
            
            /*
             * We will add this entry again to make sure it is written to enough
             * replicas. We subtract the length of the data itself, since it will
             * be added again when processing the call to add it.
             */
            lh.length = entry.getLength() - (long) data.length;
            lh.asyncAddEntry(data, this, null);
            
            return;
        }

        if (rc == BKException.Code.NoSuchEntryException || rc == BKException.Code.NoSuchLedgerExistsException) {
            lh.asyncClose(NoopCloseCallback.instance, null);
            // we don't need to wait for the close to complete. Since we mark
            // the
            // ledger closed in memory, the application wont be able to add to
            // it

            cb.operationComplete(BKException.Code.OK, null);
            LOG.debug("After closing length is: " + lh.getLength());
            return;
        }

        // otherwise, some other error, we can't handle
        LOG.error("Failure " + BKException.getMessage(rc) + " while reading entry: " + lh.lastAddConfirmed + 1
                + " ledger: " + lh.ledgerId + " while recovering ledger");
        cb.operationComplete(rc, null);
        return;
    }

    @Override
    public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
        if (rc != BKException.Code.OK) {
            // Give up, we can't recover from this error

            LOG.error("Failure " + BKException.getMessage(rc) + " while writing entry: " + lh.lastAddConfirmed + 1
                    + " ledger: " + lh.ledgerId + " while recovering ledger");
            cb.operationComplete(rc, null);
            return;
        }

        doRecoveryRead();

    }

}
