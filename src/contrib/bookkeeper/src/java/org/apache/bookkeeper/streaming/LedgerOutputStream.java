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
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.log4j.Logger;

/**
 * this class provides a streaming api to get an output stream from a ledger
 * handle and write to it as a stream of bytes. This is built on top of
 * ledgerhandle api and uses a buffer to cache the data written to it and writes
 * out the entry to the ledger.
 */
public class LedgerOutputStream extends OutputStream {
    Logger LOG = Logger.getLogger(LedgerOutputStream.class);
    private LedgerHandle lh;
    private ByteBuffer bytebuff;
    byte[] bbytes;
    int defaultSize = 1024 * 1024; // 1MB default size

    /**
     * construct a outputstream from a ledger handle
     * 
     * @param lh
     *            ledger handle
     */
    public LedgerOutputStream(LedgerHandle lh) {
        this.lh = lh;
        bbytes = new byte[defaultSize];
        this.bytebuff = ByteBuffer.wrap(bbytes);
    }

    /**
     * construct a outputstream from a ledger handle
     * 
     * @param lh
     *            the ledger handle
     * @param size
     *            the size of the buffer
     */
    public LedgerOutputStream(LedgerHandle lh, int size) {
        this.lh = lh;
        bbytes = new byte[size];
        this.bytebuff = ByteBuffer.wrap(bbytes);
    }

    @Override
    public void close() {
        // flush everything
        // we have
        flush();
    }

    @Override
    public synchronized void flush() {
        // lets flush all the data
        // into the ledger entry
        if (bytebuff.position() > 0) {
            // copy the bytes into
            // a new byte buffer and send it out
            byte[] b = new byte[bytebuff.position()];
            LOG.info("Comment: flushing with params " + " " + bytebuff.position());
            System.arraycopy(bbytes, 0, b, 0, bytebuff.position());
            try {
                lh.addEntry(b);
            } catch (InterruptedException ie) {
                LOG.warn("Interrupted while flusing " + ie);
                Thread.currentThread().interrupt();
            } catch (BKException bke) {
                LOG.warn("BookKeeper exception ", bke);
            }
        }
    }

    /**
     * make space for len bytes to be written to the buffer.
     * 
     * @param len
     * @return if true then we can make space for len if false we cannot
     */
    private boolean makeSpace(int len) {
        if (bytebuff.remaining() < len) {
            flush();
            bytebuff.clear();
            if (bytebuff.capacity() < len) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void write(byte[] b) {
        if (makeSpace(b.length)) {
            bytebuff.put(b);
        } else {
            try {
                lh.addEntry(b);
            } catch (InterruptedException ie) {
                LOG.warn("Interrupted while writing", ie);
                Thread.currentThread().interrupt();
            } catch (BKException bke) {
                LOG.warn("BookKeeper exception", bke);
            }
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        if (!makeSpace(len)) {
            // lets try making the buffer bigger
            bbytes = new byte[len];
            bytebuff = ByteBuffer.wrap(bbytes);
        }
        bytebuff.put(b, off, len);
    }

    @Override
    public synchronized void write(int b) throws IOException {
        makeSpace(1);
        byte oneB = (byte) (b & 0xFF);
        bytebuff.put(oneB);
    }
}