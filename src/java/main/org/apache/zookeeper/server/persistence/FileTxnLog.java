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
package org.apache.zookeeper.server.persistence;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements the TxnLog interface. It provides api's
 * to access the txnlogs and add entries to it.
 * <p>
 * The format of a Transactional log is as follows:
 * <blockquote><pre>
 * LogFile:
 *     FileHeader TxnList ZeroPad
 * 
 * FileHeader: {
 *     magic 4bytes (ZKLG)
 *     version 4bytes
 *     dbid 8bytes
 *   }
 * 
 * TxnList:
 *     Txn || Txn TxnList
 *     
 * Txn:
 *     checksum Txnlen TxnHeader Record 0x42
 * 
 * checksum: 8bytes Adler32 is currently used
 *   calculated across payload -- Txnlen, TxnHeader, Record and 0x42
 * 
 * Txnlen:
 *     len 4bytes
 * 
 * TxnHeader: {
 *     sessionid 8bytes
 *     cxid 4bytes
 *     zxid 8bytes
 *     time 8bytes
 *     type 4bytes
 *   }
 *     
 * Record:
 *     See Jute definition file for details on the various record types
 *      
 * ZeroPad:
 *     0 padded to EOF (filled during preallocation stage)
 * </pre></blockquote> 
 */
public class FileTxnLog implements TxnLog {
    private static final Logger LOG;

    static long preAllocSize =  65536 * 1024;

    public final static int TXNLOG_MAGIC =
        ByteBuffer.wrap("ZKLG".getBytes()).getInt();

    public final static int VERSION = 2;

    /** Maximum time we allow for elapsed fsync before WARNing */
    private final static long fsyncWarningThresholdMS;

    static {
        LOG = LoggerFactory.getLogger(FileTxnLog.class);

        String size = System.getProperty("zookeeper.preAllocSize");
        if (size != null) {
            try {
                preAllocSize = Long.parseLong(size) * 1024;
            } catch (NumberFormatException e) {
                LOG.warn(size + " is not a valid value for preAllocSize");
            }
        }
        /** Local variable to read fsync.warningthresholdms into */
        Long fsyncWarningThreshold;
        if ((fsyncWarningThreshold = Long.getLong("zookeeper.fsync.warningthresholdms")) == null)
            fsyncWarningThreshold = Long.getLong("fsync.warningthresholdms", 1000);
        fsyncWarningThresholdMS = fsyncWarningThreshold;
    }

    long lastZxidSeen;
    volatile BufferedOutputStream logStream = null;
    volatile OutputArchive oa;
    volatile FileOutputStream fos = null;

    File logDir;
    private final boolean forceSync = !System.getProperty("zookeeper.forceSync", "yes").equals("no");;
    long dbId;
    private LinkedList<FileOutputStream> streamsToFlush =
        new LinkedList<FileOutputStream>();
    long currentSize;
    File logFileWrite = null;

    /**
     * constructor for FileTxnLog. Take the directory
     * where the txnlogs are stored
     * @param logDir the directory where the txnlogs are stored
     */
    public FileTxnLog(File logDir) {
        this.logDir = logDir;
    }

    /**
     * method to allow setting preallocate size
     * of log file to pad the file.
     * @param size the size to set to in bytes
     */
    public static void setPreallocSize(long size) {
        preAllocSize = size;
    }

    /**
     * creates a checksum alogrithm to be used
     * @return the checksum used for this txnlog
     */
    protected Checksum makeChecksumAlgorithm(){
        return new Adler32();
    }


    /**
     * rollover the current log file to a new one.
     * @throws IOException
     */
    public synchronized void rollLog() throws IOException {
        if (logStream != null) {
            this.logStream.flush();
            this.logStream = null;
            oa = null;
        }
    }

    /**
     * close all the open file handles
     * @throws IOException
     */
    public synchronized void close() throws IOException {
        if (logStream != null) {
            logStream.close();
        }
        for (FileOutputStream log : streamsToFlush) {
            log.close();
        }
    }
    
