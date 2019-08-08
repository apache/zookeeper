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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.common.AtomicFileOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.util.SerializeUtils;

/**
 * This class implements the snapshot interface.
 * it is responsible for storing, serializing
 * and deserializing the right snapshot.
 * and provides access to the snapshots.
 */
public class FileSnap implements SnapShot {
    //snap文件目录
    File snapDir;
    //是否关闭
    private volatile boolean close = false;
    private static final int VERSION = 2;
    private static final long dbId = -1;
    private static final Logger LOG = LoggerFactory.getLogger(FileSnap.class);
    //文件的魔数
    public final static int SNAP_MAGIC
            = ByteBuffer.wrap("ZKSN".getBytes()).getInt();

    public static final String SNAPSHOT_FILE_PREFIX = "snapshot";

    public FileSnap(File snapDir) {
        this.snapDir = snapDir;
    }

    /**
     * deserialize a data tree from the most recent snapshot
     * @return the zxid of the snapshot
     */
    public long deserialize(DataTree dt, Map<Long, Integer> sessions)
            throws IOException {
        // we run through 100 snapshots (not all of them)
        // if we cannot get it running within 100 snapshots
        // we should  give up
        //找到最新的100个(大概率)valid的快照文件
        List<File> snapList = findNValidSnapshots(100);
        if (snapList.size() == 0) {
            return -1L;
        }
        File snap = null;
        boolean foundValid = false;
        //将这些快照文件按新旧排序，直到第一个合法的就break
        for (int i = 0, snapListSize = snapList.size(); i < snapListSize; i++) {
            snap = snapList.get(i);
            LOG.info("Reading snapshot " + snap);
            try (CheckedInputStream snapIS = SnapStream.getInputStream(snap)) {
                InputArchive ia = BinaryInputArchive.getArchive(snapIS);
                //根据ia反序列化到dataTree以及sessions
                deserialize(dt, sessions, ia);
                //反序列填充session和dataTree之后，计算checkSum
                SnapStream.checkSealIntegrity(snapIS, ia);
                foundValid = true;
                break;
            } catch (IOException e) {
                LOG.warn("problem reading snap file " + snap, e);
            }
        }
        if (!foundValid) {
            throw new IOException("在snapDir目录里无法找到有效的快照 " + snapDir);
        }
        //从最近的第一个valid的snap文件中，解析出zxid
        dt.lastProcessedZxid = Util.getZxidFromName(snap.getName(), SNAPSHOT_FILE_PREFIX);
        return dt.lastProcessedZxid;
    }

    /**
     * deserialize the datatree from an inputarchive
     * @param dt the datatree to be serialized into
     * @param sessions the sessions to be filled up
     * @param ia the input archive to restore from
     * @throws IOException
     */
    public void deserialize(DataTree dt, Map<Long, Integer> sessions,
            InputArchive ia) throws IOException {
        FileHeader header = new FileHeader();
        // 反序列化至header
        header.deserialize(ia, "fileheader");
        if (header.getMagic() != SNAP_MAGIC) {
            throw new IOException("mismatching magic headers "
                    + header.getMagic() +
                    " !=  " + FileSnap.SNAP_MAGIC);
        }
        // 反序列化至dataTree和sessions
        SerializeUtils.deserializeSnapshot(dt,ia,sessions);
    }

    /**
     * find the most recent snapshot in the database.
     * @return the file containing the most recent snapshot
     */
    // 找到最近的snapshot文件
    public File findMostRecentSnapshot() throws IOException {
        List<File> files = findNValidSnapshots(1);
        if (files.size() == 0) {
            return null;
        }
        return files.get(0);
    }

    /**
     * find the last (maybe) valid n snapshots. this does some
     * minor checks on the validity of the snapshots. It just
     * checks for / at the end of the snapshot. This does
     * not mean that the snapshot is truly valid but is
     * valid with a high probability. also, the most recent
     * will be first on the list.
     * @param n the number of most recent snapshots
     * @return the last n snapshots (the number might be
     * less than n in case enough snapshots are not available).
     * @throws IOException
     */
    //找到最近n个(大概)合理的快照文件，按从新到旧排序
    private List<File> findNValidSnapshots(int n) throws IOException {
        List<File> files = Util.sortDataDir(snapDir.listFiles(), SNAPSHOT_FILE_PREFIX, false);
        int count = 0;
        List<File> list = new ArrayList<File>();
        for (File f : files) {
            // we should catch the exceptions
            // from the valid snapshot and continue
            // until we find a valid one
            try {
                //一个minor check,来看这个文件是否大概率valid
                if (SnapStream.isValidSnapshot(f)) {
                    list.add(f);
                    count++;
                    if (count == n) {
                        break;
                    }
                }
            } catch (IOException e) {
                LOG.info("invalid snapshot " + f, e);
            }
        }
        return list;
    }

    /**
     * find the last n snapshots. this does not have
     * any checks if the snapshot might be valid or not
     * 找到最后n个快照。如果快照可能有效，则没有*任何检查
     * @param n the number of most recent snapshots
     * @return the last n snapshots
     * @throws IOException
     */
    public List<File> findNRecentSnapshots(int n) throws IOException {
        List<File> files = Util.sortDataDir(snapDir.listFiles(), SNAPSHOT_FILE_PREFIX, false);
        int count = 0;
        List<File> list = new ArrayList<File>();
        for (File f: files) {
            if (count == n)
                break;
            if (Util.getZxidFromName(f.getName(), SNAPSHOT_FILE_PREFIX) != -1) {
                count++;
                list.add(f);
            }
        }
        return list;
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
        //header序列化
        header.serialize(oa, "fileheader");
        //将dataTree和sessions序列化到oa
        SerializeUtils.serializeSnapshot(dt,oa,sessions);
    }

    /**
     * serialize the datatree and session into the file snapshot
     * @param dt the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param snapShot the file to store snapshot into
     * @param fsync sync the file immediately after write
     */
    public synchronized void serialize(DataTree dt, Map<Long, Integer> sessions, File snapShot, boolean fsync)
            throws IOException {
        if (!close) {
            try (CheckedOutputStream snapOS = SnapStream.getOutputStream(snapShot)) {
                OutputArchive oa = BinaryOutputArchive.getArchive(snapOS);
                FileHeader header = new FileHeader(SNAP_MAGIC, VERSION, dbId);
                //将dt,session,header进行序列化
                serialize(dt, sessions, oa, header);
                SnapStream.sealStream(snapOS, oa);
            }
        }
    }

    /**
     * synchronized close just so that if serialize is in place
     * the close operation will block and will wait till serialize
     * is done and will set the close flag
     */
    // 关闭资源
    @Override
    public synchronized void close() throws IOException {
        close = true;
    }

}
