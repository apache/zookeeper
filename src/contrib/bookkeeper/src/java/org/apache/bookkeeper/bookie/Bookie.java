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


import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.bookkeeper.bookie.BookieException;
import org.apache.bookkeeper.proto.WriteCallback;
import org.apache.log4j.Logger;



/**
 * Implements a bookie.
 *
 */

public class Bookie extends Thread {
    HashMap<Long, LedgerDescriptor> ledgers = new HashMap<Long, LedgerDescriptor>();
    static Logger LOG = Logger.getLogger(Bookie.class);
    /**
     * 4 byte signature followed by 2-byte major and 2-byte minor versions
     */
    private static byte ledgerHeader[] =  { 0x42, 0x6f, 0x6f, 0x6b, 0, 0, 0, 0};
    
    final File journalDirectory;

    final File ledgerDirectories[];
    
    public static class NoLedgerException extends IOException {
        private static final long serialVersionUID = 1L;
        private long ledgerId;
        public NoLedgerException(long ledgerId) {
            this.ledgerId = ledgerId;
        }
        public long getLedgerId() {
            return ledgerId;
        }
    }
    public static class NoEntryException extends IOException {
        private static final long serialVersionUID = 1L;
        private long ledgerId;
        private long entryId;
        public NoEntryException(long ledgerId, long entryId) {
            this.ledgerId = ledgerId;
            this.entryId = entryId;
        }
        public long getLedger() {
            return ledgerId;
        }
        public long getEntry() {
            return entryId;
        }
    }

    public Bookie(File journalDirectory, File ledgerDirectories[]) {
        this.journalDirectory = journalDirectory;
        this.ledgerDirectories = ledgerDirectories;
        setDaemon(true);
        LOG.debug("I'm starting a bookie with journal directory " + journalDirectory.getName());
        start();
    }

    private void putHandle(LedgerDescriptor handle) {
        synchronized (ledgers) {
            handle.decRef();
        }
    }

    private LedgerDescriptor getHandle(long ledgerId, boolean readonly, byte[] masterKey) throws IOException {
        LedgerDescriptor handle = null;
        synchronized (ledgers) {
            handle = ledgers.get(ledgerId);
            if (handle == null) {
                handle = createHandle(ledgerId, readonly);
                ledgers.put(ledgerId, handle);
                handle.setMasterKey(ByteBuffer.wrap(masterKey));
            } 
            handle.incRef();
        }
        return handle;
    }
    
    private LedgerDescriptor getHandle(long ledgerId, boolean readonly) throws IOException {
        LedgerDescriptor handle = null;
        synchronized (ledgers) {
            handle = ledgers.get(ledgerId);
            if (handle == null) {
                handle = createHandle(ledgerId, readonly);
                ledgers.put(ledgerId, handle);
            } 
            handle.incRef();
        }
        return handle;
    }
    

    private LedgerDescriptor createHandle(long ledgerId, boolean readOnly) throws IOException {
        RandomAccessFile ledgerFile = null;
        RandomAccessFile ledgerIndexFile = null;
        String ledgerName = getLedgerName(ledgerId, false);
        String ledgerIndexName = getLedgerName(ledgerId, true);
        for (File d : ledgerDirectories) {
            File lf = new File(d, ledgerName);
            File lif = new File(d, ledgerIndexName);
            if (lf.exists()) {
                if (ledgerFile != null) {
                    throw new IOException("Duplicate ledger file found for "
                            + ledgerId);
                }
                ledgerFile = new RandomAccessFile(lf, "rw");
            }
            if (lif.exists()) {
                if (ledgerIndexFile != null) {
                    throw new IOException(
                            "Duplicate ledger index file found for " + ledgerId);
                }
                ledgerIndexFile = new RandomAccessFile(lif, "rw");
            }
        }
        if (ledgerFile == null && ledgerIndexFile == null) {
            if (readOnly) {
                throw new NoLedgerException(ledgerId);
            }
            File dirs[] = pickDirs(ledgerDirectories);
            File lf = new File(dirs[0], ledgerName);
            checkParents(lf);
            ledgerFile = new RandomAccessFile(lf, "rw");
            ledgerFile.write(ledgerHeader);
            File lif = new File(dirs[1], ledgerIndexName);
            checkParents(lif);
            ledgerIndexFile = new RandomAccessFile(lif, "rw");
        }
        if (ledgerFile != null && ledgerIndexFile != null) {
            return new LedgerDescriptor(ledgerId, ledgerFile.getChannel(),
                    ledgerIndexFile.getChannel());
        }
        if (ledgerFile == null) {
            throw new IOException("Found index but no data for " + ledgerId);
        }
        throw new IOException("Found data but no index for " + ledgerId);
    }
    
    static final private void checkParents(File f) throws IOException {
        File parent = f.getParentFile();
        if (parent.exists()) {
            return;
        }
        if (parent.mkdirs() == false) {
            throw new IOException("Counldn't mkdirs for " + parent);
        }
    }

    static final private Random rand = new Random();

    static final private File[] pickDirs(File dirs[]) {
        File rc[] = new File[2];
        rc[0] = dirs[rand.nextInt(dirs.length)];
        rc[1] = dirs[rand.nextInt(dirs.length)];
        return rc;
    }

