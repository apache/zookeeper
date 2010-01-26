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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

/**
 * This class manages the writing of the bookkeeper entries. All the new
 * entries are written to a common log. The LedgerCache will have pointers
 * into files created by this class with offsets into the files to find
 * the actual ledger entry. The entry log files created by this class are
 * identified by a long.
 */
public class EntryLogger {
    private static final Logger LOG = Logger.getLogger(EntryLogger.class);
    private File dirs[];
    private long logId;
    /**
     * The maximum size of a entry logger file.
     */
    final static long LOG_SIZE_LIMIT = 2*1024*1024*1024L;
    private volatile BufferedChannel logChannel;
    // The ledgers contained in this file, seems to be unsused right now
    //private HashSet<Long> ledgerMembers = new HashSet<Long>();
    /**
     * The 1K block at the head of the entry logger file
     * that contains the fingerprint and (future) meta-data
     */
    final static ByteBuffer LOGFILE_HEADER = ByteBuffer.allocate(1024);
    static {
        LOGFILE_HEADER.put("BKLO".getBytes());
    }
    // this indicates that a write has happened since the last flush
    private volatile boolean somethingWritten = false;
    
    /**
     * Create an EntryLogger that stores it's log files in the given
     * directories
     */
    public EntryLogger(File dirs[]) throws IOException {
        this.dirs = dirs;
        // Find the largest logId
        for(File f: dirs) {
            long lastLogId = getLastLogId(f);
            if (lastLogId >= logId) {
                logId = lastLogId+1;
            }
        }
        createLogId(logId);
        //syncThread = new SyncThread();
        //syncThread.start();
    }
    
    /**
     * Maps entry log files to open channels.
     */
    private ConcurrentHashMap<Long, BufferedChannel> channels = new ConcurrentHashMap<Long, BufferedChannel>();
    
    /**
     * Creates a new log file with the given id.
     */
    private void createLogId(long logId) throws IOException {
        List<File> list = Arrays.asList(dirs);
        Collections.shuffle(list);
        File firstDir = list.get(0);
        if (logChannel != null) {
            logChannel.flush(true);
        }
        logChannel = new BufferedChannel(new RandomAccessFile(new File(firstDir, Long.toHexString(logId)+".log"), "rw").getChannel(), 64*1024);
        logChannel.write((ByteBuffer) LOGFILE_HEADER.clear());
        channels.put(logId, logChannel);
        for(File f: dirs) {
            setLastLogId(f, logId);
        }
    }

    /**
     * writes the given id to the "lastId" file in the given directory.
     */
    private void setLastLogId(File dir, long logId) throws IOException {
        FileOutputStream fos;
        fos = new FileOutputStream(new File(dir, "lastId"));
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos));
        try {
            bw.write(Long.toHexString(logId) + "\n");
            bw.flush();
        } finally {
            try {
                fos.close();
            } catch (IOException e) {
            }
        }
    }
    
    /**
     * reads id from the "lastId" file in the given directory.
     */
    private long getLastLogId(File f) {
        FileInputStream fis;
        try {
            fis = new FileInputStream(new File(f, "lastId"));
        } catch (FileNotFoundException e) {
            return -1;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(fis));
        try {
            String lastIdString = br.readLine();
            return Long.parseLong(lastIdString);
        } catch (IOException e) {
            return -1;
        } catch(NumberFormatException e) {
            return -1;
        } finally {
            try {
                fis.close();
            } catch (IOException e) {
            }
        }
    }
    
    private void openNewChannel() throws IOException {
        createLogId(++logId);
    }
    
    synchronized void flush() throws IOException {
        if (logChannel != null) {
            logChannel.flush(true);
        }
    }
    synchronized long addEntry(long ledger, ByteBuffer entry) throws IOException {
        if (logChannel.position() + entry.remaining() + 4 > LOG_SIZE_LIMIT) {
            openNewChannel();
        }
        ByteBuffer buff = ByteBuffer.allocate(4);
        buff.putInt(entry.remaining());
        buff.flip();
        logChannel.write(buff);
        long pos = logChannel.position();
        logChannel.write(entry);
        //logChannel.flush(false);
        somethingWritten = true;
        return (logId << 32L) | pos;
    }
    
    byte[] readEntry(long ledgerId, long entryId, long location) throws IOException {
        long entryLogId = location >> 32L;
        long pos = location & 0xffffffffL;
        ByteBuffer sizeBuff = ByteBuffer.allocate(4);
        pos -= 4; // we want to get the ledgerId and length to check
        BufferedChannel fc;
        try {
            fc = getChannelForLogId(entryLogId);
        } catch (FileNotFoundException e) {
            FileNotFoundException newe = new FileNotFoundException(e.getMessage() + " for " + ledgerId + " with location " + location);
            newe.setStackTrace(e.getStackTrace());
            throw newe;
        }
        if (fc.read(sizeBuff, pos) != sizeBuff.capacity()) {
            throw new IOException("Short read from entrylog " + entryLogId);
        }
        pos += 4;
        sizeBuff.flip();
        int entrySize = sizeBuff.getInt();
        // entrySize does not include the ledgerId
        if (entrySize > 1024*1024) {
            LOG.error("Sanity check failed for entry size of " + entrySize + " at location " + pos + " in " + entryLogId);
            
        }
        byte data[] = new byte[entrySize];
        ByteBuffer buff = ByteBuffer.wrap(data);
        int rc = fc.read(buff, pos);
        if ( rc != data.length) {
            throw new IOException("Short read for " + ledgerId + "@" + entryId + " in " + entryLogId + "@" + pos + "("+rc+"!="+data.length+")");
        }
        buff.flip();
        long thisLedgerId = buff.getLong();
        if (thisLedgerId != ledgerId) {
            throw new IOException("problem found in " + entryLogId + "@" + entryId + " at position + " + pos + " entry belongs to " + thisLedgerId + " not " + ledgerId);
        }
        long thisEntryId = buff.getLong();
        if (thisEntryId != entryId) {
            throw new IOException("problem found in " + entryLogId + "@" + entryId + " at position + " + pos + " entry is " + thisEntryId + " not " + entryId);
        }
        
        return data;
    }
    
    private BufferedChannel getChannelForLogId(long entryLogId) throws IOException {
        BufferedChannel fc = channels.get(entryLogId);
        if (fc != null) {
            return fc;
        }
        File file = findFile(entryLogId);
        FileChannel newFc = new RandomAccessFile(file, "rw").getChannel();
        synchronized (channels) {
            fc = channels.get(entryLogId);
            if (fc != null){
                newFc.close();
                return fc;
            }
            fc = new BufferedChannel(newFc, 8192);
            channels.put(entryLogId, fc);
            return fc;
        }
    }

    private File findFile(long logId) throws FileNotFoundException {
        for(File d: dirs) {
            File f = new File(d, Long.toHexString(logId)+".log");
            if (f.exists()) {
                return f;
            }
        }
        throw new FileNotFoundException("No file for log " + Long.toHexString(logId));
    }
    
    public void close() {
    }

    synchronized public boolean testAndClearSomethingWritten() {
        try {
            return somethingWritten;
        } finally {
            somethingWritten = false;
        }
    }

}
