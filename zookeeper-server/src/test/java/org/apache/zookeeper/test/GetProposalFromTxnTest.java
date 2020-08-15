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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.TxnLogEntry;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.Test;

/**
 * Test loading committed proposal from txnlog. Learner uses these proposals to
 * catch-up with leader
 */
public class GetProposalFromTxnTest extends ZKTestCase {

    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private static final int CONNECTION_TIMEOUT = 3000;

    private static final int MSG_COUNT = 2000;

    /**
     * Test loading proposal from txnlog
     *
     * @throws Exception
     *             an exception might be thrown here
     */
    @Test
    public void testGetProposalFromTxn() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(100);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up ");
        ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);

        // Generate transaction so we will have some txnlog
        Long[] zxids = new Long[MSG_COUNT];
        try {
            String data = "data";
            byte[] bytes = data.getBytes();
            for (int i = 0; i < MSG_COUNT; i++) {
                Stat stat = new Stat();
                zk.create("/invalidsnap-" + i, bytes, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                zk.getData("/invalidsnap-" + i, null, stat);
                zxids[i] = stat.getCzxid();
            }

        } finally {
            zk.close();
        }

        // shutdown and start zookeeper again
        f.shutdown();
        zks.shutdown();
        assertTrue(ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server to shutdown");
        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        zks.startdata();

        ZKDatabase db = zks.getZKDatabase();

        // Set sizeLimit to be very high number, so we can pull all transactions
        // from txnlog
        Iterator<Proposal> itr = db.getProposalsFromTxnLog(zxids[0], 10000000);

        int createCount = 0;
        ArrayList<Long> retrievedZxids = new ArrayList<Long>(MSG_COUNT);

        // Get zxid of create requests
        while (itr.hasNext()) {
            Proposal proposal = itr.next();
            TxnLogEntry logEntry = SerializeUtils.deserializeTxn(
                    proposal.packet.getData());
            TxnHeader hdr = logEntry.getHeader();
            Record rec = logEntry.getTxn();
            if (hdr.getType() == OpCode.create) {
                retrievedZxids.add(hdr.getZxid());
                createCount++;
            }
        }

        // All zxid should match what we created
        assertTrue(Arrays.equals(zxids, retrievedZxids.toArray(new Long[0])), "Zxids missmatches");

        // There should be 2000 create requests
        assertTrue((createCount == MSG_COUNT), "create proposal count == " + MSG_COUNT);

        // We are requesting half the number of transaction from the snapshot
        // this should exceed threshold (ZKDatabase.snapshotSizeFactor)
        db.setSnapshotSizeFactor(0.33);
        long sizeLimit = db.calculateTxnLogSizeLimit();

        itr = db.getProposalsFromTxnLog(zxids[MSG_COUNT / 2], sizeLimit);
        assertFalse((itr.hasNext()), "Expect empty proposal");
        f.shutdown();
        zks.shutdown();
    }

}