    /**
     * append an entry to the transaction log
     * @param hdr the header of the transaction
     * @param txn the transaction part of the entry
     * returns true iff something appended, otw false 
     */
    public synchronized boolean append(TxnHeader hdr, Record txn)
        throws IOException
    {
        if (hdr != null) {
            if (hdr.getZxid() <= lastZxidSeen) {
                LOG.warn("Current zxid " + hdr.getZxid()
                        + " is <= " + lastZxidSeen + " for "
                        + hdr.getType());
            }
            if (logStream==null) {
               if(LOG.isInfoEnabled()){
                    LOG.info("Creating new log file: log." +  
                            Long.toHexString(hdr.getZxid()));
               }
               
               logFileWrite = new File(logDir, ("log." + 
                       Long.toHexString(hdr.getZxid())));
               fos = new FileOutputStream(logFileWrite);
               logStream=new BufferedOutputStream(fos);
               oa = BinaryOutputArchive.getArchive(logStream);
               FileHeader fhdr = new FileHeader(TXNLOG_MAGIC,VERSION, dbId);
               fhdr.serialize(oa, "fileheader");
               // Make sure that the magic number is written before padding.
               logStream.flush();
               currentSize = fos.getChannel().position();
               streamsToFlush.add(fos);
            }
            padFile(fos);
            byte[] buf = Util.marshallTxnEntry(hdr, txn);
            if (buf == null || buf.length == 0) {
                throw new IOException("Faulty serialization for header " +
                        "and txn");
            }
            Checksum crc = makeChecksumAlgorithm();
            crc.update(buf, 0, buf.length);
            oa.writeLong(crc.getValue(), "txnEntryCRC");
            Util.writeTxnBytes(oa, buf);
            
            return true;
        }
        return false;
    }

    /**
     * pad the current file to increase its size
     * @param out the outputstream to be padded
     * @throws IOException
     */
    private void padFile(FileOutputStream out) throws IOException {
        currentSize = Util.padLogFile(out, currentSize, preAllocSize);
    }

    /**
     * Find the log file that starts at, or just before, the snapshot. Return
     * this and all subsequent logs. Results are ordered by zxid of file,
     * ascending order.
     * @param logDirList array of files
     * @param snapshotZxid return files at, or before this zxid
     * @return
     */
    public static File[] getLogFiles(File[] logDirList,long snapshotZxid) {
        List<File> files = Util.sortDataDir(logDirList, "log", true);
        long logZxid = 0;
        // Find the log file that starts before or at the same time as the
        // zxid of the snapshot
        for (File f : files) {
            long fzxid = Util.getZxidFromName(f.getName(), "log");
            if (fzxid > snapshotZxid) {
                continue;
            }
            // the files
            // are sorted with zxid's
            if (fzxid > logZxid) {
                logZxid = fzxid;
            }
        }
        List<File> v=new ArrayList<File>(5);
        for (File f : files) {
            long fzxid = Util.getZxidFromName(f.getName(), "log");
            if (fzxid < logZxid) {
                continue;
            }
            v.add(f);
        }
        return v.toArray(new File[0]);

    }

    /**
     * get the last zxid that was logged in the transaction logs
     * @return the last zxid logged in the transaction logs
     */
    public long getLastLoggedZxid() {
        File[] files = getLogFiles(logDir.listFiles(), 0);
        long maxLog=files.length>0?
                Util.getZxidFromName(files[files.length-1].getName(),"log"):-1;

        // if a log file is more recent we must scan it to find
        // the highest zxid
        long zxid = maxLog;
        TxnIterator itr = null;
        try {
            FileTxnLog txn = new FileTxnLog(logDir);
            itr = txn.read(maxLog);
            while (true) {
                if(!itr.next())
                    break;
                TxnHeader hdr = itr.getHeader();
                zxid = hdr.getZxid();
            }
        } catch (IOException e) {
            LOG.warn("Unexpected exception", e);
        } finally {
            close(itr);
        }
        return zxid;
    }

    private void close(TxnIterator itr) {
        if (itr != null) {
            try {
                itr.close();
            } catch (IOException ioe) {
                LOG.warn("Error closing file iterator", ioe);
            }
        }
    }

