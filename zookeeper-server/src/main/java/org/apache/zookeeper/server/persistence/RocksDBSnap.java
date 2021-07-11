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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Index;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.DataTree.ZxidDigest;
import org.apache.zookeeper.server.ReferenceCountedACLCache;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.TransactionChangeRecord;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements the snapshot interface.
 * It is responsible for storing, serializing
 * and deserializing the right snapshot in RocksDB,
 * and provides access to the snapshots.
 */
public class RocksDBSnap implements SnapShot {
    File snapDir;
    RocksDB db;
    Options options;
    WriteOptions writeOpts;
    RocksIterator rocksIterator;

    private volatile boolean close = false;

    private static final boolean SYNC_WRITE = false;
    private static final boolean DISABLE_WAL = true;

    //VisibleForTesting
    public static final String ROCKSDB_WRITE_BUFFER_SIZE = "zookeeper.rocksdbWriteBufferSize";

    private static final int PREFIX_STARTING_INDEX = 0;
    private static final int PREFIX_ENDING_INDEX = 3;

    private static final String SESSION_KEY_PREFIX = "S::";
    private static final String DATATREE_KEY_PREFIX = "T::";
    private static final String ACL_KEY_PREFIX = "A::";

    private static final String ZXID_KEY = "Zxid";
    private static final String ZXIDDIGEST_KEY = "ZxidDigest";

    private static final long DEFAULT_ROCKSDB_WRITE_BUFFER_SIZE = 4096 * 1024 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(RocksDBSnap.class);

    /**
     * The constructor which takes the snapDir. This class is instantiated
     * via SnapshotFactory
     *
     * @param snapDir the snapshot directory
     */
    public RocksDBSnap(File snapDir) throws IOException {
        RocksDB.loadLibrary();
        if (snapDir == null) {
            throw new IllegalArgumentException("Snap Directory can't be null!");
        }

        this.snapDir = snapDir;

        long rocksdbWriteBufferSize = Long.getLong(
                ROCKSDB_WRITE_BUFFER_SIZE, DEFAULT_ROCKSDB_WRITE_BUFFER_SIZE);
        this.options = new Options()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setDbWriteBufferSize(rocksdbWriteBufferSize);

        try {
            this.db = RocksDB.open(options, snapDir.getAbsolutePath());
        } catch (RocksDBException e) {
            throw new IOException("Failed to open RocksDB. " + "error: " + e.getMessage(), e);
        }
        // setting Sync = true and DisableWAL = true will lead to writes failing
        // and throwing an exception. So we set Sync = false here and let RocksDB
        // flush after serialization.
        this.writeOpts = new WriteOptions().setSync(SYNC_WRITE).setDisableWAL(DISABLE_WAL);
    }

    // VisibleForTesting
    public void initializeIterator() {
        rocksIterator = db.newIterator();
        rocksIterator.seekToFirst();
    }

    // VisibleForTesting
    public void closeIterator() {
        rocksIterator.close();
    }

