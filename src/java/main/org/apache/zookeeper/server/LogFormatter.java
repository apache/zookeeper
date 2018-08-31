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

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.Record;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.server.persistence.FileHeader;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.CreateContainerTxn;
import org.apache.zookeeper.txn.CreateTTLTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnHeader;

@InterfaceAudience.Public
public class LogFormatter {
    private static final Logger LOG = LoggerFactory.getLogger(LogFormatter.class);

    /**
     * get transaction log data string with node's data as a string
     * @param txn
     * @return
     */
    private static String getDataStrFromTxn(Record txn) {
        StringBuilder txnData = new StringBuilder();
        if (txn == null) {
            return txnData.toString();
        }
        if (txn instanceof CreateTxn) {
            CreateTxn createTxn = ((CreateTxn) txn);
            txnData.append(createTxn.getPath() + "," + new String(createTxn.getData()))
                   .append("," + createTxn.getAcl() + "," + createTxn.getEphemeral())
                   .append("," + createTxn.getParentCVersion());
        } else if (txn instanceof SetDataTxn) {
            SetDataTxn setDataTxn = ((SetDataTxn) txn);
            txnData.append(setDataTxn.getPath() + "," + new String(setDataTxn.getData()))
                   .append("," + setDataTxn.getVersion());
        } else if (txn instanceof CreateContainerTxn) {
            CreateContainerTxn createContainerTxn = ((CreateContainerTxn) txn);
            txnData.append(createContainerTxn.getPath() + "," + new String(createContainerTxn.getData()))
                   .append("," + createContainerTxn.getAcl() + "," + createContainerTxn.getParentCVersion());
        } else if (txn instanceof CreateTTLTxn) {
            CreateTTLTxn createTTLTxn = ((CreateTTLTxn) txn);
            txnData.append(createTTLTxn.getPath() + "," + new String(createTTLTxn.getData()))
                   .append("," + createTTLTxn.getAcl() + "," + createTTLTxn.getParentCVersion())
                   .append("," + createTTLTxn.getTtl());
        } else {
            txnData.append(txn.toString());
        }

        return txnData.toString();
    }
    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        CommandLine cl;
        Options options = new Options();
        PosixParser parser = new PosixParser();
        String[] cmdArgs;

        options.addOption("s", false, "showdata");
        cl = parser.parse(options, args);
        cmdArgs = cl.getArgs();

        if (cmdArgs.length != 1) {
            System.err.println("USAGE: LogFormatter log_file");
            System.err.println("USAGE: LogFormatter [-s] log_file to print transaction data as a string");
            System.exit(ExitCode.INVALID_INVOCATION.getValue());
        }
        FileInputStream fis = new FileInputStream(cmdArgs[0]);
        BinaryInputArchive logStream = BinaryInputArchive.getArchive(fis);
        FileHeader fhdr = new FileHeader();
        fhdr.deserialize(logStream, "fileheader");

        if (fhdr.getMagic() != FileTxnLog.TXNLOG_MAGIC) {
            System.err.println(String.format("Invalid magic number for %s", cmdArgs[0]));
            System.exit(ExitCode.INVALID_INVOCATION.getValue());
        }
        System.out.println(String.format("%s with dbid %d txnlog format version %d\n",
                "ZooKeeper Transactional Log File", fhdr.getDbid(), fhdr.getVersion()));
        DateFormat dataFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.LONG);

        int count = 0;
        while (true) {
            long crcValue;
            byte[] bytes;
            try {
                crcValue = logStream.readLong("crcvalue");

                bytes = logStream.readBuffer("txnEntry");
            } catch (EOFException e) {
                System.out.println(String.format("EOF reached after %d txns.", count));
                return;
            }
            if (bytes.length == 0) {
                // Since we preallocate, we define EOF to be an
                // empty transaction
                System.out.println(String.format("EOF reached after %d txns.", count));
                return;
            }
            Checksum crc = new Adler32();
            crc.update(bytes, 0, bytes.length);
            if (crcValue != crc.getValue()) {
                throw new IOException(String.format("CRC doesn't match %s vs %s", crcValue, crc.getValue()));
            }
            TxnHeader hdr = new TxnHeader();
            Record txn = SerializeUtils.deserializeTxn(bytes, hdr);

            String txnStr = (txn != null) ? txn.toString() : "";
            if (cl.hasOption("s")) {
                txnStr = getDataStrFromTxn(txn);
            }

            System.out.println(String.format("%s session 0x%s cxid 0x%s zxid 0x%s %s %s\n",
                    dataFormat.format(new Date(hdr.getTime())), Long.toHexString(hdr.getClientId()),
                    Long.toHexString(hdr.getCxid()), Long.toHexString(hdr.getZxid()),
                    TraceFormatter.op2String(hdr.getType()), txnStr));

            if (logStream.readByte("EOR") != 'B') {
                LOG.error("Last transaction was partial.");
                throw new EOFException("Last transaction was partial.");
            }
            count++;
        }
    }
}