    /**
     * commit the logs. make sure that evertyhing hits the
     * disk
     */
    public synchronized void commit() throws IOException {
        if (logStream != null) {
            logStream.flush();
        }
        for (FileOutputStream log : streamsToFlush) {
            log.flush();
            if (forceSync) {
                long startSyncNS = System.nanoTime();

                log.getChannel().force(false);

                long syncElapsedMS =
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startSyncNS);
                if (syncElapsedMS > fsyncWarningThresholdMS) {
                    LOG.warn("fsync-ing the write ahead log in "
                            + Thread.currentThread().getName()
                            + " took " + syncElapsedMS
                            + "ms which will adversely effect operation latency. "
                            + "See the ZooKeeper troubleshooting guide");
                }
            }
        }
        while (streamsToFlush.size() > 1) {
            streamsToFlush.removeFirst().close();
        }
    }

    /**
     * start reading all the transactions from the given zxid
     * @param zxid the zxid to start reading transactions from
     * @return returns an iterator to iterate through the transaction
     * logs
     */
    public TxnIterator read(long zxid) throws IOException {
        return new FileTxnIterator(logDir, zxid);
    }

    /**
     * truncate the current transaction logs
     * @param zxid the zxid to truncate the logs to
     * @return true if successful false if not
     */
    public boolean truncate(long zxid) throws IOException {
        FileTxnIterator itr = null;
        try {
            itr = new FileTxnIterator(this.logDir, zxid);
            PositionInputStream input = itr.inputStream;
            if(input == null) {
                throw new IOException("No log files found to truncate! This could " +
                        "happen if you still have snapshots from an old setup or " +
                        "log files were deleted accidentally or dataLogDir was changed in zoo.cfg.");
            }
            long pos = input.getPosition();
            // now, truncate at the current position
            RandomAccessFile raf = new RandomAccessFile(itr.logFile, "rw");
            raf.setLength(pos);
            raf.close();
            while (itr.goToNextLog()) {
                if (!itr.logFile.delete()) {
                    LOG.warn("Unable to truncate {}", itr.logFile);
                }
            }
        } finally {
            close(itr);
        }
        return true;
    }

    /**
     * read the header of the transaction file
     * @param file the transaction file to read
     * @return header that was read fomr the file
     * @throws IOException
     */
    private static FileHeader readHeader(File file) throws IOException {
        InputStream is =null;
        try {
            is = new BufferedInputStream(new FileInputStream(file));
            InputArchive ia=BinaryInputArchive.getArchive(is);
            FileHeader hdr = new FileHeader();
            hdr.deserialize(ia, "fileheader");
            return hdr;
         } finally {
             try {
                 if (is != null) is.close();
             } catch (IOException e) {
                 LOG.warn("Ignoring exception during close", e);
             }
         }
    }

    /**
     * the dbid of this transaction database
     * @return the dbid of this database
     */
    public long getDbId() throws IOException {
        FileTxnIterator itr = new FileTxnIterator(logDir, 0);
        FileHeader fh=readHeader(itr.logFile);
        itr.close();
        if(fh==null)
            throw new IOException("Unsupported Format.");
        return fh.getDbid();
    }

    /**
     * the forceSync value. true if forceSync is enabled, false otherwise.
     * @return the forceSync value
     */
    public boolean isForceSync() {
        return forceSync;
    }

    /**
     * a class that keeps track of the position 
     * in the input stream. The position points to offset
     * that has been consumed by the applications. It can 
     * wrap buffered input streams to provide the right offset 
     * for the application.
     */
    static class PositionInputStream extends FilterInputStream {
        long position;
        protected PositionInputStream(InputStream in) {
            super(in);
            position = 0;
        }
        
        @Override
        public int read() throws IOException {
            int rc = super.read();
            if (rc > -1) {
                position++;
            }
            return rc;
        }

        public int read(byte[] b) throws IOException {
            int rc = super.read(b);
            if (rc > 0) {
                position += rc;
            }
            return rc;            
        }
        
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int rc = super.read(b, off, len);
            if (rc > 0) {
                position += rc;
            }
            return rc;
        }
        
        @Override
        public long skip(long n) throws IOException {
            long rc = super.skip(n);
            if (rc > 0) {
                position += rc;
            }
            return rc;
        }
        public long getPosition() {
            return position;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void mark(int readLimit) {
            throw new UnsupportedOperationException("mark");
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException("reset");
        }
    }
    
    /**
     * this class implements the txnlog iterator interface
     * which is used for reading the transaction logs
     */
    public static class FileTxnIterator implements TxnLog.TxnIterator {
        File logDir;
        long zxid;
        TxnHeader hdr;
        Record record;
        File logFile;
        InputArchive ia;
        static final String CRC_ERROR="CRC check failed";
       
        PositionInputStream inputStream=null;
        //stored files is the list of files greater than
        //the zxid we are looking for.
        private ArrayList<File> storedFiles;

        /**
         * create an iterator over a transaction database directory
         * @param logDir the transaction database directory
         * @param zxid the zxid to start reading from
         * @throws IOException
         */
        public FileTxnIterator(File logDir, long zxid) throws IOException {
          this.logDir = logDir;
          this.zxid = zxid;
          init();
        }

        /**
         * initialize to the zxid specified
         * this is inclusive of the zxid
         * @throws IOException
         */
        void init() throws IOException {
            storedFiles = new ArrayList<File>();
            List<File> files = Util.sortDataDir(FileTxnLog.getLogFiles(logDir.listFiles(), 0), "log", false);
            for (File f: files) {
                if (Util.getZxidFromName(f.getName(), "log") >= zxid) {
                    storedFiles.add(f);
                }
                // add the last logfile that is less than the zxid
                else if (Util.getZxidFromName(f.getName(), "log") < zxid) {
                    storedFiles.add(f);
                    break;
                }
            }
            goToNextLog();
            if (!next())
                return;
            while (hdr.getZxid() < zxid) {
                if (!next())
                    return;
            }
        }

        /**
         * go to the next logfile
         * @return true if there is one and false if there is no
         * new file to be read
         * @throws IOException
         */
        private boolean goToNextLog() throws IOException {
            if (storedFiles.size() > 0) {
                this.logFile = storedFiles.remove(storedFiles.size()-1);
                ia = createInputArchive(this.logFile);
                return true;
            }
            return false;
        }

        /**
         * read the header from the inputarchive
         * @param ia the inputarchive to be read from
         * @param is the inputstream
         * @throws IOException
         */
        protected void inStreamCreated(InputArchive ia, InputStream is)
            throws IOException{
            FileHeader header= new FileHeader();
            header.deserialize(ia, "fileheader");
            if (header.getMagic() != FileTxnLog.TXNLOG_MAGIC) {
                throw new IOException("Transaction log: " + this.logFile + " has invalid magic number " 
                        + header.getMagic()
                        + " != " + FileTxnLog.TXNLOG_MAGIC);
            }
        }

        /**
         * Invoked to indicate that the input stream has been created.
         * @param ia input archive
         * @param is file input stream associated with the input archive.
         * @throws IOException
         **/
        protected InputArchive createInputArchive(File logFile) throws IOException {
            if(inputStream==null){
                inputStream= new PositionInputStream(new BufferedInputStream(new FileInputStream(logFile)));
                LOG.debug("Created new input stream " + logFile);
                ia  = BinaryInputArchive.getArchive(inputStream);
                inStreamCreated(ia,inputStream);
                LOG.debug("Created new input archive " + logFile);
            }
            return ia;
        }

        /**
         * create a checksum algorithm
         * @return the checksum algorithm
         */
        protected Checksum makeChecksumAlgorithm(){
            return new Adler32();
        }

        /**
         * the iterator that moves to the next transaction
         * @return true if there is more transactions to be read
         * false if not.
         */
        public boolean next() throws IOException {
            if (ia == null) {
                return false;
            }
            try {
                long crcValue = ia.readLong("crcvalue");
                byte[] bytes = Util.readTxnBytes(ia);
                // Since we preallocate, we define EOF to be an
                if (bytes == null || bytes.length==0) {
                    throw new EOFException("Failed to read " + logFile);
                }
                // EOF or corrupted record
                // validate CRC
                Checksum crc = makeChecksumAlgorithm();
                crc.update(bytes, 0, bytes.length);
                if (crcValue != crc.getValue())
                    throw new IOException(CRC_ERROR);
                if (bytes == null || bytes.length == 0)
                    return false;
                hdr = new TxnHeader();
                record = SerializeUtils.deserializeTxn(bytes, hdr);
            } catch (EOFException e) {
                LOG.debug("EOF excepton " + e);
                inputStream.close();
                inputStream = null;
                ia = null;
                hdr = null;
                // this means that the file has ended
                // we should go to the next file
                if (!goToNextLog()) {
                    return false;
                }
                // if we went to the next log file, we should call next() again
                return next();
            } catch (IOException e) {
                inputStream.close();
                throw e;
            }
            return true;
        }

        /**
         * reutrn the current header
         * @return the current header that
         * is read
         */
        public TxnHeader getHeader() {
            return hdr;
        }

        /**
         * return the current transaction
         * @return the current transaction
         * that is read
         */
        public Record getTxn() {
            return record;
        }

        /**
         * close the iterator
         * and release the resources.
         */
        public void close() throws IOException {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

}
