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

import org.junit.Assert;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.zookeeper.server.DataTree;
import org.junit.Test;

/**
 * This test checks that the server does not create empty snapshot files if the
 * disk is full.
 */
public class EmptySnapshotTest {

    static class MockFileSnap extends FileSnap {

        MockFileSnap(File snapDir) {
            super(snapDir);
        }

        public synchronized void serialize(DataTree dt, Map<Long, Integer> sessions, File snapShot,
                                           boolean fsync) throws IOException {
            // Create empty new file.
            Assert.assertTrue(snapShot.createNewFile());
            throw new IOException("Created empty snapshot file from " +
                                  "MockFileSnap::serialize()");
        }

    }

    @Test
    public void testNoEmptySnapshot() throws Exception {
        File tmpFile = File.createTempFile("empty-snapshot-test", ".junit",
                 new File(System.getProperty("build.test.dir", "build")));
        File tmpDataDir = new File(tmpFile + ".dir");
        Assert.assertFalse(tmpDataDir.exists());
        Assert.assertTrue(tmpDataDir.mkdirs());

        FileTxnSnapLog snapLog = new FileTxnSnapLog(tmpDataDir, tmpDataDir);
        snapLog.snapLog = new MockFileSnap(snapLog.dataDir);

        Assert.assertEquals(0,
                ((FileSnap) snapLog.snapLog).findNRecentSnapshots(10).size());

        DataTree tree = new DataTree();
        tree.createNode("/empty-snapshot-test-1", "data".getBytes(), null, -1,
                       -1, 1, 1);
        try {
            snapLog.save(tree, new ConcurrentHashMap<Long, Integer>(), false);
            Assert.fail("Should have thrown an IOException");
        } catch (IOException e) {
        }

        Assert.assertEquals(0,
                ((FileSnap) snapLog.snapLog).findNRecentSnapshots(10).size());

        snapLog.snapLog = new FileSnap(snapLog.dataDir);
        snapLog.save(tree, new ConcurrentHashMap<Long, Integer>(), false);
        Assert.assertEquals(1,
                ((FileSnap) snapLog.snapLog).findNRecentSnapshots(10).size());
  }
}
