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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.server.metric.SimpleCounter;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.TxnLog.TxnIterator;
import org.apache.zookeeper.server.quorum.QuorumPeerMainTest;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TxnLogDigestTest extends ClientBase {

    private static final Logger LOG =
            LoggerFactory.getLogger(TxnLogDigestTest.class);

    private ZooKeeper zk;
    private ZooKeeperServer server;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        server = serverFactory.getZooKeeperServer();
        zk = createClient();
    }

    @AfterEach
    public void tearDown() throws Exception {
        // server will be closed in super.tearDown
        super.tearDown();

        if (zk != null) {
            zk.close();
        }
        MockedFileTxnLog.reset();
    }

    @Override
    public void setupCustomizedEnv() {
        ZooKeeperServer.setDigestEnabled(true);
    }

    @Override
    public void cleanUpCustomizedEnv() {
        ZooKeeperServer.setDigestEnabled(false);
    }

    @BeforeAll
    public static void applyMockUps() {
        new MockedFileTxnLog();
    }

    /**
     * Check that the digest stored in the txn matches the digest calculated
     * from DataTree.
     */
    @Test
    public void digestFromTxnLogsMatchesTree() throws Exception {
        // reset the mismatch metrics
        SimpleCounter digestMistachesCount = (SimpleCounter) ServerMetrics.getMetrics().DIGEST_MISMATCHES_COUNT;
        digestMistachesCount.reset();

        // trigger some write ops
        performOperations(createClient(), "/digestFromTxnLogsMatchesTree");

        // make sure there is no digest mismatch
        assertEquals(0, digestMistachesCount.get());

        // verify that the digest is wrote to disk with txn
        TxnDigest lastDigest = getLastTxnLogDigest();
        assertNotNull(lastDigest);
        assertEquals(server.getZKDatabase().getDataTree().getTreeDigest(),
                lastDigest.getTreeDigest());
    }

    /**
     * Test the compatible when enable/disable digest:
     *
     * * check that txns which were written with digest can be read when
     *   digest is disabled
     * * check that txns which were written without digest can be read
     *   when digest is enabled.
     */
    @Test
    public void checkTxnCompatibleWithAndWithoutDigest() throws Exception {
        // 1. start server with digest disabled
        restartServerWithDigestFlag(false);

        // trigger some write ops
        Map<String, String> expectedNodes = performOperations(createClient(), "/p1");

        // reset the mismatch metrics
        SimpleCounter digestMistachesCount = (SimpleCounter) ServerMetrics.getMetrics().DIGEST_MISMATCHES_COUNT;
        digestMistachesCount.reset();

        // 2. restart server with digest enabled
        restartServerWithDigestFlag(true);

        // make sure the data wrote when digest was disabled can be
        // successfully read
        checkNodes(expectedNodes);

        Map<String, String> expectedNodes1 = performOperations(createClient(), "/p2");

        // make sure there is no digest mismatch
        assertEquals(0, digestMistachesCount.get());

        // 3. disable the digest again and make sure everything is fine
        restartServerWithDigestFlag(false);

        checkNodes(expectedNodes);
        checkNodes(expectedNodes1);
    }

    /**
     * Simulate the scenario where txn is missing, and make sure the
     * digest code can catch this issue.
     */
    @Test
    public void testTxnMissing() throws Exception {
        // updated MockedFileTxnLog to skip append txn on specific txn
        MockedFileTxnLog.skipAppendZxid = 3;

        // trigger some write operations
        performOperations(createClient(), "/testTxnMissing");

        // restart server to load the corrupted txn file
        SimpleCounter digestMistachesCount = (SimpleCounter) ServerMetrics.getMetrics().DIGEST_MISMATCHES_COUNT;
        digestMistachesCount.reset();

        restartServerWithDigestFlag(true);

        // check that digest mismatch is reported
        assertThat("mismtach should be reported", digestMistachesCount.get(), greaterThan(0L));

        // restart server with digest disabled
        digestMistachesCount.reset();
        restartServerWithDigestFlag(false);

        // check that no digest mismatch is reported
        assertEquals(0, digestMistachesCount.get());
    }

    private void restartServerWithDigestFlag(boolean digestEnabled)
            throws Exception {
        stopServer();
        QuorumPeerMainTest.waitForOne(zk, States.CONNECTING);

        ZooKeeperServer.setDigestEnabled(digestEnabled);

        startServer();
        QuorumPeerMainTest.waitForOne(zk, States.CONNECTED);
    }

    private TxnDigest getLastTxnLogDigest() throws IOException {
        TxnIterator itr = new FileTxnLog(new File(tmpDir, "version-2")).read(1);
        TxnDigest lastDigest = null;
        while (itr.next()) {
            lastDigest = itr.getDigest();
        }
        return lastDigest;
    }

    public static void create(ZooKeeper client, String path, CreateMode mode)
              throws Exception {
         client.create(path, path.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, mode);
    }

    /**
     * Helper method to trigger various write ops inside ZK.
     */
    public static Map<String, String> performOperations(
            ZooKeeper client, String prefix) throws Exception {
        Map<String, String> nodes = new HashMap<>();

        String path = prefix;
        create(client, path, CreateMode.PERSISTENT);
        nodes.put(path, path);

        path = prefix + "/child1";
        create(client, path, CreateMode.PERSISTENT);
        nodes.put(path, path);

        path = prefix + "/child2";
        create(client, path, CreateMode.PERSISTENT);
        client.delete(prefix + "/child2", -1);

        path = prefix + "/child1/leaf";
        create(client, path, CreateMode.PERSISTENT);
        String updatedData = "updated data";
        client.setData(path, updatedData.getBytes(), -1);
        nodes.put(path, updatedData);

        List<Op> subTxns = new ArrayList<Op>();
        for (int i = 0; i < 3; i++) {
            path = prefix + "/m" + i;
            subTxns.add(Op.create(path, path.getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            nodes.put(path, path);
        }
        client.multi(subTxns);
        client.close();

        return nodes;
    }

    private void checkNodes(Map<String, String> expectedNodes) throws Exception {
        ZooKeeper client = createClient();
        try {
            for (Map.Entry<String, String> entry: expectedNodes.entrySet()) {
                assertEquals(entry.getValue(),
                        new String(client.getData(entry.getKey(), false, null)));
            }
        } finally {
            client.close();
        }
    }

    public static final class MockedFileTxnLog extends MockUp<FileTxnLog> {
        static long skipAppendZxid = -1;

        @Mock
        public synchronized boolean append(Invocation invocation, TxnHeader hdr,
                Record txn, TxnDigest digest) throws IOException {
            if (hdr != null && hdr.getZxid() == skipAppendZxid) {
                LOG.info("skipping txn {}", skipAppendZxid);
                return true;
            }
            return invocation.proceed(hdr, txn, digest);
        }

        public static void reset() {
            skipAppendZxid = -1;
        }
    };
}
