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

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.zip.Adler32;
import java.util.zip.Checksum;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.Record;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.ZKUtil;
import org.apache.zookeeper.server.persistence.FileHeader;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @deprecated deprecated in 3.5.5, use @see TxnLogToolkit instead
 */
@Deprecated
@InterfaceAudience.Public
public class LogFormatter {

    private static final Logger LOG = LoggerFactory.getLogger(LogFormatter.class);

    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("USAGE: LogFormatter log_file");
            ServiceUtils.requestSystemExit(ExitCode.INVALID_INVOCATION.getValue());
        }

        String error = ZKUtil.validateFileInput(args[0]);
        if (null != error) {
            System.err.println(error);
            ServiceUtils.requestSystemExit(ExitCode.INVALID_INVOCATION.getValue());
        }

        FileInputStream fis = new FileInputStream(args[0]);
        BinaryInputArchive logStream = BinaryInputArchive.getArchive(fis);
        FileHeader fhdr = new FileHeader();
        fhdr.deserialize(logStream, "fileheader");

        if (fhdr.getMagic() != FileTxnLog.TXNLOG_MAGIC) {
            System.err.println("Invalid magic number for " + args[0]);
            ServiceUtils.requestSystemExit(ExitCode.INVALID_INVOCATION.getValue());
        }
        System.out.println("ZooKeeper Transactional Log File with dbid "
                           + fhdr.getDbid()
                           + " txnlog format version "
                           + fhdr.getVersion());

        // enable digest
        ZooKeeperServer.setDigestEnabled(true);

        int count = 0;
        while (true) {
            long crcValue;
            byte[] bytes;
            try {
                crcValue = logStream.readLong("crcvalue");

                bytes = logStream.readBuffer("txnEntry");
            } catch (EOFException e) {
                System.out.println("EOF reached after " + count + " txns.");
                return;
            }
            if (bytes.length == 0) {
                // Since we preallocate, we define EOF to be an
                // empty transaction
                System.out.println("EOF reached after " + count + " txns.");
                return;
            }
            Checksum crc = new Adler32();
            crc.update(bytes, 0, bytes.length);
            if (crcValue != crc.getValue()) {
                throw new IOException("CRC doesn't match " + crcValue + " vs " + crc.getValue());
            }
            TxnLogEntry entry = SerializeUtils.deserializeTxn(bytes);
            TxnHeader hdr = entry.getHeader();
            Record txn = entry.getTxn();
            TxnDigest digest = entry.getDigest();
            System.out.println(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.LONG).format(new Date(hdr.getTime()))
                + " session 0x" + Long.toHexString(hdr.getClientId())
                + " cxid 0x" + Long.toHexString(hdr.getCxid())
                + " zxid 0x" + Long.toHexString(hdr.getZxid())
                + " " + Request.op2String(hdr.getType())
                + " " + txn
                + " " + digest);
            if (logStream.readByte("EOR") != 'B') {
                LOG.error("Last transaction was partial.");
                throw new EOFException("Last transaction was partial.");
            }
            count++;
        }
    }

}
