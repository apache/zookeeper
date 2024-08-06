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
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.server.TxnLogEntry;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;

public abstract class FileStreamTxnIterator implements TxnLog.TxnIterator {
    protected final Logger log;

    private long maxZxid = Long.MAX_VALUE;
    private PositionInputStream inputStream = null;
    private InputArchive ia;
    private FileHeader fileHdr;
    private TxnHeader hdr;
    private Record record;
    private TxnDigest digest;
    private int recordSize;

    static final String CRC_ERROR = "CRC check failed";

    public FileStreamTxnIterator(Logger logger) {
        this.log = logger;
    }

    public abstract String getCurrentLogFilePath();

    public abstract InputStream getNextLog() throws IOException;

    public void fastForwardTo(long zxid) throws IOException {
        if (hdr != null) {
            while (hdr.getZxid() < zxid) {
                if (!next()) {
                    break;
                }
            }
        }
    }

    public void setMaxZxid(long zxid) {
        maxZxid = zxid;
    }

    /**
     * go to the next logfile
     * @return true if there is one and false if there is no
     * new file to be read
     * @throws IOException
     */
    protected boolean goToNextLog() throws IOException {
        try {
            InputStream in = getNextLog();
            
            if (in != null) {
                ia = createInputArchive(in);
                return true;
            }
        } catch (EOFException eof) {
        }

        return false;
    }

    /**
     * Invoked to indicate that the input stream has been created.
     * @param logStream the logStream to create an archive for
     * @throws IOException
     **/
    protected InputArchive createInputArchive(InputStream logStream) throws IOException {
        if (inputStream == null){
            inputStream = new PositionInputStream(new BufferedInputStream(logStream));

            if (log.isDebugEnabled()) {
                log.debug("Created new input stream {}", getCurrentLogFilePath());
            }

            ia  = BinaryInputArchive.getArchive(inputStream);
            inStreamCreated();

            if (log.isDebugEnabled()) {
                log.debug("Created new input archive {}", getCurrentLogFilePath());
            }
        }
        return ia;
    }

    /**
     * read the header from the inputarchive
     * @throws IOException
     */
    protected void inStreamCreated() throws IOException{
        fileHdr = new FileHeader();
        fileHdr.deserialize(ia, "fileheader");
        if (fileHdr.getMagic() != FileTxnLog.TXNLOG_MAGIC) {
            throw new IOException("Transaction log: " + getCurrentLogFilePath() + " has invalid magic number "
                    + fileHdr.getMagic()
                    + " != " + FileTxnLog.TXNLOG_MAGIC);
        }
    }

    protected PositionInputStream getInputStream() {
        return inputStream;
    }

    /**
     * Return the file header for the current file
     * @return current file's header
     */
    public FileHeader getFileHeader() {
        return fileHdr;
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

    public int getTxnSize() {
        return recordSize;
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
            if (bytes == null || bytes.length == 0) {
                throw new EOFException("Failed to read " + getCurrentLogFilePath());
            }

            // EOF or corrupted record
            // validate CRC
            Checksum crc = makeChecksumAlgorithm();
            crc.update(bytes, 0, bytes.length);

            if (crcValue != crc.getValue()) {
                throw new IOException(CRC_ERROR);
            }

            if (bytes == null || bytes.length == 0) {
                return false;
            }

            TxnLogEntry logEntry = SerializeUtils.deserializeTxn(bytes);
            hdr = logEntry.getHeader();
            record = logEntry.getTxn();
            digest = logEntry.getDigest();
            recordSize = bytes.length;

            if (hdr.getZxid() > maxZxid) {
                hdr = null;
                record = null;
                return false;
            }

        } catch (EOFException e) {
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
     * close the iterator
     * and release the resources.
     */
    public void close() throws IOException {
        if (inputStream != null) {
            inputStream.close();
        }
    }

    public TxnDigest getDigest() {
        return digest;
    }
}
