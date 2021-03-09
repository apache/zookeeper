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

package org.apache.zookeeper.test;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.File;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SnapshotFormatter;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InvalidSnapshotTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(InvalidSnapshotTest.class);
    private static final String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();

    private static final File testData = new File(System.getProperty("test.data.dir", "src/test/resources/data"));

    /**
     * Verify the LogFormatter by running it on a known file.
     */
    @SuppressWarnings("deprecation")
    @Test
    public void testLogFormatter() throws Exception {
        File snapDir = new File(testData, "invalidsnap");
        File logfile = new File(new File(snapDir, "version-2"), "log.274");
        String[] args = {logfile.getCanonicalFile().toString()};
        org.apache.zookeeper.server.LogFormatter.main(args);
    }

    /**
     * Verify the SnapshotFormatter by running it on a known file.
     */
    @Test
    public void testSnapshotFormatter() throws Exception {
        File snapDir = new File(testData, "invalidsnap");
        File snapfile = new File(new File(snapDir, "version-2"), "snapshot.272");
        String[] args = {snapfile.getCanonicalFile().toString()};
        SnapshotFormatter.main(args);
    }

    /**
     * Verify the SnapshotFormatter by running it on a known file with one null data.
     */
    @Test
    public void testSnapshotFormatterWithNull() throws Exception {
        File snapDir = new File(testData, "invalidsnap");
        File snapfile = new File(new File(snapDir, "version-2"), "snapshot.273");
        String[] args = {snapfile.getCanonicalFile().toString()};
        SnapshotFormatter.main(args);
    }

    /**
     * Verify the SnapshotFormatter fails as expected on corrupted snapshot.
     */
    @Test
    public void testSnapshotFormatterWithInvalidSnap() throws Exception {
        File snapDir = new File(testData, "invalidsnap");
        // Broken snapshot introduced by ZOOKEEPER-367, and used to
        // demonstrate recovery in testSnapshot below.
        File snapfile = new File(new File(snapDir, "version-2"), "snapshot.83f");
        String[] args = {snapfile.getCanonicalFile().toString()};
        try {
            SnapshotFormatter.main(args);
            fail("Snapshot '" + snapfile + "' unexpectedly parsed without error.");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Unreasonable length = 977468229"));
        }
    }

    /**
     * test the snapshot
     * @throws Exception an exception could be expected
     */
    @Test
    public void testSnapshot() throws Exception {
        File origSnapDir = new File(testData, "invalidsnap");

        // This test otherwise updates the resources directory.
        File snapDir = ClientBase.createTmpDir();
        FileUtils.copyDirectory(origSnapDir, snapDir);

        ZooKeeperServer zks = new ZooKeeperServer(snapDir, snapDir, 3000);
        SyncRequestProcessor.setSnapCount(1000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        LOG.info("starting up the zookeeper server .. waiting");
        assertTrue("waiting for server being up", ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
        ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);
        try {
            // we know this from the data files
            // this node is the last node in the snapshot

            assertTrue(zk.exists("/9/9/8", false) != null);
        } finally {
            zk.close();
        }
        f.shutdown();
        zks.shutdown();
        assertTrue("waiting for server down", ClientBase.waitForServerDown(HOSTPORT, ClientBase.CONNECTION_TIMEOUT));

    }

}
