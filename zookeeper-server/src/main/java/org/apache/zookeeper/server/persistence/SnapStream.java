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

package org.apache.zookeeper.server.persistence;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.common.AtomicFileOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xerial.snappy.SnappyCodec;
import org.xerial.snappy.SnappyInputStream;
import org.xerial.snappy.SnappyOutputStream;

/**
 * Represent the Stream used in serialize and deserialize the Snapshot.
 */
public class SnapStream {

    private static final Logger LOG = LoggerFactory.getLogger(SnapStream.class);

    public static final String ZOOKEEPER_SHAPSHOT_STREAM_MODE = "zookeeper.snapshot.compression.method";

    private static StreamMode streamMode = StreamMode.fromString(
        System.getProperty(ZOOKEEPER_SHAPSHOT_STREAM_MODE,
                           StreamMode.DEFAULT_MODE.getName()));

    static {
        LOG.info("{} = {}", ZOOKEEPER_SHAPSHOT_STREAM_MODE, streamMode);
    }

    public enum StreamMode {
        GZIP("gz"),
        SNAPPY("snappy"),
        CHECKED("");

        public static final StreamMode DEFAULT_MODE = CHECKED;

        private String name;

        StreamMode(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public String getFileExtension() {
            return name.isEmpty() ? "" : "." + name;
        }

        public static StreamMode fromString(String name) {
            for (StreamMode c : values()) {
                if (c.getName().compareToIgnoreCase(name) == 0) {
                    return c;
                }
            }
            return DEFAULT_MODE;
        }
    }

    /**
     * Return the CheckedInputStream based on the extension of the fileName.
     *
     * @param file the file the InputStream read from
     * @return the specific InputStream
     * @throws IOException
     */
    public static CheckedInputStream getInputStream(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        InputStream is;
        switch (getStreamMode(file.getName())) {
        case GZIP:
            is = new GZIPInputStream(fis);
            break;
        case SNAPPY:
            is = new SnappyInputStream(fis);
            break;
        case CHECKED:
        default:
            is = new BufferedInputStream(fis);
        }
        return new CheckedInputStream(is, new Adler32());
    }

    /**
     * Return the OutputStream based on predefined stream mode.
     *
     * @param file the file the OutputStream writes to
     * @param fsync sync the file immediately after write
     * @return the specific OutputStream
     * @throws IOException
     */
    public static CheckedOutputStream getOutputStream(File file, boolean fsync) throws IOException {
        OutputStream fos = fsync ? new AtomicFileOutputStream(file) : new FileOutputStream(file);
        OutputStream os;
        switch (streamMode) {
        case GZIP:
            os = new GZIPOutputStream(fos);
            break;
        case SNAPPY:
            os = new SnappyOutputStream(fos);
            break;
        case CHECKED:
        default:
            os = new BufferedOutputStream(fos);
        }
        return new CheckedOutputStream(os, new Adler32());
    }

    /**
     * Write specific seal to the OutputArchive and close the OutputStream.
     * Currently, only CheckedOutputStream will write it's checkSum to the
     * end of the stream.
     *
     */
    public static void sealStream(CheckedOutputStream os, OutputArchive oa) throws IOException {
        long val = os.getChecksum().getValue();
        oa.writeLong(val, "val");
        oa.writeString("/", "path");
    }

    /**
     * Verify the integrity of the seal, only CheckedInputStream will verify
     * the checkSum of the content.
     *
     */
    static void checkSealIntegrity(CheckedInputStream is, InputArchive ia) throws IOException {
        long checkSum = is.getChecksum().getValue();
        long val = ia.readLong("val");
        ia.readString("path");  // Read and ignore "/" written by SealStream.
        if (val != checkSum) {
            throw new IOException("CRC corruption");
        }
    }

