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

import static org.apache.zookeeper.server.DatadirCleanupManager.PurgeTaskStatus.COMPLETED;
import static org.apache.zookeeper.server.DatadirCleanupManager.PurgeTaskStatus.NOT_STARTED;
import static org.apache.zookeeper.server.DatadirCleanupManager.PurgeTaskStatus.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.File;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DatadirCleanupManagerTest extends ZKTestCase {

    private DatadirCleanupManager purgeMgr;
    private File snapDir;
    private File dataLogDir;

    @BeforeEach
    public void setUp() throws Exception {
        File dataDir = ClientBase.createTmpDir();
        snapDir = dataDir;
        dataLogDir = dataDir;
    }

    @Test
    public void testPurgeTask() throws Exception {
        purgeMgr = new DatadirCleanupManager(snapDir, dataLogDir, 3, 1);
        purgeMgr.start();
        assertEquals(dataLogDir, purgeMgr.getDataLogDir(), "Data log directory is not set as configured");
        assertEquals(snapDir, purgeMgr.getSnapDir(), "Snapshot directory is not set as configured");
        assertEquals(3, purgeMgr.getSnapRetainCount(), "Snapshot retain count is not set as configured");
        assertEquals(STARTED, purgeMgr.getPurgeTaskStatus(), "Purge task is not started");
        purgeMgr.shutdown();
        assertEquals(COMPLETED, purgeMgr.getPurgeTaskStatus(), "Purge task is still running after shutdown");
    }

    @Test
    public void testWithZeroPurgeInterval() throws Exception {
        purgeMgr = new DatadirCleanupManager(snapDir, dataLogDir, 3, 0);
        purgeMgr.start();
        assertEquals(NOT_STARTED, purgeMgr.getPurgeTaskStatus(), "Purge task is scheduled with zero purge interval");
        purgeMgr.shutdown();
        assertEquals(NOT_STARTED, purgeMgr.getPurgeTaskStatus(), "Purge task is scheduled with zero purge interval");
    }

    @Test
    public void testWithNegativePurgeInterval() throws Exception {
        purgeMgr = new DatadirCleanupManager(snapDir, dataLogDir, 3, -1);
        purgeMgr.start();
        assertEquals(NOT_STARTED, purgeMgr.getPurgeTaskStatus(), "Purge task is scheduled with negative purge interval");
        purgeMgr.shutdown();
        assertEquals(NOT_STARTED, purgeMgr.getPurgeTaskStatus(), "Purge task is scheduled with negative purge interval");
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (purgeMgr != null) {
            purgeMgr.shutdown();
        }
    }

}
