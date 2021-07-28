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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RocksDBMultipleSnapshotsTest {
    private static final Logger LOG = LoggerFactory.getLogger(RocksDBMultipleSnapshotsTest.class);

    @Test
    public void testMultipleSaveAndRestore() throws Exception {
        System.setProperty(SnapshotFactory.ZOOKEEPER_SNAPSHOT_NAME, "org.apache.zookeeper.server.persistence.RocksDBSnap");
        // first snapshot
        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<Long, Integer>();
        sessions.put((long) 1, 2001);
        sessions.put((long) 2, 2002);
        sessions.put((long) 3, 2003);
        DataTree dt = new DataTree();
        dt.createNode("/foo", "foof".getBytes(), null, 0, 0, 1, 1);

        File tmpDir = ClientBase.createTmpDir();
        FileTxnSnapLog snapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        snapLog.save(dt, sessions, true);

        Map<Long, Integer> deserializedSessions = new HashMap<Long, Integer>();
        DataTree deserializedDatatree = new DataTree();

        FileTxnSnapLog.PlayBackListener listener = mock(FileTxnSnapLog.PlayBackListener.class);
        long result = snapLog.restore(deserializedDatatree, deserializedSessions, listener);

        assertTrue(deserializedSessions.get((long) 1) == 2001);
        assertTrue(deserializedSessions.get((long) 2) == 2002);
        assertTrue(deserializedSessions.get((long) 3) == 2003);
        assertTrue(deserializedSessions.keySet().size() == 3);
        assertTrue((int) result == 0);


        // second snapshot
        sessions = new ConcurrentHashMap<Long, Integer>();
        sessions.put((long) 11, 21);
        sessions.put((long) 22, 2002);
        sessions.put((long) 33, 23);
        dt = new DataTree();
        dt.createNode("/foo", "foof".getBytes(), null, 0, 0, 1, 1);
        snapLog.save(dt, sessions, true);

        deserializedSessions = new HashMap<Long, Integer>();
        deserializedDatatree = new DataTree();
        result = snapLog.restore(deserializedDatatree, deserializedSessions, listener);
        snapLog.close();

        assertTrue(deserializedSessions.get((long) 11) == 21);
        assertTrue(deserializedSessions.get((long) 22) == 2002);
        assertTrue(deserializedSessions.get((long) 33) == 23);
        assertTrue(deserializedSessions.keySet().size() == 3);
        assertTrue((int) result == 0);
    }
}
