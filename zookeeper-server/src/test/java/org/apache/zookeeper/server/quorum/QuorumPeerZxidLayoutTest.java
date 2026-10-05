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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.util.ZxidLayout;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the per-QuorumPeer zxid layout switch bookkeeping introduced by
 * ZOOKEEPER-2789: persisting a switch epoch, adopting one announced by a
 * leader, and the one-way / anti-downgrade guards that refuse to move or
 * forget a switch once wide-counter data has been written.
 *
 * <p>Introduced by Benedict Jin (asdf2014) for ZOOKEEPER-2789.
 */
public class QuorumPeerZxidLayoutTest extends ZKTestCase {

    private final List<FileTxnSnapLog> logFactories = new ArrayList<>();

    @AfterEach
    public void tearDown() throws IOException {
        // release the txn log handles so the @TempDir can be deleted on Windows
        for (FileTxnSnapLog logFactory : logFactories) {
            logFactory.close();
        }
    }

    /**
     * A bare QuorumPeer whose last logged zxid is {@code lastLoggedZxid}, used
     * to drive the "has this member already written wide-counter data?" guard.
     */
    private QuorumPeer newPeer(File tmpDir, long lastLoggedZxid) throws Exception {
        tmpDir.mkdirs();
        FileTxnSnapLog logFactory = new FileTxnSnapLog(tmpDir, tmpDir);
        logFactories.add(logFactory);
        logFactory.save(new DataTree(), new ConcurrentHashMap<>(), false);
        if (lastLoggedZxid != 0) {
            logFactory.append(new Request(1, 1, ZooDefs.OpCode.error,
                new TxnHeader(1, 1, lastLoggedZxid, 1, ZooDefs.OpCode.error), new ErrorTxn(1), lastLoggedZxid));
            logFactory.commit();
        }
        QuorumPeer peer = QuorumPeer.testingQuorumPeer();
        peer.setTxnFactory(logFactory);
        peer.setZKDatabase(new ZKDatabase(logFactory));
        peer.getLastLoggedZxid(); // force the database to load
        assertEquals(lastLoggedZxid, peer.getLastLoggedZxid());
        return peer;
    }

    private File switchEpochFile(File tmpDir) {
        // FileTxnSnapLog keeps the epoch files under the version-2 sub-directory.
        return new File(new File(tmpDir, "version-2"), QuorumPeer.ZXID_LAYOUT_SWITCH_EPOCH_FILENAME);
    }

    @Test
    public void testRecordZxidLayoutSwitchPersistsAndIsIdempotent(@TempDir File tmpDir) throws Exception {
        QuorumPeer peer = newPeer(tmpDir, 0);
        assertFalse(peer.getZxidLayoutState().isSwitched());

        peer.recordZxidLayoutSwitch(6);
        assertTrue(peer.getZxidLayoutState().isSwitched());
        assertEquals(6, peer.getZxidLayoutState().getSwitchEpoch());
        assertTrue(switchEpochFile(tmpDir).exists());

        // Recording the same switch epoch again is a no-op.
        peer.recordZxidLayoutSwitch(6);
        assertEquals(6, peer.getZxidLayoutState().getSwitchEpoch());
    }

    @Test
    public void testAdoptSwitchFromLeaderWhenNotSwitched(@TempDir File tmpDir) throws Exception {
        QuorumPeer peer = newPeer(tmpDir, 0);
        peer.adoptZxidLayoutSwitch(6);
        assertTrue(peer.getZxidLayoutState().isSwitched());
        assertEquals(6, peer.getZxidLayoutState().getSwitchEpoch());
        assertTrue(switchEpochFile(tmpDir).exists());
        // Adopting the same epoch again is a no-op.
        peer.adoptZxidLayoutSwitch(6);
        assertEquals(6, peer.getZxidLayoutState().getSwitchEpoch());
    }

    @Test
    public void testAdoptMovesUnusedSwitchEpoch(@TempDir File tmpDir) throws Exception {
        // No wide-counter data yet, so a switch epoch that never committed
        // anything may be moved when the next leader announces a different one
        // (the previous announcing leader failed before its epoch committed).
        QuorumPeer peer = newPeer(tmpDir, 0);
        peer.recordZxidLayoutSwitch(6);
        peer.adoptZxidLayoutSwitch(7);
        assertEquals(7, peer.getZxidLayoutState().getSwitchEpoch());
    }

    @Test
    public void testAdoptRefusesToMoveUsedSwitchEpoch(@TempDir File tmpDir) throws Exception {
        // This member already holds a zxid in the wide-counter layout of epoch
        // 6, so the switch is "used": a leader announcing a different switch
        // epoch means histories diverged (an unsupported downgrade), and
        // joining would corrupt the zxid order — it must be refused.
        long wideZxid = ZxidLayout.WIDE_COUNTER.makeZxid(6, 100);
        QuorumPeer peer = newPeer(tmpDir, wideZxid);
        peer.recordZxidLayoutSwitch(6);
        assertThrows(IOException.class, () -> peer.adoptZxidLayoutSwitch(7));
        // The recorded switch epoch is unchanged.
        assertEquals(6, peer.getZxidLayoutState().getSwitchEpoch());
    }

    @Test
    public void testAbandonUnusedSwitch(@TempDir File tmpDir) throws Exception {
        QuorumPeer peer = newPeer(tmpDir, 0);
        peer.recordZxidLayoutSwitch(6);
        assertTrue(switchEpochFile(tmpDir).exists());

        // The leader announced no switch and this member never wrote any
        // wide-counter data, so the unused switch is forgotten.
        peer.abandonUnusedZxidLayoutSwitch();
        assertFalse(peer.getZxidLayoutState().isSwitched());
        assertFalse(switchEpochFile(tmpDir).exists());
    }

    @Test
    public void testAbandonIsNoOpWhenNotSwitched(@TempDir File tmpDir) throws Exception {
        QuorumPeer peer = newPeer(tmpDir, 0);
        peer.abandonUnusedZxidLayoutSwitch();
        assertFalse(peer.getZxidLayoutState().isSwitched());
    }

    @Test
    public void testAbandonRefusesToForgetUsedSwitch(@TempDir File tmpDir) throws Exception {
        // Wide-counter data exists, so the switch cannot be forgotten: doing so
        // and then following a legacy-layout leader would break the zxid order.
        long wideZxid = ZxidLayout.WIDE_COUNTER.makeZxid(6, 100);
        QuorumPeer peer = newPeer(tmpDir, wideZxid);
        peer.recordZxidLayoutSwitch(6);
        assertThrows(IOException.class, peer::abandonUnusedZxidLayoutSwitch);
        assertTrue(peer.getZxidLayoutState().isSwitched());
    }

}
