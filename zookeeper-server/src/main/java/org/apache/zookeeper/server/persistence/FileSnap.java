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

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import org.apache.commons.io.FilenameUtils;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Index;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.DataTree.ZxidDigest;
import org.apache.zookeeper.server.ReferenceCountedACLCache;
import org.apache.zookeeper.server.TransactionChangeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements the snapshot interface.
 * it is responsible for storing, serializing
 * and deserializing the right snapshot.
 * and provides access to the snapshots.
 */
public class FileSnap implements SnapShot {

    File snapDir;
    SnapshotInfo lastSnapshotInfo = null;
    OutputArchive oa;
    InputArchive ia;
    private volatile boolean close = false;
    private static final int VERSION = 2;
    private static final long dbId = -1;
    private static final Logger LOG = LoggerFactory.getLogger(FileSnap.class);
    public static final int SNAP_MAGIC = ByteBuffer.wrap("ZKSN".getBytes()).getInt();

    public static final String SNAPSHOT_FILE_PREFIX = "snapshot";

    public FileSnap(File snapDir) {
        this.snapDir = snapDir;
    }

    public FileSnap(File snapDir, OutputArchive oa) {
        this.snapDir = snapDir;
        this.oa = oa;
    }

    public FileSnap(File snapDir, InputArchive ia) {
        this.snapDir = snapDir;
        this.ia = ia;
    }

    /**
     * get information of the last saved/restored snapshot
     * @return info of last snapshot
     */
    public SnapshotInfo getLastSnapshotInfo() {
        return this.lastSnapshotInfo;
    }

    /**
     * deserialize a data tree from the most recent snapshot
     * @return the zxid of the snapshot
     */
    public long deserialize(DataTree dt, Map<Long, Integer> sessions) throws IOException {
        // we run through 100 snapshots (not all of them)
        // if we cannot get it running within 100 snapshots
        // we should  give up
        List<File> snapList = findNValidSnapshots(100);
        if (snapList.size() == 0) {
            return -1L;
        }
        File snap = null;
        long snapZxid = -1;
        boolean foundValid = false;
        for (int i = 0, snapListSize = snapList.size(); i < snapListSize; i++) {
            snap = snapList.get(i);
            LOG.info("Reading snapshot {}", snap);
            snapZxid = Util.getZxidFromName(snap.getName(), SNAPSHOT_FILE_PREFIX);
            try (CheckedInputStream snapIS = SnapStream.getInputStream(snap)) {
                ia = BinaryInputArchive.getArchive(snapIS);
                deserialize(dt, sessions, ia);
                SnapStream.checkSealIntegrity(snapIS, ia);

                // Digest feature was added after the CRC to make it backward
                // compatible, the older code can still read snapshots which
                // includes digest.
                //
                // To check the intact, after adding digest we added another
                // CRC check.
                if (deserializeZxidDigest(dt)) {
                    SnapStream.checkSealIntegrity(snapIS, ia);
                }

                foundValid = true;
                break;
            } catch (IOException e) {
                LOG.warn("problem reading snap file {}", snap, e);
            }
        }
        if (!foundValid) {
            throw new IOException("Not able to find valid snapshots in " + snapDir);
        }
        dt.lastProcessedZxid = snapZxid;
        lastSnapshotInfo = new SnapshotInfo(dt.lastProcessedZxid, snap.lastModified() / 1000);

        // compare the digest if this is not a fuzzy snapshot, we want to compare
        // and find inconsistent asap.
        if (dt.getDigestFromLoadedSnapshot() != null) {
            dt.compareSnapshotDigests(dt.lastProcessedZxid);
        }
        return dt.lastProcessedZxid;
    }

    /**
     * deserialize the datatree from an inputarchive
     * @param dt the datatree to be serialized into
     * @param sessions the sessions to be filled up
     * @param ia the input archive to restore from
     * @throws IOException
     */
    public void deserialize(DataTree dt, Map<Long, Integer> sessions, InputArchive ia) throws IOException {
        this.ia = ia;
        FileHeader header = new FileHeader();
        header.deserialize(ia, "fileheader");
        if (header.getMagic() != SNAP_MAGIC) {
            throw new IOException("mismatching magic headers " + header.getMagic() + " !=  " + FileSnap.SNAP_MAGIC);
        }
        deserializeSessions(sessions);
        deserializeACL(dt.getReferenceCountedAclCache());
        dt.deserialize(this, "tree");
    }

