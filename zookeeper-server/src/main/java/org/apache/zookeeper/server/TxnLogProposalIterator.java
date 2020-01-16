/*
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

package org.apache.zookeeper.server;

import java.io.IOException;
import java.util.Iterator;
import org.apache.zookeeper.server.persistence.TxnLog.TxnIterator;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.server.quorum.QuorumPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides an iterator interface to access Proposal deserialized
 * from on-disk txnlog. The iterator deserializes one proposal at a time
 * to reduce memory footprint. Note that the request part of the proposal
 * is not initialized and set to null since we don't need it during
 * follower sync-up.
 *
 */
public class TxnLogProposalIterator implements Iterator<Proposal> {

    private static final Logger LOG = LoggerFactory.getLogger(TxnLogProposalIterator.class);

    public static final TxnLogProposalIterator EMPTY_ITERATOR = new TxnLogProposalIterator();

    private boolean hasNext = false;

    private TxnIterator itr;

    @Override
    public boolean hasNext() {
        return hasNext;
    }

    /**
     * Proposal returned by this iterator has request part set to null, since
     * it is not used for follower sync-up.
     */
    @Override
    public Proposal next() {

        Proposal p = new Proposal();
        try {
            byte[] serializedData = Util.marshallTxnEntry(itr.getHeader(), itr.getTxn(), itr.getDigest());

            QuorumPacket pp = new QuorumPacket(Leader.PROPOSAL, itr.getHeader().getZxid(), serializedData, null);
            p.packet = pp;
            p.request = null;

            // This is the only place that can throw IO exception
            hasNext = itr.next();

        } catch (IOException e) {
            LOG.error("Unable to read txnlog from disk", e);
            hasNext = false;
        }

        return p;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    /**
     * Close the files and release the resources which are used for iterating
     * transaction records
     */
    public void close() {
        if (itr != null) {
            try {
                itr.close();
            } catch (IOException ioe) {
                LOG.warn("Error closing file iterator", ioe);
            }
        }
    }

    private TxnLogProposalIterator() {
    }

    public TxnLogProposalIterator(TxnIterator itr) {
        if (itr != null) {
            this.itr = itr;
            hasNext = (itr.getHeader() != null);
        }
    }

}