    static final private String getLedgerName(long ledgerId, boolean isIndex) {
        int parent = (int) (ledgerId & 0xff);
        int grandParent = (int) ((ledgerId & 0xff00) >> 8);
        StringBuilder sb = new StringBuilder();
        sb.append(Integer.toHexString(grandParent));
        sb.append('/');
        sb.append(Integer.toHexString(parent));
        sb.append('/');
        sb.append(Long.toHexString(ledgerId));
        if (isIndex) {
            sb.append(".idx");
        }
        return sb.toString();
    }

    static class QueueEntry {
        QueueEntry(ByteBuffer entry, long ledgerId, long entryId, 
                WriteCallback cb, Object ctx) {
            this.entry = entry.duplicate();
            this.cb = cb;
            this.ctx = ctx;
            this.ledgerId = ledgerId;
            this.entryId = entryId;
        }

        ByteBuffer entry;
        
        long ledgerId;
        
        long entryId;

        WriteCallback cb;

        Object ctx;
    }

    LinkedBlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<QueueEntry>();

    public final static long preAllocSize = 4*1024*1024;
    
    public final static ByteBuffer zeros = ByteBuffer.allocate(512);
    
    public void run() {
        LinkedList<QueueEntry> toFlush = new LinkedList<QueueEntry>();
        ByteBuffer lenBuff = ByteBuffer.allocate(4);
        try {
            FileChannel logFile = new RandomAccessFile(new File(journalDirectory,
                    Long.toHexString(System.currentTimeMillis()) + ".txn"),
                    "rw").getChannel();
            zeros.clear();
            long nextPrealloc = preAllocSize;
            logFile.write(zeros, nextPrealloc);
            while (true) {
                QueueEntry qe = null;
                if (toFlush.isEmpty()) {
                    qe = queue.take();
                } else {
                    qe = queue.poll();
                    if (qe == null || toFlush.size() > 100) {
                        logFile.force(false);
                        for (QueueEntry e : toFlush) {
                            e.cb.writeComplete(0, e.ledgerId, e.entryId, e.ctx);
                        }
                        toFlush.clear();
                    }
                }
                if (qe == null) {
                    continue;
                }
                lenBuff.clear();
                lenBuff.putInt(qe.entry.remaining());
                lenBuff.flip();
                logFile.write(new ByteBuffer[] { lenBuff, qe.entry });
                if (logFile.position() > nextPrealloc) {
                    nextPrealloc = (logFile.size() / preAllocSize + 1) * preAllocSize;
                    zeros.clear();
                    logFile.write(zeros, nextPrealloc);
                }
                toFlush.add(qe);
            }
        } catch (Exception e) {
            LOG.fatal("Bookie thread exiting", e);
        }
    }

    public void shutdown() throws InterruptedException {
        this.interrupt();
        this.join();
        for(LedgerDescriptor d: ledgers.values()) {
            d.close();
        }
    }
    
    public void addEntry(ByteBuffer entry, WriteCallback cb, Object ctx, byte[] masterKey)
            throws IOException, BookieException {
        
        long ledgerId = entry.getLong();
        LedgerDescriptor handle = getHandle(ledgerId, false, masterKey);
        
        if(!handle.cmpMasterKey(ByteBuffer.wrap(masterKey))){
            throw BookieException.create(BookieException.Code.UnauthorizedAccessException);
        }
        try {
            entry.rewind();
            long entryId = handle.addEntry(entry);
            entry.rewind();
            if (LOG.isTraceEnabled()) {
                LOG.trace("Adding " + entryId + "@" + ledgerId);
            }
            queue.add(new QueueEntry(entry, ledgerId, entryId, cb, ctx));
        } finally {
            putHandle(handle);
        }
    }

    public ByteBuffer readEntry(long ledgerId, long entryId) throws IOException {
        LedgerDescriptor handle = getHandle(ledgerId, true);
        try {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Reading " + entryId + "@" + ledgerId);
            }
            return handle.readEntry(entryId);
        } finally {
            putHandle(handle);
        }
    }

    // The rest of the code is test stuff
    static class CounterCallback implements WriteCallback {
        int count;

        synchronized public void writeComplete(int rc, long l, long e, Object ctx) {
            count--;
            if (count == 0) {
                notifyAll();
            }
        }

        synchronized public void incCount() {
            count++;
        }

        synchronized public void waitZero() throws InterruptedException {
            while (count > 0) {
                wait();
            }
        }
    }

    /**
     * @param args
     * @throws IOException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws IOException,
            InterruptedException, BookieException {
        Bookie b = new Bookie(new File("/tmp"), new File[] { new File("/tmp") });
        CounterCallback cb = new CounterCallback();
        long start = System.currentTimeMillis();
        for (int i = 0; i < 100000; i++) {
            ByteBuffer buff = ByteBuffer.allocate(1024);
            buff.putLong(1);
            buff.putLong(i);
            buff.limit(1024);
            buff.position(0);
            cb.incCount();
            b.addEntry(buff, cb, null, new byte[0]);
        }
        cb.waitZero();
        long end = System.currentTimeMillis();
        System.out.println("Took " + (end-start) + "ms");
    }
}