    public long deserialize(DataTree dt, Map<Long, Integer> sessions)
            throws IOException {
        File[] files = snapDir.listFiles();
        if (files == null || files.length == 0) {
            LOG.info("No snapshot found in {}", snapDir.getName());
            return -1L;
        }
        long lastProcessedZxid;
        long start = Time.currentElapsedTime();
        try {
            byte[] zxidBytes = db.get(ZXID_KEY.getBytes(StandardCharsets.UTF_8));
            if (zxidBytes == null) {
                // We didn't find zxid infomation in RocksDB, which means
                // there is no RocksDB snapshot in the snapDir.
                LOG.info("No snapshot found in {}", snapDir.getName());
                return -1L;
            }
            lastProcessedZxid = Long.parseLong(new String(zxidBytes, StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new IOException("Failed to deserialize last processed zxid in RocksDB. " + "error: " + e.getMessage(), e);
        }
        LOG.info("RocksDB: Reading snapshot 0x{} from {}", Long.toHexString(lastProcessedZxid), snapDir);
        dt.lastProcessedZxid = lastProcessedZxid;

        rocksIterator = db.newIterator();
        rocksIterator.seekToFirst();
        ByteArrayInputStream bais;
        BinaryInputArchive bia;
        while (rocksIterator.isValid()) {
            String key = new String(rocksIterator.key(), StandardCharsets.UTF_8);
            String prefix = key.substring(PREFIX_STARTING_INDEX, PREFIX_ENDING_INDEX);
            switch (prefix) {
                case SESSION_KEY_PREFIX:
                    deserializeSessions(sessions);
                    break;
                case ACL_KEY_PREFIX:
                    deserializeACL(dt.getReferenceCountedAclCache());
                    break;
                case DATATREE_KEY_PREFIX:
                    dt.deserialize(this, "tree");
                    break;
                default:
                    // last processed zxid or zxid digest
                    rocksIterator.next();
                    break;
            }
        }
        rocksIterator.close();

        deserializeZxidDigest(dt);
        if (dt.getDigestFromLoadedSnapshot() != null) {
            dt.compareSnapshotDigests(lastProcessedZxid);
        }
        long elapsed = Time.currentElapsedTime() - start;
        LOG.info("RocksDBSnap deserialization takes " + elapsed + " ms");
        ServerMetrics.getMetrics().ROCKSDB_SNAPSHOT_DESERIALIZATION_TIME.add(elapsed);
        return lastProcessedZxid;
    }

    public synchronized void serialize(DataTree dt, Map<Long, Integer> sessions,
                                       long lastZxid, boolean fsync) throws IOException {
        if (close) {
            return;
        }
        if (fsync) {
            // take a full snapshot when snap sync with the leader

            // close RocksDB for cleaning up the old snapshot,
            // because destroyDB will fail if the RocksDB is open and locked
            db.close();
            // clean up the old snapshot
            try {
                RocksDB.destroyDB(snapDir.getAbsolutePath(), options);
            } catch (RocksDBException e) {
                throw new IOException("Failed to clean old data in RocksDB files: " + "error: " + e.getMessage(), e);
            }
            // re-open RocksDB for taking a new snapshot
            try {
                db = RocksDB.open(options, snapDir.getAbsolutePath());
            } catch (RocksDBException e) {
                throw new IOException("Failed to open RocksDB. " + "error: " + e.getMessage(), e);
            }

            LOG.info("RocksDB: Snapshotting: 0x{} to {}", Long.toHexString(lastZxid), snapDir);

            updateLastProcessedZxid(lastZxid, null);
            serializeSessions(sessions);
            serializeACL(dt.getReferenceCountedAclCache());
            dt.serialize(this, "tree");
            serializeZxidDigest(dt);
        }
        flush();
    }

    public void flush() throws IOException {
        long start = Time.currentElapsedTime();
        try (final FlushOptions flushOptions = new FlushOptions().setWaitForFlush(true)) {
            db.flush(flushOptions);
        } catch (RocksDBException e) {
            throw new IOException("Failed to flush in RocksDB: " + "error: " + e.getMessage(), e);
        }
        long elapsed = Time.currentElapsedTime() - start;
        ServerMetrics.getMetrics().ROCKSDB_FLUSH_TIME.add(elapsed);
    }

    public File findMostRecentSnapshot() throws IOException {
        // In RocksDB, we always apply transactions to the current snapshot.
        // So we only keep one single folder for the RocksDB snapshot. If
        // this snapshot cannot be loaded because of corrupted data, we will
        // sync with leader to get the latest data. Keeping multiple snapshots
        // won't help here, since we still need to take snapshot syncing with
        // the old snapshot, that's why we only keep one here.
        return snapDir;
    }

    @SuppressWarnings("unchecked")
    public void applyTxn(List<TransactionChangeRecord> changeList, long zxid) throws IOException {
        // We use RocksDB WriteBatch to make atomic updates.
        // We didn't let applying client's requests wait until flush finished because
        // flushing 200MB of data in memtables takes nearly 1.5 seconds, flushing 500MB
        // of data takes 4.5 seconds, and flushing 1GB of data takes more than 10 seconds.
        try (WriteBatch writeBatch = new WriteBatch()) {
            // update sessions, ACL, DataTree and ZxidDigest in RocksDB
            for (int i = 0; i < changeList.size(); i++) {
                TransactionChangeRecord change = changeList.get(i);
                switch (change.getType()) {
                    case TransactionChangeRecord.DATANODE:
                        String path = (String) change.getKey();
                        DataNode node = (DataNode) change.getValue();
                        String operation = change.getOperation();
                        if (operation.equals(TransactionChangeRecord.ADD)
                            || operation.equals(TransactionChangeRecord.UPDATE)) {
                            addNode(path, node, writeBatch);
                        } else {
                            removeNode(path, writeBatch);
                        }
                        break;
                    case TransactionChangeRecord.ACL:
                        Long index = (Long) change.getKey();
                        List<ACL> aclList = (List<ACL>) change.getValue();
                        if (change.getOperation().equals(TransactionChangeRecord.ADD)) {
                            addACLKeyValue(index, aclList, writeBatch);
                        } else {
                            removeACLKeyValue(index, writeBatch);
                        }
                        break;
                    case TransactionChangeRecord.SESSION:
                        Long id = (Long) change.getKey();
                        Integer timeout = (Integer) change.getValue();
                        if (change.getOperation().equals(TransactionChangeRecord.ADD)) {
                            addSessionKeyValue(id, timeout, writeBatch);
                        } else {
                            removeSessionKeyValue(id, writeBatch);
                        }
                        break;
                    case TransactionChangeRecord.ZXIDDIGEST:
                        ZxidDigest zxidDigest = (ZxidDigest) change.getValue();
                        if (change.getOperation().equals(TransactionChangeRecord.UPDATE)) {
                            updateZxidDigest(zxidDigest, writeBatch);
                        }
                        break;
                    default:
                        LOG.warn("Unknown TransactionChangeRecord type {}", change);
                        break;
                }
            }
            // update the zxid in RocksDB
            updateLastProcessedZxid(zxid, writeBatch);

            // even if RocksDB's memTable is auto flushed, we always have consistent data and zxid digest.
            db.write(writeOpts, writeBatch);
        } catch (RocksDBException e) {
            throw new IOException("Failed to apply txns to RocksDB " + "error: " + e.getMessage(), e);
        }
    }

    private void updateLastProcessedZxid(long zxid, WriteBatch writeBatch) throws IOException {
        try {
            if (writeBatch != null) {
                writeBatch.put(ZXID_KEY.getBytes(StandardCharsets.UTF_8),
                        Long.toString(zxid).getBytes(StandardCharsets.UTF_8));
            } else {
                db.put(writeOpts, ZXID_KEY.getBytes(StandardCharsets.UTF_8),
                        Long.toString(zxid).getBytes(StandardCharsets.UTF_8));
            }
        } catch (RocksDBException e) {
            throw new IOException("Failed to serialize last processed zxid in RocksDB. "
                    + "error: " + e.getMessage(), e);
        }
    }

    public void serializeSessions(Map<Long, Integer> sessions) throws IOException {
        HashMap<Long, Integer> sessSnap = new HashMap<Long, Integer>(sessions);
        for (Entry<Long, Integer> entry : sessSnap.entrySet()) {
            addSessionKeyValue(entry.getKey(), entry.getValue(), null);
        }
    }

    private void addSessionKeyValue(Long id, Integer timeout, WriteBatch writeBatch) throws IOException {
        try {
            String key = SESSION_KEY_PREFIX + id;
            if (writeBatch != null) {
                writeBatch.put(key.getBytes(StandardCharsets.UTF_8),
                        timeout.toString().getBytes(StandardCharsets.UTF_8));
            } else {
                db.put(writeOpts, key.getBytes(StandardCharsets.UTF_8),
                        timeout.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (RocksDBException e) {
            throw new IOException("Failed to serialize sessions in RocksDB " + "error: " + e.getMessage(), e);
        }
    }

    private void removeSessionKeyValue(Long id, WriteBatch writeBatch) throws IOException {
        try {
            String key = SESSION_KEY_PREFIX + id;
            writeBatch.delete(key.getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new IOException("Failed to delete the session in RocksDB " + "error: " + e.getMessage(), e);
        }
    }

    public void deserializeSessions(Map<Long, Integer> sessions) throws IOException {
        while (rocksIterator.isValid()) {
            String key = new String(rocksIterator.key(), StandardCharsets.UTF_8);
            if (!key.startsWith(SESSION_KEY_PREFIX)) {
                break;
            }
            key = key.substring(PREFIX_ENDING_INDEX);
            long id = Long.parseLong(key);
            int to = Integer.parseInt(new String(rocksIterator.value(), StandardCharsets.UTF_8));
            sessions.put(id, to);
            rocksIterator.next();
        }
    }

    public synchronized void serializeACL(ReferenceCountedACLCache aclCache) throws IOException {
        Set<Map.Entry<Long, List<ACL>>> set = aclCache.getLongKeyMap().entrySet();
        for (Map.Entry<Long, List<ACL>> val : set) {
            addACLKeyValue(val.getKey(), val.getValue(), null);
        }
    }

    private void addACLKeyValue(Long index, List<ACL> aclList, WriteBatch writeBatch) throws IOException {
        String key = ACL_KEY_PREFIX + index;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        boa.startVector(aclList, "acls");
        for (ACL acl : aclList) {
            acl.serialize(boa, "acl");
        }
        boa.endVector(aclList, "acls");
        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
        try {
            if (writeBatch != null) {
                writeBatch.put(key.getBytes(StandardCharsets.UTF_8), bb.array());
            } else {
                db.put(writeOpts, key.getBytes(StandardCharsets.UTF_8), bb.array());
            }
        } catch (RocksDBException e) {
            throw new IOException("Failed to serialize ACL lists in RocksDB " + "error: " + e.getMessage(), e);
        }
    }

    private void removeACLKeyValue(Long index, WriteBatch writeBatch) throws IOException {
        try {
            String key = ACL_KEY_PREFIX + index;
            writeBatch.delete(key.getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new IOException("Failed to delete the ACL list in RocksDB " + "error: " + e.getMessage(), e);
        }
    }

    public synchronized void deserializeACL(ReferenceCountedACLCache aclCache) throws IOException {
        aclCache.clear();
        ByteArrayInputStream bais;
        BinaryInputArchive bia;
        while (rocksIterator.isValid()) {
            String key = new String(rocksIterator.key(), StandardCharsets.UTF_8);
            if (!key.startsWith(ACL_KEY_PREFIX)) {
                break;
            }
            key = key.substring(PREFIX_ENDING_INDEX);
            long val = Long.parseLong(key);
            List<ACL> aclList = new ArrayList<ACL>();
            bais = new ByteArrayInputStream(rocksIterator.value());
            bia = BinaryInputArchive.getArchive(bais);
            Index j = bia.startVector("acls");
            if (j == null) {
                throw new RuntimeException("Incorrent format of InputArchive when deserialize DataTree - missing acls");
            }
            while (!j.done()) {
                ACL acl = new ACL();
                acl.deserialize(bia, "acl");
                aclList.add(acl);
                j.incr();
            }
            aclCache.updateMaps(val, aclList);
            rocksIterator.next();
        }
    }

    public void writeNode(String pathString, DataNode node) throws IOException {
        addNode(pathString, node, null);
    }

    private void addNode(String pathString, DataNode node, WriteBatch writeBatch) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        boa.writeRecord(node, "node");
        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
        try {
            pathString = DATATREE_KEY_PREFIX + pathString;
            if (writeBatch != null) {
                writeBatch.put(pathString.getBytes(StandardCharsets.UTF_8), bb.array());
            } else {
                db.put(writeOpts, pathString.getBytes(StandardCharsets.UTF_8), bb.array());
            }
        } catch (RocksDBException e) {
            throw new IOException("Failed to serialize data node in RocksDB " + "error: " + e.getMessage(), e);
        }
    }

    private void removeNode(String pathString, WriteBatch writeBatch) throws IOException {
        try {
            pathString = DATATREE_KEY_PREFIX + pathString;
            writeBatch.delete(pathString.getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new IOException("Failed to delete the data node in RocksDB " + "error: " + e.getMessage(), e);
        }
    }

    public void markEnd() throws IOException {
        // nothing needs to be done here when taking a snapshot in RocksDB
    }

    public String readNode(DataNode node) throws IOException {
        if (!rocksIterator.isValid()) {
            // finished iterating over all data nodes in RocksDB snapshot
            return "/";
        }
        String path = new String(rocksIterator.key(), StandardCharsets.UTF_8);
        if (!path.startsWith(DATATREE_KEY_PREFIX)) {
            // finished iterating over all data nodes in RocksDB snapshot
            return "/";
        }
        path = path.substring(PREFIX_ENDING_INDEX);
        ByteArrayInputStream bais = new ByteArrayInputStream(rocksIterator.value());
        BinaryInputArchive bia = BinaryInputArchive.getArchive(bais);
        bia.readRecord(node, "node");
        rocksIterator.next();
        return path;
    }

    public boolean serializeZxidDigest(DataTree dt) throws IOException {
        if (dt.nodesDigestEnabled()) {
            ZxidDigest zxidDigest = dt.getLastProcessedZxidDigest();
            if (zxidDigest == null) {
                zxidDigest = dt.getBlankDigest();
            }
            updateZxidDigest(zxidDigest, null);
            return true;
        }
        return false;
    }

    private void updateZxidDigest(ZxidDigest zxidDigest, WriteBatch writeBatch) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        zxidDigest.serialize(boa);
        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
        try {
            if (writeBatch != null) {
                writeBatch.put(ZXIDDIGEST_KEY.getBytes(StandardCharsets.UTF_8), bb.array());
            } else {
                db.put(writeOpts, ZXIDDIGEST_KEY.getBytes(StandardCharsets.UTF_8), bb.array());
            }
        } catch (RocksDBException e) {
            throw new IOException("Failed to serialize zxid digest in RocksDB " + "error: " + e.getMessage(), e);
        }
    }

    public boolean deserializeZxidDigest(DataTree dt) throws IOException {
        if (dt.nodesDigestEnabled()) {
            try {
                byte[] zxidDigestBytes = db.get(ZXIDDIGEST_KEY.getBytes(StandardCharsets.UTF_8));
                ZxidDigest zxidDigest = dt.getBlankDigest();
                ByteArrayInputStream bais = new ByteArrayInputStream(zxidDigestBytes);
                BinaryInputArchive bia = BinaryInputArchive.getArchive(bais);
                zxidDigest.deserialize(bia);
                dt.setZxidDigestFromLoadedSnapshot(zxidDigest);
            } catch (RocksDBException e) {
                throw new IOException("Failed to deserialize zxid digest from RocksDB " + "error: " + e.getMessage(), e);
            }
            return true;
        }
        return false;
    }

    /**
     * synchronized close just so that if serialize is in place
     * the close operation will block and will wait till serialize
     * is done and will set the close flag
     */
    @Override
    public synchronized void close() throws IOException {
        close = true;
        writeOpts.close();
        db.close();
        options.close();
    }

    public SnapshotInfo getLastSnapshotInfo() {
        return null;
    }
}
