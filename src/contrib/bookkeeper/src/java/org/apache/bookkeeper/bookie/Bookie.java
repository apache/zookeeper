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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.bookkeeper.bookie.BookieException;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;



/**
 * Implements a bookie.
 *
 */

public class Bookie extends Thread {
    HashMap<Long, LedgerDescriptor> ledgers = new HashMap<Long, LedgerDescriptor>();
    static Logger LOG = Logger.getLogger(Bookie.class);
    
    final File journalDirectory;

    final File ledgerDirectories[];
    
    // ZK registration path for this bookie
    static final String BOOKIE_REGISTRATION_PATH = "/ledgers/available/";
    static final String LEDGERS_PATH = "/ledgers";

    // ZooKeeper client instance for the Bookie
    ZooKeeper zk;

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
            super("Entry " + entryId + " not found in " + ledgerId);
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

    EntryLogger entryLogger;
    LedgerCache ledgerCache;
    class SyncThread extends Thread {
        volatile boolean running = true;
        public SyncThread() {
            super("SyncThread");
        }
        @Override
        public void run() {
            while(running) {
                synchronized(this) {
                    try {
                        wait(100);
                        if (!entryLogger.testAndClearSomethingWritten()) {
                            continue;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        continue;
                    }
                }
                lastLogMark.markLog();
                try {
                    ledgerCache.flushLedger(true);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    entryLogger.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                lastLogMark.rollLog();
            }
        }
    }
    SyncThread syncThread = new SyncThread();
    public Bookie(int port, String zkServers, File journalDirectory, File ledgerDirectories[]) throws IOException {
        instantiateZookeeperClient(port, zkServers);
        this.journalDirectory = journalDirectory;
        this.ledgerDirectories = ledgerDirectories;
        entryLogger = new EntryLogger(ledgerDirectories);
        ledgerCache = new LedgerCache(ledgerDirectories);
        lastLogMark.readLog();
        final long markedLogId = lastLogMark.txnLogId;
        if (markedLogId > 0) {
            File logFiles[] = journalDirectory.listFiles();
            ArrayList<Long> logs = new ArrayList<Long>();
            for(File f: logFiles) {
                String name = f.getName();
                if (!name.endsWith(".txn")) {
                    continue;
                }
                String idString = name.split("\\.")[0];
                long id = Long.parseLong(idString, 16);
                if (id < markedLogId) {
                    continue;
                }
                logs.add(id);
            }
            Collections.sort(logs);
            if (logs.size() == 0 || logs.get(0) != markedLogId) {
                throw new IOException("Recovery log " + markedLogId + " is missing");
            }
            // TODO: When reading in the journal logs that need to be synced, we
            // should use BufferedChannels instead to minimize the amount of
            // system calls done.
            ByteBuffer lenBuff = ByteBuffer.allocate(4);
            ByteBuffer recBuff = ByteBuffer.allocate(64*1024);
            for(Long id: logs) {
                FileChannel recLog = openChannel(id);
                while(true) {
                    lenBuff.clear();
                    fullRead(recLog, lenBuff);
                    if (lenBuff.remaining() != 0) {
                        break;
                    }
                    lenBuff.flip();
                    int len = lenBuff.getInt();
                    if (len == 0) {
                        break;
                    }
                    recBuff.clear();
                    if (recBuff.remaining() < len) {
                        recBuff = ByteBuffer.allocate(len);
                    }
                    recBuff.limit(len);
                    if (fullRead(recLog, recBuff) != len) {
                        // This seems scary, but it just means that this is where we
                        // left off writing
                        break;
                    }
                    recBuff.flip();
                    long ledgerId = recBuff.getLong();
                    // XXX we net to make sure we set the master keys appropriately!
                    LedgerDescriptor handle = getHandle(ledgerId, false);
                    try {
                        recBuff.rewind();
                        handle.addEntry(recBuff);
                    } finally {
                        putHandle(handle);
                    }
                }
            }
        }
        setDaemon(true);
        LOG.debug("I'm starting a bookie with journal directory " + journalDirectory.getName());
        start();
        syncThread.start();
    }

    // Method to instantiate the ZooKeeper client for the Bookie.
    private void instantiateZookeeperClient(int port, String zkServers) throws IOException {
        if (zkServers == null) {
            LOG.warn("No ZK servers passed to Bookie constructor so BookKeeper clients won't know about this server!");
            zk = null;
            return;
        }
        // Create the ZooKeeper client instance
        zk = new ZooKeeper(zkServers, 10000, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                // TODO: handle session disconnects and expires
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Process: " + event.getType() + " " + event.getPath());
                }
            }
        });
        // Create the ZK ephemeral node for this Bookie.
        try {
            zk.create(BOOKIE_REGISTRATION_PATH + InetAddress.getLocalHost().getHostAddress() + ":" + port, new byte[0],
                    Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        } catch (Exception e) {
            LOG.fatal("ZK exception registering ephemeral Znode for Bookie!", e);
            // Throw an IOException back up. This will cause the Bookie
            // constructor to error out. Alternatively, we could do a System
            // exit here as this is a fatal error.
            throw new IOException(e);
        }
    }

