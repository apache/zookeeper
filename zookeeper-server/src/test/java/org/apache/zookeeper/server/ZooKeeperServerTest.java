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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.SnapStream;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;

public class ZooKeeperServerTest extends ZKTestCase {

    @Test
    public void testSortDataDirAscending() {
        File[] files = new File[5];

        files[0] = new File("foo.10027c6de");
        files[1] = new File("foo.10027c6df");
        files[2] = new File("bar.10027c6dd");
        files[3] = new File("foo.10027c6dc");
        files[4] = new File("foo.20027c6dc");

        File[] orig = files.clone();

        List<File> filelist = Util.sortDataDir(files, "foo", true);

        assertEquals(orig[2], filelist.get(0));
        assertEquals(orig[3], filelist.get(1));
        assertEquals(orig[0], filelist.get(2));
        assertEquals(orig[1], filelist.get(3));
        assertEquals(orig[4], filelist.get(4));
    }

    @Test
    public void testSortDataDirDescending() {
        File[] files = new File[5];

        files[0] = new File("foo.10027c6de");
        files[1] = new File("foo.10027c6df");
        files[2] = new File("bar.10027c6dd");
        files[3] = new File("foo.10027c6dc");
        files[4] = new File("foo.20027c6dc");

        File[] orig = files.clone();

        List<File> filelist = Util.sortDataDir(files, "foo", false);

        assertEquals(orig[4], filelist.get(0));
        assertEquals(orig[1], filelist.get(1));
        assertEquals(orig[0], filelist.get(2));
        assertEquals(orig[3], filelist.get(3));
        assertEquals(orig[2], filelist.get(4));
    }

    @Test
    public void testGetLogFiles() {
        File[] files = new File[5];

        files[0] = new File("log.10027c6de");
        files[1] = new File("log.10027c6df");
        files[2] = new File("snapshot.10027c6dd");
        files[3] = new File("log.10027c6dc");
        files[4] = new File("log.20027c6dc");

        File[] orig = files.clone();

        File[] filelist = FileTxnLog.getLogFiles(files, Long.parseLong("10027c6de", 16));

        assertEquals(3, filelist.length);
        assertEquals(orig[0], filelist[0]);
        assertEquals(orig[1], filelist[1]);
        assertEquals(orig[4], filelist[2]);
    }

    @Test
    public void testForceSyncDefaultEnabled() {
        File file = new File("foo.10027c6de");
        FileTxnLog log = new FileTxnLog(file);
        assertTrue(log.isForceSync());
    }

    @Test
    public void testForceSyncDefaultDisabled() {
        try {
            File file = new File("foo.10027c6de");
            System.setProperty("zookeeper.forceSync", "no");
            FileTxnLog log = new FileTxnLog(file);
            assertFalse(log.isForceSync());
        } finally {
            //Reset back to default.
            System.setProperty("zookeeper.forceSync", "yes");
        }
    }

    @Test
    public void testInvalidSnapshot() {
        File f = null;
        File tmpFileDir = null;
        try {
            tmpFileDir = ClientBase.createTmpDir();
            f = new File(tmpFileDir, "snapshot.0");
            if (!f.exists()) {
                f.createNewFile();
            }
            assertFalse(SnapStream.isValidSnapshot(f), "Snapshot file size is greater than 9 bytes");
            assertTrue(f.delete(), "Can't delete file");
        } catch (IOException e) {
        } finally {
            if (null != tmpFileDir) {
                ClientBase.recursiveDelete(tmpFileDir);
            }
        }
    }

}
