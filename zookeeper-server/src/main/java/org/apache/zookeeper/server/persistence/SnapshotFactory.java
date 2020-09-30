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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnapshotFactory {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotFactory.class);

    public static final String ZOOKEEPER_SNAPSHOT_NAME = "zookeeper.snapshotName";

    public static boolean shouldRecordTransactionChanges() {
        String snapName = System.getProperty(ZOOKEEPER_SNAPSHOT_NAME);
        if (snapName == null || snapName.equals(FileSnap.class.getName())) {
            return false;
        } else if (snapName.equals(RocksDBSnap.class.getName())
            || snapName.equals(RocksDBToFileSnap.class.getName())
            || snapName.equals(FileToRocksDBSnap.class.getName())){
            return true;
        }
        return false;
    }

    public static SnapShot createSnapshot(File snapDir) throws IOException {
        String snapshotName = System.getProperty(ZOOKEEPER_SNAPSHOT_NAME);
        if (snapshotName == null) {
            snapshotName = FileSnap.class.getName();
        }

        try {
            SnapShot snapshot = (SnapShot) Class.forName(snapshotName).getConstructor(File.class).newInstance(snapDir);
            LOG.info("Using {} to take snapshot", snapshotName);
            return snapshot;
        } catch (Exception e) {
            IOException ioe = new IOException("Couldn't instantiate " + snapshotName);
            ioe.initCause(e);
            throw ioe;
        }
    }
}