    public void serialize(DataTree dt, Map<Long, Integer> sessions, long lastZxid, boolean fsync)
            throws IOException {
        File snapshotFile = new File(snapDir, FilenameUtils.getName(Util.makeSnapshotName(lastZxid)));
        try {
            serialize(dt, sessions, snapshotFile, fsync);
        } catch (IOException e) {
            if (snapshotFile.length() == 0) {
                /* This may be caused by a full disk. In such a case, the server
                 * will get stuck in a loop where it tries to write a snapshot
                 * out to disk, and ends up creating an empty file instead.
                 * Doing so will eventually result in valid snapshots being
                 * removed during cleanup. */
                if (snapshotFile.delete()) {
                    LOG.info("Deleted empty snapshot file: "
                            + snapshotFile.getAbsolutePath());
                } else {
                    LOG.warn("Could not delete empty snapshot file: "
                            + snapshotFile.getAbsolutePath());
                }
            } else {
                /* Something else went wrong when writing the snapshot out to
                 * disk. If this snapshot file is invalid, when restarting,
                 * ZooKeeper will skip it, and find the last known good snapshot
                 * instead. */
            }
            throw e;
        }
    }

    /**
     * find the most recent snapshot in the database.
     * @return the file containing the most recent snapshot
     */
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
    protected List<File> findNValidSnapshots(int n) throws IOException {
        List<File> files = Util.sortDataDir(snapDir.listFiles(), SNAPSHOT_FILE_PREFIX, false);
        int count = 0;
        List<File> list = new ArrayList<File>();
        for (File f : files) {
            // we should catch the exceptions
            // from the valid snapshot and continue
            // until we find a valid one
            try {
                if (SnapStream.isValidSnapshot(f)) {
                    list.add(f);
                    count++;
                    if (count == n) {
                        break;
                    }
                }
            } catch (IOException e) {
                LOG.warn("invalid snapshot {}", f, e);
            }
        }
        return list;
    }