    private static int fullRead(FileChannel fc, ByteBuffer bb) throws IOException {
        int total = 0;
        while(bb.remaining() > 0) {
            int rc = fc.read(bb);
            if (rc <= 0) {
                return total;
            }
            total += rc;
        }
        return total;
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
                if (readonly) {
                    throw new NoLedgerException(ledgerId);
                }
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
                if (readonly) {
                    throw new NoLedgerException(ledgerId);
                }
                handle = createHandle(ledgerId, readonly);
                ledgers.put(ledgerId, handle);
            } 
            handle.incRef();
        }
        return handle;
    }
    

    private LedgerDescriptor createHandle(long ledgerId, boolean readOnly) throws IOException {
        return new LedgerDescriptor(ledgerId, entryLogger, ledgerCache);
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
    
    class LastLogMark {
        long txnLogId;
        long txnLogPosition;
        LastLogMark lastMark;
        LastLogMark(long logId, long logPosition) {
            this.txnLogId = logId;
            this.txnLogPosition = logPosition;
        }
        synchronized void setLastLogMark(long logId, long logPosition) {
            txnLogId = logId;
            txnLogPosition = logPosition;
        }
        synchronized void markLog() {
            lastMark = new LastLogMark(txnLogId, txnLogPosition);
        }
        synchronized void rollLog() {
            byte buff[] = new byte[16];
            ByteBuffer bb = ByteBuffer.wrap(buff);
            bb.putLong(txnLogId);
            bb.putLong(txnLogPosition);
            for(File dir: ledgerDirectories) {
                File file = new File(dir, "lastMark");
                try {
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(buff);
                    fos.getChannel().force(true);
                    fos.close();
                } catch (IOException e) {
                    LOG.error("Problems writing to " + file, e);
                }
            }
        }
        synchronized void readLog() {
            byte buff[] = new byte[16];
            ByteBuffer bb = ByteBuffer.wrap(buff);
            for(File dir: ledgerDirectories) {
                File file = new File(dir, "lastMark");
                try {
                    FileInputStream fis = new FileInputStream(file);
                    fis.read(buff);
                    fis.close();
                    bb.clear();
                    long i = bb.getLong();
                    long p = bb.getLong();
                    if (i > txnLogId) {
                        txnLogId = i;
                    }
                    if (p > txnLogPosition) {
                        txnLogPosition = p;
                    }
                } catch (IOException e) {
                    LOG.error("Problems reading from " + file + " (this is okay if it is the first time starting this bookie");
                }
            }
        }
    }
    
    private LastLogMark lastLogMark = new LastLogMark(0, 0);
    
    @Override
    public void run() {
        LinkedList<QueueEntry> toFlush = new LinkedList<QueueEntry>();
        ByteBuffer lenBuff = ByteBuffer.allocate(4);
        try {
            long logId = System.currentTimeMillis();
            FileChannel logFile = openChannel(logId);
            BufferedChannel bc = new BufferedChannel(logFile, 65536);
            zeros.clear();
            long nextPrealloc = preAllocSize;
            long lastFlushPosition = 0;
            logFile.write(zeros, nextPrealloc);
            // TODO: Currently, when we roll over the journal logs, the older
            // ones are never garbage collected. We should remove a journal log
            // once all of its entries have been synced with the entry logs.
            while (true) {
                QueueEntry qe = null;
                if (toFlush.isEmpty()) {
                    qe = queue.take();
                } else {
                    qe = queue.poll();
                    if (qe == null || bc.position() > lastFlushPosition + 512*1024) {
                        //logFile.force(false);
                        bc.flush(true);
                        lastFlushPosition = bc.position();
                        lastLogMark.setLastLogMark(logId, lastFlushPosition);
                        for (QueueEntry e : toFlush) {
                            e.cb.writeComplete(0, e.ledgerId, e.entryId, null, e.ctx);
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
                //
                // we should be doing the following, but then we run out of
                // direct byte buffers
                // logFile.write(new ByteBuffer[] { lenBuff, qe.entry });
                bc.write(lenBuff);
                bc.write(qe.entry);
                if (bc.position() > nextPrealloc) {
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

    private FileChannel openChannel(long logId) throws FileNotFoundException {
        FileChannel logFile = new RandomAccessFile(new File(journalDirectory,
                Long.toHexString(logId) + ".txn"),
                "rw").getChannel();
        return logFile;
    }

    public void shutdown() throws InterruptedException {
    	if(zk != null) zk.close();
        this.interrupt();
        this.join();
        syncThread.running = false;
        syncThread.join();
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

        synchronized public void writeComplete(int rc, long l, long e, InetSocketAddress addr, Object ctx) {
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
        Bookie b = new Bookie(5000, null, new File("/tmp"), new File[] { new File("/tmp") });
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
