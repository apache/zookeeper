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
package org.apache.bookkeeper.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Enumeration;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.LedgerEntry;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.log4j.Logger;

public class LedgerInputStream extends InputStream {
    Logger LOG = Logger.getLogger(LedgerInputStream.class);
    private LedgerHandle lh;
    private ByteBuffer bytebuff;
    byte[] bbytes;
    long lastEntry = 0;
    int increment = 50;
    int defaultSize = 1024 * 1024; // 1MB default size
    Enumeration<LedgerEntry> ledgerSeq = null;

    /**
     * construct a outputstream from a ledger handle
     * 
     * @param lh
     *            ledger handle
     * @throws {@link BKException}, {@link InterruptedException}
     */
    public LedgerInputStream(LedgerHandle lh) throws BKException, InterruptedException {
        this.lh = lh;
        bbytes = new byte[defaultSize];
        this.bytebuff = ByteBuffer.wrap(bbytes);
        this.bytebuff.position(this.bytebuff.limit());
        lastEntry = Math.min(lh.getLastAddConfirmed(), increment);
        ledgerSeq = lh.readEntries(0, lastEntry);
    }

    /**
     * construct a outputstream from a ledger handle
     * 
     * @param lh
     *            the ledger handle
     * @param size
     *            the size of the buffer
     * @throws {@link BKException}, {@link InterruptedException}
     */
    public LedgerInputStream(LedgerHandle lh, int size) throws BKException, InterruptedException {
        this.lh = lh;
        bbytes = new byte[size];
        this.bytebuff = ByteBuffer.wrap(bbytes);
        this.bytebuff.position(this.bytebuff.limit());
        lastEntry = Math.min(lh.getLastAddConfirmed(), increment);
        ledgerSeq = lh.readEntries(0, lastEntry);
    }

    /**
     * Method close currently doesn't do anything. The application
     * is supposed to open and close the ledger handle backing up 
     * a stream ({@link LedgerHandle}).
     */
    @Override
    public void close() {
        // do nothing
        // let the application
        // close the ledger
    }

    /**
     * refill the buffer, we need to read more bytes
     * 
     * @return if we can refill or not
     */
    private synchronized boolean refill() throws IOException {
        bytebuff.clear();
        if (!ledgerSeq.hasMoreElements() && lastEntry >= lh.getLastAddConfirmed()) {
            return false;
        }
        if (!ledgerSeq.hasMoreElements()) {
            // do refill
            long last = Math.min(lastEntry + increment, lh.getLastAddConfirmed());
            try {
                ledgerSeq = lh.readEntries(lastEntry + 1, last);
            } catch (BKException bk) {
                IOException ie = new IOException(bk.getMessage());
                ie.initCause(bk);
                throw ie;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            lastEntry = last;
        }
        LedgerEntry le = ledgerSeq.nextElement();
        bbytes = le.getEntry();
        bytebuff = ByteBuffer.wrap(bbytes);
        return true;
    }

    @Override
    public synchronized int read() throws IOException {
        boolean toread = true;
        if (bytebuff.remaining() == 0) {
            // their are no remaining bytes
            toread = refill();
        }
        if (toread) {
            int ret = 0xFF & bytebuff.get();
            return ret;
        }
        return -1;
    }

    @Override
    public synchronized int read(byte[] b) throws IOException {
        // be smart ... just copy the bytes
        // once and return the size
        // user will call it again
        boolean toread = true;
        if (bytebuff.remaining() == 0) {
            toread = refill();
        }
        if (toread) {
            int bcopied = bytebuff.remaining();
            int tocopy = Math.min(bcopied, b.length);
            // cannot used gets because of
            // the underflow/overflow exceptions
            System.arraycopy(bbytes, bytebuff.position(), b, 0, tocopy);
            bytebuff.position(bytebuff.position() + tocopy);
            return tocopy;
        }
        return -1;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        // again dont need ot fully
        // fill b, just return
        // what we have and let the application call read
        // again
        boolean toread = true;
        if (bytebuff.remaining() == 0) {
            toread = refill();
        }
        if (toread) {
            int bcopied = bytebuff.remaining();
            int tocopy = Math.min(bcopied, len);
            System.arraycopy(bbytes, bytebuff.position(), b, off, tocopy);
            bytebuff.position(bytebuff.position() + tocopy);
            return tocopy;
        }
        return -1;
    }
}
