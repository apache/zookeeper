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

package org.apache.zookeeper.server.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Adler32;
import java.util.zip.Checksum;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.persistence.FileHeader;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LogChopper}, including chopping a log whose zxids span
 * the switch to the wide-counter layout (ZOOKEEPER-2789).
 *
 * <p>Introduced by Benedict Jin (asdf2014) for ZOOKEEPER-2789.
 */
public class LogChopperTest extends ZKTestCase {

    private byte[] buildTxnLog(long... zxids) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
        new FileHeader(FileTxnLog.TXNLOG_MAGIC, 2, 1).serialize(oa, "fileheader");
        for (long zxid : zxids) {
            TxnHeader hdr = new TxnHeader(1, 1, zxid, 1, ZooDefs.OpCode.error);
            byte[] bytes = Util.marshallTxnEntry(hdr, new ErrorTxn(1), null);
            Checksum crc = new Adler32();
            crc.update(bytes, 0, bytes.length);
            oa.writeLong(crc.getValue(), "crcvalue");
            oa.writeBuffer(bytes, "txnEntry");
            oa.writeByte((byte) 'B', "EOR");
        }
        return baos.toByteArray();
    }

    @Test
    public void testChopLegacyLog() throws IOException {
        // An intra-epoch gap between (1, 2) and (1, 4) exercises the gap
        // diagnostics with the default legacy-only layout.
        byte[] log = buildTxnLog(
            ZxidUtils.makeZxid(1, 1),
            ZxidUtils.makeZxid(1, 2),
            ZxidUtils.makeZxid(1, 4),
            ZxidUtils.makeZxid(2, 1));
        ByteArrayOutputStream chopped = new ByteArrayOutputStream();
        assertTrue(LogChopper.chop(new ByteArrayInputStream(log), chopped, ZxidUtils.makeZxid(1, 4)));
    }

    @Test
    public void testChopAcrossZxidLayoutSwitch() throws IOException {
        // The log spans the switch to the wide-counter layout at epoch 6:
        // legacy zxids of epoch 5, then wide-counter zxids of epoch 6, with
        // gaps on both sides of the boundary so the diagnostics decompose
        // zxids of both layouts.
        ZxidLayoutState layoutState = new ZxidLayoutState();
        layoutState.switchAt(6);
        byte[] log = buildTxnLog(
            ZxidUtils.makeZxid(5, 1),
            ZxidUtils.makeZxid(5, 3),
            ZxidLayout.WIDE_COUNTER.makeZxid(6, 1),
            ZxidLayout.WIDE_COUNTER.makeZxid(6, 3),
            ZxidLayout.WIDE_COUNTER.makeZxid(7, 1));
        ByteArrayOutputStream chopped = new ByteArrayOutputStream();
        assertTrue(LogChopper.chop(
            new ByteArrayInputStream(log), chopped, ZxidLayout.WIDE_COUNTER.makeZxid(6, 3), layoutState));
    }

    @Test
    public void testChopReturnsFalseWhenZxidMissing() throws IOException {
        byte[] log = buildTxnLog(
            ZxidUtils.makeZxid(1, 1),
            ZxidUtils.makeZxid(1, 2));
        ByteArrayOutputStream chopped = new ByteArrayOutputStream();
        // The log ends before any zxid larger than the requested one shows
        // up, so nothing is chopped.
        assertFalse(LogChopper.chop(new ByteArrayInputStream(log), chopped, ZxidUtils.makeZxid(1, 5)));
    }

}
