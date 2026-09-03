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

package org.apache.zookeeper.server.quorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.Map;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.admin.Commands;
import org.apache.zookeeper.server.util.ZxidLayout;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test of the upgrade to the wide-counter zxid layout
 * (ZOOKEEPER-2789): an ensemble full of legacy-layout data is restarted
 * with {@code zookeeper.wideCounterZxidEnabled}, the new leader switches
 * the layout at its new epoch, the legacy data is synced correctly across
 * the layout boundary, and the switch survives further restarts.
 *
 * <p>Introduced by Benedict Jin (asdf2014) for ZOOKEEPER-2789.
 */
public class WideCounterZxidUpgradeTest extends ZKTestCase {

    private final QuorumUtil qu = new QuorumUtil(1); // 3 servers

    @AfterEach
    public void tearDown() throws Exception {
        System.clearProperty(QuorumPeer.WIDE_COUNTER_ZXID_ENABLED);
        qu.shutdownAll();
        qu.tearDown();
    }

    @Test
    public void testUpgradeToWideCounterZxid() throws Exception {
        qu.disableJMXTest = true;

        // Phase 1: a legacy ensemble commits some data.
        qu.startAll();
        long legacyZxid;
        ZooKeeper zk = ClientBase.createZKClient(qu.getConnString());
        try {
            for (int i = 0; i < 10; i++) {
                zk.create("/legacy-" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            Stat stat = zk.exists("/legacy-9", false);
            legacyZxid = stat.getCzxid();
            assertFalse(qu.getLeaderQuorumPeer().getZxidLayoutState().isSwitched());
        } finally {
            zk.close();
        }
        qu.shutdownAll();

        // Phase 2: every binary now understands the wide layout and the
        // feature is enabled; the newly elected leader switches the layout.
        // The followers hand their legacy last zxids to the leader during
        // the sync, exercising the mixed-layout parsing.
        System.setProperty(QuorumPeer.WIDE_COUNTER_ZXID_ENABLED, "true");
        qu.startAll();
        long switchEpoch;
        long wideZxid;
        zk = ClientBase.createZKClient(qu.getConnString());
        try {
            for (int i = 0; i < 10; i++) {
                assertNotNull(zk.exists("/legacy-" + i, false), "legacy data lost across the layout switch: /legacy-" + i);
            }
            QuorumPeer leader = qu.getLeaderQuorumPeer();
            assertTrue(leader.getZxidLayoutState().isSwitched(), "the new leader should have switched the layout");
            switchEpoch = leader.getZxidLayoutState().getSwitchEpoch();

            zk.create("/wide-0", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            Stat stat = zk.exists("/wide-0", false);
            wideZxid = stat.getCzxid();
            assertEquals(switchEpoch, ZxidLayout.WIDE_COUNTER.getEpochFromZxid(wideZxid));
            assertTrue(wideZxid > legacyZxid, "the total zxid order must survive the layout switch");

            // The operator-visible zabstate command decomposes the last zxid
            // with the switched layout, so it reports the real 24-bit epoch
            // rather than a legacy 32-bit misreading.
            Map<String, Object> zabState = Commands.runGetCommand(
                "zabstate", leader.getActiveServer(), new HashMap<>(), null, null).toMap();
            assertEquals(switchEpoch, ((Number) zabState.get("zab_epoch")).longValue());
        } finally {
            zk.close();
        }

        // A follower that misses some wide-counter commits catches up with
        // a sync over the mixed-layout data it holds.
        int followerId = qu.getLeaderServer() == 1 ? 2 : 1;
        qu.shutdown(followerId);
        zk = ClientBase.createZKClient(qu.getConnString());
        try {
            zk.create("/wide-1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } finally {
            zk.close();
        }
        qu.restart(followerId);
        zk = ClientBase.createZKClient(qu.getConnectionStringForServer(followerId));
        try {
            assertNotNull(zk.exists("/wide-1", false), "the restarted follower should have synced the wide-counter commits");
            assertNotNull(zk.exists("/legacy-0", false));
        } finally {
            zk.close();
        }

        // Phase 3: the switch epoch is reloaded from disk after a full
        // restart, and the ensemble keeps working in the wide layout.
        qu.shutdownAll();
        qu.startAll();
        zk = ClientBase.createZKClient(qu.getConnString());
        try {
            assertNotNull(zk.exists("/legacy-0", false));
            assertNotNull(zk.exists("/wide-0", false));
            QuorumPeer leader = qu.getLeaderQuorumPeer();
            assertTrue(leader.getZxidLayoutState().isSwitched());
            assertEquals(switchEpoch, leader.getZxidLayoutState().getSwitchEpoch(),
                "the switch epoch must not move once recorded");
            zk.create("/wide-2", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            Stat stat = zk.exists("/wide-2", false);
            // The restart elected a new epoch, still in the wide layout.
            assertTrue(ZxidLayout.WIDE_COUNTER.getEpochFromZxid(stat.getCzxid()) > switchEpoch);
            assertTrue(stat.getCzxid() > wideZxid);
        } finally {
            zk.close();
        }
    }

}