    /**
     * Verifies that the file is a valid snapshot. Snapshot may be invalid if
     * it's incomplete as in a situation when the server dies while in the
     * process of storing a snapshot. Any files that are improperly formated
     * or corrupted are invalid. Any file that is not a snapshot is also an
     * invalid snapshot.
     *
     * @param file file to verify
     * @return true if the snapshot is valid
     * @throws IOException
     */
    public static boolean isValidSnapshot(File file) throws IOException {
        if (file == null || Util.getZxidFromName(file.getName(), FileSnap.SNAPSHOT_FILE_PREFIX) == -1) {
            return false;
        }

        boolean isValid = false;
        switch (getStreamMode(file.getName())) {
        case GZIP:
            isValid = isValidGZipStream(file);
            break;
        case SNAPPY:
            isValid = isValidSnappyStream(file);
            break;
        case CHECKED:
        default:
            isValid = isValidCheckedStream(file);
        }
        return isValid;
    }

    public static void setStreamMode(StreamMode mode) {
        streamMode = mode;
    }

    public static StreamMode getStreamMode() {
        return streamMode;
    }

    /**
     * Detect the stream mode from file name extension
     *
     * @param fileName
     * @return the stream mode detected
     */
    public static StreamMode getStreamMode(String fileName) {
        String[] splitSnapName = fileName.split("\\.");

        // Use file extension to detect format
        if (splitSnapName.length > 1) {
            String mode = splitSnapName[splitSnapName.length - 1];
            return StreamMode.fromString(mode);
        }

        return StreamMode.CHECKED;
    }

    /**
     * Certify the GZip stream integrity by checking the header
     * for the GZip magic string
     *
     * @param f file to verify
     * @return true if it has the correct GZip magic string
     * @throws IOException
     */
    private static boolean isValidGZipStream(File f) throws IOException {
        byte[] byteArray = new byte[2];
        try (FileInputStream fis = new FileInputStream(f)) {
            if (2 != fis.read(byteArray, 0, 2)) {
                LOG.error("Read incorrect number of bytes from {}", f.getName());
                return false;
            }
            ByteBuffer bb = ByteBuffer.wrap(byteArray);
            byte[] magicHeader = new byte[2];
            bb.get(magicHeader, 0, 2);
            int magic = magicHeader[0] & 0xff | ((magicHeader[1] << 8) & 0xff00);
            return magic == GZIPInputStream.GZIP_MAGIC;
        } catch (FileNotFoundException e) {
            LOG.error("Unable to open file {}", f.getName(), e);
            return false;
        }
    }

    /**
     * Certify the Snappy stream integrity by checking the header
     * for the Snappy magic string
     *
     * @param f file to verify
     * @return true if it has the correct Snappy magic string
     * @throws IOException
     */
    private static boolean isValidSnappyStream(File f) throws IOException {
        byte[] byteArray = new byte[SnappyCodec.MAGIC_LEN];
        try (FileInputStream fis = new FileInputStream(f)) {
            if (SnappyCodec.MAGIC_LEN != fis.read(byteArray, 0, SnappyCodec.MAGIC_LEN)) {
                LOG.error("Read incorrect number of bytes from {}", f.getName());
                return false;
            }
            ByteBuffer bb = ByteBuffer.wrap(byteArray);
            byte[] magicHeader = new byte[SnappyCodec.MAGIC_LEN];
            bb.get(magicHeader, 0, SnappyCodec.MAGIC_LEN);
            return Arrays.equals(magicHeader, SnappyCodec.getMagicHeader());
        } catch (FileNotFoundException e) {
            LOG.error("Unable to open file {}", f.getName(), e);
            return false;
        }
    }

    /**
     * Certify the Checked stream integrity by checking the header
     * length and format
     *
     * @param f file to verify
     * @return true if it has the correct header
     * @throws IOException
     */
    private static boolean isValidCheckedStream(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            // including the header and the last / bytes
            // the snapshot should be at least 10 bytes
            if (raf.length() < 10) {
                return false;
            }

            raf.seek(raf.length() - 5);
            byte[] bytes = new byte[5];
            int readlen = 0;
            int l;
            while (readlen < 5 && (l = raf.read(bytes, readlen, bytes.length - readlen)) >= 0) {
                readlen += l;
            }
            if (readlen != bytes.length) {
                LOG.info("Invalid snapshot {}. too short, len = {} bytes", f.getName(), readlen);
                return false;
            }
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            int len = bb.getInt();
            byte b = bb.get();
            if (len != 1 || b != '/') {
                LOG.info("Invalid snapshot {}. len = {}, byte = {}", f.getName(), len, (b & 0xff));
                return false;
            }
        }

        return true;
    }

}
