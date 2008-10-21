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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.log4j.Logger;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.util.SerializeUtils;

/**
 * This class implements the snapshot interface.
 * it is responsible for storing, serializing
 * and deserializing the right snapshot.
 * and provides access to the snapshots.
 */
public class FileSnap implements SnapShot {
    File snapDir;
    private static final int VERSION=2;
    private static final long dbId=-1;
    private static final Logger LOG = Logger.getLogger(FileSnap.class);
    public final static int SNAP_MAGIC
        = ByteBuffer.wrap("ZKSN".getBytes()).getInt();
    public FileSnap(File snapDir) {
        this.snapDir = snapDir;
    }

    /**
     * deserialize a data tree from the most recent snapshot
     * @return the zxid of the snapshot
     */
    public long deserialize(DataTree dt, Map<Long, Integer> sessions)
            throws IOException {
        File snap = findMostRecentSnapshot();
        if (snap == null) {
            return -1L;
        }
        LOG.info("Reading snapshot " + snap);
        InputStream snapIS = new BufferedInputStream(new FileInputStream(snap));
        CheckedInputStream crcIn = new CheckedInputStream(snapIS, new Adler32());
        InputArchive ia=BinaryInputArchive.getArchive(crcIn);
        deserialize(dt,sessions, ia);
        long checkSum = crcIn.getChecksum().getValue();
        long val = ia.readLong("val");
        if (val != checkSum) {
            throw new IOException("CRC corruption in snapshot :  " + snap);
        }
        snapIS.close();
        crcIn.close();
        dt.lastProcessedZxid = Util.getZxidFromName(snap.getName(), "snapshot");
        return dt.lastProcessedZxid;
    }

    /**
     * deserialize the datatree from an inputarchive
     * @param dt the datatree to be serialized into
     * @param sessions the sessions to be filled up
     * @param ia the input archive to restore from
     * @throws IOException
     */
    protected void deserialize(DataTree dt, Map<Long, Integer> sessions,
            InputArchive ia) throws IOException {
        FileHeader header = new FileHeader();
        header.deserialize(ia, "fileheader");
        SerializeUtils.deserializeSnapshot(dt,ia,sessions);
    }

    /**
     * find the most recent snapshot in the database.
     * @return the file containing the most recent snapshot
     */
    public File findMostRecentSnapshot() throws IOException {
        List<File> files = Util.sortDataDir(snapDir.listFiles(), "snapshot", false);
        for (File f : files) {
            if(Util.isValidSnapshot(f))
                return f;
        }
        return null;
    }

    /**
     * serialize the datatree and sessions
     * @param dt the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param oa the output archive to serialize into
     * @param header the header of this snapshot
     * @throws IOException
     */
    protected void serialize(DataTree dt,Map<Long, Integer> sessions,
            OutputArchive oa, FileHeader header) throws IOException {
        // this is really a programmatic error and not something that can
        // happen at runtime
        if(header==null)
            throw new IllegalStateException(
                    "Snapshot's not open for writing: uninitialized header");
        header.serialize(oa, "fileheader");
        SerializeUtils.serializeSnapshot(dt,oa,sessions);
    }

    /**
     * serialize the datatree and session into the file snapshot
     * @param dt the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param snapShot the file to store snapshot into
     */
    public void serialize(DataTree dt, Map<Long, Integer> sessions, File snapShot)
            throws IOException {
        OutputStream sessOS = new BufferedOutputStream(new FileOutputStream(snapShot));
        CheckedOutputStream crcOut = new CheckedOutputStream(sessOS, new Adler32());
        //CheckedOutputStream cout = new CheckedOutputStream()
        OutputArchive oa = BinaryOutputArchive.getArchive(crcOut);
        FileHeader header = new FileHeader(SNAP_MAGIC, VERSION, dbId);
        serialize(dt,sessions,oa, header);
        long val = crcOut.getChecksum().getValue();
        oa.writeLong(val, "val");
        oa.writeString("/", "path");
        sessOS.flush();
        crcOut.close();
        sessOS.close();
    }

 }