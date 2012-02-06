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

package org.apache.zookeeper.server;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.RandomAccessFile;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This test checks that the server works even if the last snapshot is
 * invalidated by corruption or if the server crashes while generating the
 * snapshot.
 */
public class InvalidSnapshotTest extends ClientBase {
    private static final Logger LOG =
            LoggerFactory.getLogger(InvalidSnapshotTest.class);

    public InvalidSnapshotTest() {
        SyncRequestProcessor.setSnapCount(100);
    }

    /**
     * Validate that the server can come up on an invalid snapshot - by
     * reverting to a prior snapshot + associated logs.
     */
    @Test
    public void testInvalidSnapshot() throws Exception {
        ZooKeeper zk = createClient();
        try {
            for (int i = 0; i < 2000; i++) {
                zk.create("/invalidsnap-" + i, new byte[0],
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }
        NIOServerCnxnFactory factory = (NIOServerCnxnFactory)serverFactory;
        stopServer();

        // now corrupt the snapshot
        File snapFile = factory.zkServer.getTxnLogFactory().findMostRecentSnapshot();
        LOG.info("Corrupting " + snapFile);
        RandomAccessFile raf = new RandomAccessFile(snapFile, "rws");
        raf.setLength(3);
        raf.close();

        // now restart the server
        startServer();

        // verify that the expected data exists and wasn't lost
        zk = createClient();
        try {
            assertTrue("the node should exist",
                    (zk.exists("/invalidsnap-1999", false) != null));
        } finally {
            zk.close();
        }
    }
}
