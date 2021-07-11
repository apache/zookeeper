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

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ReferenceCountedACLCache;
import org.apache.zookeeper.server.TransactionChangeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements the snapshot interface.
 * It is responsible for reading a snapshot from
 * file systems and taking a snapshot in RocksDB
 */
public class FileToRocksDBSnap implements SnapShot {
    private static final Logger LOG = LoggerFactory.getLogger(FileToRocksDBSnap.class);

    SnapShot fileSnapLog;
    SnapShot rocksdbSnapLog;
    // RocksDB will only take a full snapshot if this is true. When we are loading
    // the database, we always need take a full snapshot in RocksDB.
    private final boolean SYNC_SNAP = true;

    public FileToRocksDBSnap(File snapDir) throws IOException {
        this.fileSnapLog = new FileSnap(snapDir);
        this.rocksdbSnapLog = new RocksDBSnap(snapDir);
    }

    public long deserialize(DataTree dt, Map<Long, Integer> sessions)
            throws IOException {
        long lastZxid = fileSnapLog.deserialize(dt, sessions);
        // After deserializing the data from the snapshot in files, we need take
        // a snapshot in RocksDB immediately, otherwise we won't have a RocksDB
        // snapshot to apply transactions to when replaying the transactions in
        // transaction logs.
        serialize(dt, sessions, lastZxid, SYNC_SNAP);
        return lastZxid;
    }

    public synchronized void serialize(DataTree dt, Map<Long, Integer> sessions, long lastZxid, boolean fsync)
            throws IOException {
        rocksdbSnapLog.serialize(dt, sessions, lastZxid, fsync);
    }

    public File findMostRecentSnapshot() throws IOException {
        // do nothing, because in deserialization, this method is called in FileSnap.
        return null;
    }

    public void serializeSessions(Map<Long, Integer> sessions) throws IOException {
        // do nothing, because in serialization, this method is called in RocksDBSnap.
    }

    public void deserializeSessions(Map<Long, Integer> sessions) throws IOException {
        // do nothing, because in deserialization, this method is called in FileSnap.
    }

    public void serializeACL(ReferenceCountedACLCache aclCache) throws IOException {
        // do nothing, because in serialization, this method is called in RocksDBSnap.
    }

    public void deserializeACL(ReferenceCountedACLCache aclCache) throws IOException {
        // do nothing, because in deserialization, this method is called in FileSnap.
    }

    public void writeNode(String pathString, DataNode node) throws IOException {
        // do nothing, because in serialization, this method is called in RocksDBSnap.
    }

    public void markEnd() throws IOException {
        // do nothing, because in serialization, this method is called in RocksDBSnap.
    }

    public String readNode(DataNode node) throws IOException {
        // do nothing, because in deserialization, this method is called in FileSnap.
        return null;
    }

    public boolean serializeZxidDigest(DataTree dt) throws IOException {
        // do nothing, because in serialization, this method is called in RocksDBSnap.
        return false;
    }

    public boolean deserializeZxidDigest(DataTree dt) throws IOException {
        // do nothing, because in deserialization, this method is called in FileSnap.
        return false;
    }

    public void applyTxn(List<TransactionChangeRecord> changeList, long zxid) throws IOException {
        rocksdbSnapLog.applyTxn(changeList, zxid);
    }

    public void close() throws IOException {
        fileSnapLog.close();
        rocksdbSnapLog.close();
    }

    public SnapshotInfo getLastSnapshotInfo() {
        return null;
    }
}
