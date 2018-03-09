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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.server.TraceFormatter;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

import static org.apache.zookeeper.server.persistence.FileTxnLog.TXNLOG_MAGIC;

public class TxnLogTool implements Closeable {

    public class TxnLogToolException extends Exception {
        private static final long serialVersionUID = 1L;
        private int exitCode;

        TxnLogToolException(int exitCode, String message, Object... params) {
            super(String.format(message, params));
            this.exitCode = exitCode;
        }

        int getExitCode() {
            return exitCode;
        }
    }

    public class TxnLogToolParseException extends TxnLogToolException {
        private static final long serialVersionUID = 1L;
        private Options options;

        TxnLogToolParseException(Options options, int exitCode, String message, Object... params) {
            super(exitCode, message, params);
            this.options = options;
        }

        Options getOptions() {
            return options;
        }
    }

    private File txnLogFile;
    private boolean recoveryMode = false;
    private boolean verbose = false;
    private FileInputStream txnFis;
    private BinaryInputArchive logStream;
    private boolean initialized = false;

    // Recovery mode
    private int crcFixed = 0;
    private FileOutputStream recoveryFos;
    private BinaryOutputArchive recoveryOa;
    private File recoveryLogFile;

    /**
     * @param args Command line arguments
     */
    public static void main(String[] args) throws Exception {
        try (final TxnLogTool logf = new TxnLogTool()) {
            logf.run(args);
        } catch (TxnLogToolParseException e) {
            System.err.println(e.getMessage() + "\n");
            printHelpAndExit(e.getExitCode(), e.getOptions());
        } catch (TxnLogToolException e) {
            System.err.println(e.getMessage());
            System.exit(e.getExitCode());
        }
    }

    public void run(String[] args) throws Exception {
        parseCommandLine(args);
        dump();
        printStat();
    }

    public void init(boolean recoveryMode, boolean verbose, String txnLogFileName)
            throws FileNotFoundException, TxnLogToolException {
        this.recoveryMode = recoveryMode;
        this.verbose = verbose;
        txnLogFile = new File(txnLogFileName);
        if (!txnLogFile.exists() || !txnLogFile.canRead()) {
            throw new TxnLogToolException(1, "File doesn't exist or not readable: %s", txnLogFile);
        }
        if (recoveryMode) {
            recoveryLogFile = new File(txnLogFile.toString() + ".fixed");
            if (recoveryLogFile.exists()) {
                throw new TxnLogToolException(1, "Recovery file %s already exists or not writable", recoveryLogFile);
            }
        }

        openTxnLogFile();
        if (recoveryMode) {
            openRecoveryFile();
        }

        initialized = true;
    }

    public void dump() throws Exception {
        if (!initialized) {
            throw new TxnLogToolException(1, "TxnLogTool is not yet initialized");
        }
        crcFixed = 0;

        FileHeader fhdr = new FileHeader();
        fhdr.deserialize(logStream, "fileheader");
        if (fhdr.getMagic() != TXNLOG_MAGIC) {
            throw new TxnLogToolException(2, "Invalid magic number for %s", txnLogFile.getName());
        }
        System.out.println("ZooKeeper Transactional Log File with dbid "
                + fhdr.getDbid() + " txnlog format version "
                + fhdr.getVersion());

        if (recoveryMode) {
            fhdr.serialize(recoveryOa, "fileheader");
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
                    crcValue = crc.getValue();
                    printTxn(bytes, "CRC FIXED");
                    ++crcFixed;
                } else {
                    printTxn(bytes, "CRC ERROR");
                }
            }
            if (!recoveryMode || verbose) {
                printTxn(bytes);
            }
            if (logStream.readByte("EOR") != 'B') {
                throw new TxnLogToolException(1, "Last transaction was partial.");
            }
            if (recoveryMode) {
                recoveryOa.writeLong(crcValue, "crcvalue");
                recoveryOa.writeBuffer(bytes, "txnEntry");
                recoveryOa.writeByte((byte)'B', "EOR");
                // add padding

            }
            count++;
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

    private void parseCommandLine(String[] args) throws TxnLogToolException, FileNotFoundException {
        CommandLineParser parser = new PosixParser();
        Options options = new Options();

        Option helpOpt = new Option("h", "help", false, "Print help message");
        options.addOption(helpOpt);

        Option recoverOpt = new Option("r", "recover", false, "Recovery mode. Re-calculate CRC for broken entries.");
        options.addOption(recoverOpt);

        Option quietOpt = new Option("v", "verbose", false, "Be verbose in recovery mode: print all entries, not just fixed ones.");
        options.addOption(quietOpt);

        Option dumpOpt = new Option("d", "dump", false, "Dump mode. Dump all entries of the log file. (this is the default)");
        options.addOption(dumpOpt);

        try {
            CommandLine cli = parser.parse(options, args);
            if (cli.hasOption("help")) {
                printHelpAndExit(0, options);
            }
            if (cli.getArgs().length < 1) {
                printHelpAndExit(1, options);
            }
            init(cli.hasOption("recover"), cli.hasOption("verbose"), cli.getArgs()[0]);
        } catch (ParseException e) {
            throw new TxnLogToolParseException(options, 1, e.getMessage());
        }
    }

    private static void printHelpAndExit(int exitCode, Options options) {
        HelpFormatter help = new HelpFormatter();
        help.printHelp(120,"TxnLogTool [-dhrv] <txn_log_file_name>", "", options, "");
        System.exit(exitCode);
    }

    private void printStat() {
        if (recoveryMode) {
            System.out.printf("Recovery file %s has been written with %d fixed CRC error(s)\n", recoveryLogFile, crcFixed);
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