    /**
     * find the last n snapshots. this does not have
     * any checks if the snapshot might be valid or not
     * @param n the number of most recent snapshots
     * @return the last n snapshots
     * @throws IOException
     */
    public List<File> findNRecentSnapshots(int n) throws IOException {
        List<File> files = Util.sortDataDir(snapDir.listFiles(), SNAPSHOT_FILE_PREFIX, false);
        int count = 0;
        List<File> list = new ArrayList<File>();
        for (File f : files) {
            if (count == n) {
                break;
            }
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
     * @param header the header of this snapshot
     * @throws IOException
     */
    protected void serialize(
        DataTree dt,
        Map<Long, Integer> sessions,
        FileHeader header) throws IOException {
        // this is really a programmatic error and not something that can
        // happen at runtime
        if (header == null) {
            throw new IllegalStateException("Snapshot's not open for writing: uninitialized header");
        }
        header.serialize(oa, "fileheader");
        serializeSessions(sessions);
        serializeACL(dt.getReferenceCountedAclCache());
        dt.serialize(this, "tree");
    }

    /**
     * serialize the datatree and session into the file snapshot
     * @param dt the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param snapShot the file to store snapshot into
     * @param fsync sync the file immediately after write
     */
    public synchronized void serialize(
        DataTree dt,
        Map<Long, Integer> sessions,
        File snapShot,
        boolean fsync) throws IOException {
        if (!close) {
            long lastZxid = Util.getZxidFromName(snapShot.getName(), SNAPSHOT_FILE_PREFIX);
            LOG.info("Snapshotting: 0x{} to {}", Long.toHexString(lastZxid), snapShot);
            try (CheckedOutputStream snapOS = SnapStream.getOutputStream(snapShot, fsync)) {
                oa = BinaryOutputArchive.getArchive(snapOS);
                FileHeader header = new FileHeader(SNAP_MAGIC, VERSION, dbId);
                serialize(dt, sessions, header);
                SnapStream.sealStream(snapOS, oa);

                // Digest feature was added after the CRC to make it backward
                // compatible, the older code cal still read snapshots which
                // includes digest.
                //
                // To check the intact, after adding digest we added another
                // CRC check.
                if (serializeZxidDigest(dt)) {
                    SnapStream.sealStream(snapOS, oa);
                }

                lastSnapshotInfo = new SnapshotInfo(
                    Util.getZxidFromName(snapShot.getName(), SNAPSHOT_FILE_PREFIX),
                    snapShot.lastModified() / 1000);
            }
        } else {
            throw new IOException("FileSnap has already been closed");
        }
    }

    private void writeChecksum(CheckedOutputStream crcOut, OutputArchive oa) throws IOException {
        long val = crcOut.getChecksum().getValue();
        oa.writeLong(val, "val");
        oa.writeString("/", "path");
    }

    private void checkChecksum(CheckedInputStream crcIn, InputArchive ia) throws IOException {
        long checkSum = crcIn.getChecksum().getValue();
        long val = ia.readLong("val");
        // read and ignore "/" written by writeChecksum
        ia.readString("path");
        if (val != checkSum) {
            throw new IOException("CRC corruption");
        }
    }

    public synchronized void serializeSessions(Map<Long, Integer> sessions) throws IOException {
        HashMap<Long, Integer> sessSnap = new HashMap<Long, Integer>(sessions);
        oa.writeInt(sessSnap.size(), "count");
        for (Entry<Long, Integer> entry : sessSnap.entrySet()) {
            oa.writeLong(entry.getKey().longValue(), "id");
            oa.writeInt(entry.getValue().intValue(), "timeout");
        }
    }

    public synchronized void deserializeSessions(Map<Long, Integer> sessions) throws IOException {
        int count = ia.readInt("count");
        while (count > 0) {
            long id = ia.readLong("id");
            int to = ia.readInt("timeout");
            sessions.put(id, to);
            count--;
        }
    }

    public synchronized void serializeACL(ReferenceCountedACLCache aclCache) throws IOException {
        oa.writeInt(aclCache.getLongKeyMap().size(), "map");
        Set<Map.Entry<Long, List<ACL>>> set = aclCache.getLongKeyMap().entrySet();
        for (Map.Entry<Long, List<ACL>> val : set) {
            oa.writeLong(val.getKey(), "long");
            List<ACL> aclList = val.getValue();
            oa.startVector(aclList, "acls");
            for (ACL acl : aclList) {
                acl.serialize(oa, "acl");
            }
            oa.endVector(aclList, "acls");
        }
    }

    public synchronized void deserializeACL(ReferenceCountedACLCache aclCache) throws IOException {
        aclCache.clear();
        int aclCount = ia.readInt("aclCount");
        while (aclCount > 0) {
            Long val = ia.readLong("long");
            List<ACL> aclList = new ArrayList<ACL>();
            Index j = ia.startVector("acls");
            if (j == null) {
                throw new RuntimeException("Incorrect format of InputArchive when deserialize DataTree - missing acls");
            }
            while (!j.done()) {
                ACL acl = new ACL();
                acl.deserialize(ia, "acl");
                aclList.add(acl);
                j.incr();
            }
            aclCache.updateMaps(val, aclList);
            aclCount--;
        }
    }

    public synchronized void writeNode(String pathString, DataNode node) throws IOException {
        oa.writeString(pathString, "path");
        oa.writeRecord(node, "node");
    }

    public synchronized void markEnd() throws IOException {
        oa.writeString("/", "path");
    }

    public String readNode(DataNode node) throws IOException {
        String path = ia.readString("path");
        if (!"/".equals(path)) {
            ia.readRecord(node, "node");
        }
        return path;
    }

    public synchronized boolean serializeZxidDigest(DataTree dt) throws IOException {
        if (dt.nodesDigestEnabled()) {
            ZxidDigest zxidDigest = dt.getLastProcessedZxidDigest();
            if (zxidDigest == null) {
                zxidDigest = dt.getBlankDigest();
            }
            zxidDigest.serialize(oa);
            return true;
        }
        return false;
    }

    public synchronized boolean deserializeZxidDigest(DataTree dt) throws IOException {
        if (dt.nodesDigestEnabled()) {
            try {
                ZxidDigest zxidDigest = dt.getBlankDigest();
                zxidDigest.deserialize(ia);
                if (zxidDigest.getZxid() > 0) {
                    dt.setZxidDigestFromLoadedSnapshot(zxidDigest);
                    LOG.info("The digest in the snapshot is {}, 0x{}, {}",
                            zxidDigest.getDigestVersion(),
                            Long.toHexString(zxidDigest.getZxid()),
                            zxidDigest.getDigest());
                } else {
                    dt.setZxidDigestFromLoadedSnapshot(null);
                    LOG.info("The digest value is empty in snapshot");
                }

                // There is possibility that the start zxid of a snapshot might
                // be larger than the digest zxid in snapshot.
                //
                // Known cases:
                //
                // The new leader set the last processed zxid to be the new
                // epoch + 0, which is not mapping to any txn, and it uses
                // this to take snapshot, which is possible if we don't
                // clean database before switching to LOOKING. In this case
                // the currentZxidDigest will be the zxid of last epoch and
                // it's smaller than the zxid of the snapshot file.
                //
                // It's safe to reset the targetZxidDigest to null and start
                // to compare digest when replaying the first txn, since it's
                // a non fuzzy snapshot.
                if (zxidDigest != null && zxidDigest.getZxid() < dt.lastProcessedZxid) {
                    LOG.info("The zxid of snapshot digest 0x{} is smaller "
                                    + "than the known snapshot highest zxid, the snapshot "
                                    + "started with zxid 0x{}. It will be invalid to use "
                                    + "this snapshot digest associated with this zxid, will "
                                    + "ignore comparing it.", Long.toHexString(zxidDigest.getZxid()),
                            Long.toHexString(dt.lastProcessedZxid));
                    dt.setZxidDigestFromLoadedSnapshot(null);
                }

                return true;
            } catch (EOFException e) {
                LOG.warn("Got EOF exception while reading the digest, likely due to the reading an older snapshot.");
                return false;
            }
        }
        return false;
    }

    public void applyTxn(List<TransactionChangeRecord> changeList, long zxid) throws IOException {
        // do nothing because FileSnap does not need to apply txns
    }

    /**
     * synchronized close just so that if serialize is in place
     * the close operation will block and will wait till serialize
     * is done and will set the close flag
     */
    @Override
    public synchronized void close() throws IOException {
        close = true;
    }

}
