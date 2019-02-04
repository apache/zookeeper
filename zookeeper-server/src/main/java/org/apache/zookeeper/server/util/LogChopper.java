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
package org.apache.zookeeper.server.util;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.persistence.FileHeader;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.txn.TxnHeader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

/**
 * this class will chop the log at the specified zxid
 */
@InterfaceAudience.Public
public class LogChopper {
    public static void main(String args[]) {
        ExitCode rc = ExitCode.INVALID_INVOCATION;
        if (args.length != 3) {
            System.out.println("Usage: LogChopper zxid_to_chop_to txn_log_to_chop chopped_filename");
            System.out.println("    this program will read the txn_log_to_chop file and copy all the transactions");
            System.out.println("    from it up to (and including) the given zxid into chopped_filename.");
            System.exit(rc.getValue());
        }
        String txnLog = args[1];
        String choppedLog = args[2];

        try (
            InputStream is = new BufferedInputStream(new FileInputStream(txnLog));
            OutputStream os = new BufferedOutputStream(new FileOutputStream(choppedLog))
        ) {
            long zxid = Long.decode(args[0]);

            if (chop(is, os, zxid)) {
                rc = ExitCode.EXECUTION_FINISHED;
            }
        } catch (Exception e) {
            System.out.println("Got exception: " + e.getMessage());
        }
        System.exit(rc.getValue());
    }

    public static boolean chop(InputStream is, OutputStream os, long zxid) throws IOException {
        BinaryInputArchive logStream = BinaryInputArchive.getArchive(is);
        BinaryOutputArchive choppedStream = BinaryOutputArchive.getArchive(os);
        FileHeader fhdr = new FileHeader();
        fhdr.deserialize(logStream, "fileheader");

        if (fhdr.getMagic() != FileTxnLog.TXNLOG_MAGIC) {
            System.err.println("Invalid magic number in txn log file");
            return false;
        }
        System.out.println("ZooKeeper Transactional Log File with dbid "
                + fhdr.getDbid() + " txnlog format version "
                + fhdr.getVersion());

        fhdr.serialize(choppedStream, "fileheader");
        int count = 0;
        boolean hasZxid = false;
        long previousZxid = -1;
        while (true) {
            long crcValue;
            byte[] bytes;
            try {
                crcValue = logStream.readLong("crcvalue");

                bytes = logStream.readBuffer("txnEntry");
            } catch (EOFException e) {
                System.out.println("EOF reached after " + count + " txns.");
                // returning false because nothing was chopped
                return false;
            }
            if (bytes.length == 0) {
                // Since we preallocate, we define EOF to be an
                // empty transaction
                System.out.println("EOF reached after " + count + " txns.");
                // returning false because nothing was chopped
                return false;
            }

            Checksum crc = new Adler32();
            crc.update(bytes, 0, bytes.length);
            if (crcValue != crc.getValue()) {
                throw new IOException("CRC doesn't match " + crcValue +
                        " vs " + crc.getValue());
            }
            TxnHeader hdr = new TxnHeader();
            Record txn = SerializeUtils.deserializeTxn(bytes, hdr);
            if (logStream.readByte("EOR") != 'B') {
                System.out.println("Last transaction was partial.");
                throw new EOFException("Last transaction was partial.");
            }

            final long txnZxid = hdr.getZxid();
            if (txnZxid == zxid) {
                hasZxid = true;
            }

            // logging the gap to make the inconsistency investigation easier
            if (previousZxid != -1 && txnZxid != previousZxid + 1) {
                long txnEpoch = ZxidUtils.getEpochFromZxid(txnZxid);
                long txnCounter = ZxidUtils.getCounterFromZxid(txnZxid);
                long previousEpoch = ZxidUtils.getEpochFromZxid(previousZxid);
                if (txnEpoch == previousEpoch) {
                    System.out.println(
                            String.format("There is intra-epoch gap between %x and %x",
                                    previousZxid, txnZxid));
                } else if (txnCounter != 1) {
                    System.out.println(
                        String.format("There is inter-epoch gap between %x and %x",
                                previousZxid, txnZxid));
                }
            }
            previousZxid = txnZxid;

            if (txnZxid > zxid) {
                if (count == 0 || !hasZxid) {
                    System.out.println(String.format("This log does not contain zxid %x", zxid));
                    return false;
                }
                System.out.println(String.format("Chopping at %x new log has %d records", zxid, count));
                return true;
            }

            choppedStream.writeLong(crcValue, "crcvalue");
            choppedStream.writeBuffer(bytes, "txnEntry");
            choppedStream.writeByte((byte)'B', "EOR");

            count++;
        }
    }
}
