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

package org.apache.zookeeper.server.persistence;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.server.TraceFormatter;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnHeader;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Scanner;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

import static org.apache.zookeeper.server.persistence.FileTxnLog.TXNLOG_MAGIC;
import static org.apache.zookeeper.server.persistence.TxnLogToolkitCliParser.printHelpAndExit;

public class TxnLogToolkit implements Closeable {

    static class TxnLogToolkitException extends Exception {
        private static final long serialVersionUID = 1L;
        private int exitCode;

        TxnLogToolkitException(int exitCode, String message, Object... params) {
            super(String.format(message, params));
            this.exitCode = exitCode;
        }

        int getExitCode() {
            return exitCode;
        }
    }

    static class TxnLogToolkitParseException extends TxnLogToolkitException {
        private static final long serialVersionUID = 1L;

        TxnLogToolkitParseException(int exitCode, String message, Object... params) {
            super(exitCode, message, params);
        }
    }

    private File txnLogFile;
    private boolean recoveryMode = false;
    private boolean verbose = false;
    private FileInputStream txnFis;
    private BinaryInputArchive logStream;

    // Recovery mode
    private int crcFixed = 0;
    private FileOutputStream recoveryFos;
    private BinaryOutputArchive recoveryOa;
    private File recoveryLogFile;
    private FilePadding filePadding = new FilePadding();
    private boolean force = false;

    /**
     * @param args Command line arguments
     */
    public static void main(String[] args) throws Exception {
        final TxnLogToolkit lt = parseCommandLine(args);
        try {
            lt.dump(new Scanner(System.in));
            lt.printStat();
        } catch (TxnLogToolkitParseException e) {
            System.err.println(e.getMessage() + "\n");
            printHelpAndExit(e.getExitCode());
        } catch (TxnLogToolkitException e) {
            System.err.println(e.getMessage());
            System.exit(e.getExitCode());
        } finally {
            lt.close();
        }
    }

    public TxnLogToolkit(boolean recoveryMode, boolean verbose, String txnLogFileName, boolean force)
            throws FileNotFoundException, TxnLogToolkitException {
        this.recoveryMode = recoveryMode;
        this.verbose = verbose;
        this.force = force;
        txnLogFile = new File(txnLogFileName);
        if (!txnLogFile.exists() || !txnLogFile.canRead()) {
            throw new TxnLogToolkitException(1, "File doesn't exist or not readable: %s", txnLogFile);
        }
        if (recoveryMode) {
            recoveryLogFile = new File(txnLogFile.toString() + ".fixed");
            if (recoveryLogFile.exists()) {
                throw new TxnLogToolkitException(1, "Recovery file %s already exists or not writable", recoveryLogFile);
            }
        }

        openTxnLogFile();
        if (recoveryMode) {
            openRecoveryFile();
        }
    }

    public void dump(Scanner scanner) throws Exception {
        crcFixed = 0;

        FileHeader fhdr = new FileHeader();
        fhdr.deserialize(logStream, "fileheader");
        if (fhdr.getMagic() != TXNLOG_MAGIC) {
            throw new TxnLogToolkitException(2, "Invalid magic number for %s", txnLogFile.getName());
        }
        System.out.println("ZooKeeper Transactional Log File with dbid "
                + fhdr.getDbid() + " txnlog format version "
                + fhdr.getVersion());

        if (recoveryMode) {
            fhdr.serialize(recoveryOa, "fileheader");
            recoveryFos.flush();
            filePadding.setCurrentSize(recoveryFos.getChannel().position());
        }

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
                if (recoveryMode) {
                    if (!force) {
                        printTxn(bytes, "CRC ERROR");
                        if (askForFix(scanner)) {
                            crcValue = crc.getValue();
                            ++crcFixed;
                        }
                    } else {
                        crcValue = crc.getValue();
                        printTxn(bytes, "CRC FIXED");
                        ++crcFixed;
                    }
                } else {
                    printTxn(bytes, "CRC ERROR");
                }
            }
            if (!recoveryMode || verbose) {
                printTxn(bytes);
            }
            if (logStream.readByte("EOR") != 'B') {
                throw new TxnLogToolkitException(1, "Last transaction was partial.");
            }
            if (recoveryMode) {
                filePadding.padFile(recoveryFos.getChannel());
                recoveryOa.writeLong(crcValue, "crcvalue");
                recoveryOa.writeBuffer(bytes, "txnEntry");
                recoveryOa.writeByte((byte)'B', "EOR");
            }
            count++;
        }
    }

    private boolean askForFix(Scanner scanner) throws TxnLogToolkitException {
        while (true) {
            System.out.print("Would you like to fix it (Yes/No/Abort) ? ");
            char answer = Character.toUpperCase(scanner.next().charAt(0));
            switch (answer) {
                case 'Y':
                    return true;
                case 'N':
                    return false;
                case 'A':
                    throw new TxnLogToolkitException(0, "Recovery aborted.");
            }
        }
    }

    private void printTxn(byte[] bytes) throws IOException {
        printTxn(bytes, "");
    }

    private void printTxn(byte[] bytes, String prefix) throws IOException {
        TxnHeader hdr = new TxnHeader();
        Record txn = SerializeUtils.deserializeTxn(bytes, hdr);
        String txns = String.format("%s session 0x%s cxid 0x%s zxid 0x%s %s %s",
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.LONG).format(new Date(hdr.getTime())),
                Long.toHexString(hdr.getClientId()),
                Long.toHexString(hdr.getCxid()),
                Long.toHexString(hdr.getZxid()),
                TraceFormatter.op2String(hdr.getType()),
                txn);
        if (prefix != null && !"".equals(prefix.trim())) {
            System.out.print(prefix + " - ");
        }
        if (txns.endsWith("\n")) {
            System.out.print(txns);
        } else {
            System.out.println(txns);
        }
    }

    private void openTxnLogFile() throws FileNotFoundException {
        txnFis = new FileInputStream(txnLogFile);
        logStream = BinaryInputArchive.getArchive(txnFis);
    }

    private void closeTxnLogFile() throws IOException {
        if (txnFis != null) {
            txnFis.close();
        }
    }

    private void openRecoveryFile() throws FileNotFoundException {
        recoveryFos = new FileOutputStream(recoveryLogFile);
        recoveryOa = BinaryOutputArchive.getArchive(recoveryFos);
    }

    private void closeRecoveryFile() throws IOException {
        if (recoveryFos != null) {
            recoveryFos.close();
        }
    }

    private static TxnLogToolkit parseCommandLine(String[] args) throws TxnLogToolkitException, FileNotFoundException {
        TxnLogToolkitCliParser parser = new TxnLogToolkitCliParser();
        parser.parse(args);
        return new TxnLogToolkit(parser.isRecoveryMode(), parser.isVerbose(), parser.getTxnLogFileName(), parser.isForce());
    }

    private void printStat() {
        if (recoveryMode) {
            System.out.printf("Recovery file %s has been written with %d fixed CRC error(s)%n", recoveryLogFile, crcFixed);
        }
    }

    @Override
    public void close() throws IOException {
        if (recoveryMode) {
            closeRecoveryFile();
        }
        closeTxnLogFile();
    }
}
