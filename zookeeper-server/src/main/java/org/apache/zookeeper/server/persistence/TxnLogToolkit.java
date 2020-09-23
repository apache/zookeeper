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

package org.apache.zookeeper.server.persistence;

import static org.apache.zookeeper.server.persistence.FileTxnLog.TXNLOG_MAGIC;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Scanner;
import java.util.zip.Adler32;
import java.util.zip.Checksum;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.TxnLogEntry;
import org.apache.zookeeper.server.util.LogChopper;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.CreateContainerTxn;
import org.apache.zookeeper.txn.CreateTTLTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnHeader;
import org.apache.zookeeper.util.ServiceUtils;

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
        private Options options;

        TxnLogToolkitParseException(Options options, int exitCode, String message, Object... params) {
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

    // Recovery mode
    private int crcFixed = 0;
    private FileOutputStream recoveryFos;
    private BinaryOutputArchive recoveryOa;
    private File recoveryLogFile;
    private FilePadding filePadding = new FilePadding();
    private boolean force = false;

    // chop mode
    private long zxid = -1L;

    /**
     * @param args Command line arguments
     */
    public static void main(String[] args) throws Exception {
        try (final TxnLogToolkit lt = parseCommandLine(args)) {
            if (lt.isDumpMode()) {
                lt.dump(new Scanner(System.in));
                lt.printStat();
            } else {
                lt.chop();
            }
        } catch (TxnLogToolkitParseException e) {
            System.err.println(e.getMessage() + "\n");
            printHelpAndExit(e.getExitCode(), e.getOptions());
        } catch (TxnLogToolkitException e) {
            System.err.println(e.getMessage());
            ServiceUtils.requestSystemExit(e.getExitCode());
        }
    }

    public TxnLogToolkit(
        boolean recoveryMode,
        boolean verbose,
        String txnLogFileName,
        boolean force) throws FileNotFoundException, TxnLogToolkitException {
        this.recoveryMode = recoveryMode;
        this.verbose = verbose;
        this.force = force;
        txnLogFile = loadTxnFile(txnLogFileName);
        if (recoveryMode) {
            recoveryLogFile = new File(txnLogFile.toString() + ".fixed");
            if (recoveryLogFile.exists()) {
                throw new TxnLogToolkitException(
                    ExitCode.UNEXPECTED_ERROR.getValue(),
                    "Recovery file %s already exists or not writable",
                    recoveryLogFile);
            }
        }

        openTxnLogFile();
        if (recoveryMode) {
            openRecoveryFile();
        }
    }

    public TxnLogToolkit(String txnLogFileName, String zxidName) throws TxnLogToolkitException {
        txnLogFile = loadTxnFile(txnLogFileName);
        zxid = Long.decode(zxidName);
    }

    private File loadTxnFile(String txnLogFileName) throws TxnLogToolkitException {
        File logFile = new File(txnLogFileName);
        if (!logFile.exists() || !logFile.canRead()) {
            throw new TxnLogToolkitException(
                ExitCode.UNEXPECTED_ERROR.getValue(),
                "File doesn't exist or not readable: %s",
                logFile);
        }
        return logFile;
    }

    public void dump(Scanner scanner) throws Exception {
        crcFixed = 0;

        FileHeader fhdr = new FileHeader();
        fhdr.deserialize(logStream, "fileheader");
        if (fhdr.getMagic() != TXNLOG_MAGIC) {
            throw new TxnLogToolkitException(
                ExitCode.INVALID_INVOCATION.getValue(),
                "Invalid magic number for %s",
                txnLogFile.getName());
        }
        System.out.println("ZooKeeper Transactional Log File with dbid " + fhdr.getDbid()
                           + " txnlog format version " + fhdr.getVersion());

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
                throw new TxnLogToolkitException(ExitCode.UNEXPECTED_ERROR.getValue(), "Last transaction was partial.");
            }
            if (recoveryMode) {
                filePadding.padFile(recoveryFos.getChannel());
                recoveryOa.writeLong(crcValue, "crcvalue");
                recoveryOa.writeBuffer(bytes, "txnEntry");
                recoveryOa.writeByte((byte) 'B', "EOR");
            }
            count++;
        }
    }

    public void chop() {
        File targetFile = new File(txnLogFile.getParentFile(), txnLogFile.getName() + ".chopped" + zxid);
        try (InputStream is = new BufferedInputStream(new FileInputStream(txnLogFile));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(targetFile))) {
            if (!LogChopper.chop(is, os, zxid)) {
                throw new TxnLogToolkitException(
                    ExitCode.INVALID_INVOCATION.getValue(),
                    "Failed to chop %s",
                    txnLogFile.getName());
            }
        } catch (Exception e) {
            System.out.println("Got exception: " + e.getMessage());
        }
    }

    public boolean isDumpMode() {
        return zxid < 0;
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
                throw new TxnLogToolkitException(ExitCode.EXECUTION_FINISHED.getValue(), "Recovery aborted.");
            }
        }
    }

    private void printTxn(byte[] bytes) throws IOException {
        printTxn(bytes, "");
    }

    private void printTxn(byte[] bytes, String prefix) throws IOException {
        TxnLogEntry logEntry = SerializeUtils.deserializeTxn(bytes);
        TxnHeader hdr = logEntry.getHeader();
        Record txn = logEntry.getTxn();
        String txnStr = getFormattedTxnStr(txn);
        String txns = String.format(
            "%s session 0x%s cxid 0x%s zxid 0x%s %s %s",
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.LONG).format(new Date(hdr.getTime())),
            Long.toHexString(hdr.getClientId()),
            Long.toHexString(hdr.getCxid()),
            Long.toHexString(hdr.getZxid()),
                Request.op2String(hdr.getType()),
            txnStr);
        if (prefix != null && !"".equals(prefix.trim())) {
            System.out.print(prefix + " - ");
        }
        if (txns.endsWith("\n")) {
            System.out.print(txns);
        } else {
            System.out.println(txns);
        }
    }

    /**
     * get the formatted string from the txn.
     * @param txn transaction log data
     * @return the formatted string
     */
    private static String getFormattedTxnStr(Record txn) throws IOException {
        StringBuilder txnData = new StringBuilder();
        if (txn == null) {
            return txnData.toString();
        }
        if (txn instanceof CreateTxn) {
            CreateTxn createTxn = ((CreateTxn) txn);
            txnData.append(createTxn.getPath() + "," + checkNullToEmpty(createTxn.getData()))
                   .append("," + createTxn.getAcl() + "," + createTxn.getEphemeral())
                   .append("," + createTxn.getParentCVersion());
        } else if (txn instanceof SetDataTxn) {
            SetDataTxn setDataTxn = ((SetDataTxn) txn);
            txnData.append(setDataTxn.getPath() + "," + checkNullToEmpty(setDataTxn.getData()))
                   .append("," + setDataTxn.getVersion());
        } else if (txn instanceof CreateContainerTxn) {
            CreateContainerTxn createContainerTxn = ((CreateContainerTxn) txn);
            txnData.append(createContainerTxn.getPath() + "," + checkNullToEmpty(createContainerTxn.getData()))
                   .append("," + createContainerTxn.getAcl() + "," + createContainerTxn.getParentCVersion());
        } else if (txn instanceof CreateTTLTxn) {
            CreateTTLTxn createTTLTxn = ((CreateTTLTxn) txn);
            txnData.append(createTTLTxn.getPath() + "," + checkNullToEmpty(createTTLTxn.getData()))
                   .append("," + createTTLTxn.getAcl() + "," + createTTLTxn.getParentCVersion())
                   .append("," + createTTLTxn.getTtl());
        } else if (txn instanceof MultiTxn) {
            MultiTxn multiTxn = ((MultiTxn) txn);
            List<Txn> txnList = multiTxn.getTxns();
            for (int i = 0; i < txnList.size(); i++) {
                Txn t = txnList.get(i);
                if (i == 0) {
                    txnData.append(Request.op2String(t.getType()) + ":" + checkNullToEmpty(t.getData()));
                } else {
                    txnData.append(";" + Request.op2String(t.getType()) + ":" + checkNullToEmpty(t.getData()));
                }
            }
        } else {
            txnData.append(txn.toString());
        }

        return txnData.toString();
    }

    private static String checkNullToEmpty(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return "";
        }

        return new String(data, StandardCharsets.UTF_8);
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
        CommandLineParser parser = new DefaultParser();
        Options options = new Options();

        Option helpOpt = new Option("h", "help", false, "Print help message");
        options.addOption(helpOpt);

        Option recoverOpt = new Option("r", "recover", false, "Recovery mode. Re-calculate CRC for broken entries.");
        options.addOption(recoverOpt);

        Option quietOpt = new Option("v", "verbose", false, "Be verbose in recovery mode: print all entries, not just fixed ones.");
        options.addOption(quietOpt);

        Option dumpOpt = new Option("d", "dump", false, "Dump mode. Dump all entries of the log file with printing the content of a nodepath (default)");
        options.addOption(dumpOpt);

        Option forceOpt = new Option("y", "yes", false, "Non-interactive mode: repair all CRC errors without asking");
        options.addOption(forceOpt);

        // Chop mode options
        Option chopOpt = new Option("c", "chop", false, "Chop mode. Chop txn file to a zxid.");
        Option zxidOpt = new Option("z", "zxid", true, "Used with chop. Zxid to which to chop.");
        options.addOption(chopOpt);
        options.addOption(zxidOpt);

        try {
            CommandLine cli = parser.parse(options, args);
            if (cli.hasOption("help")) {
                printHelpAndExit(0, options);
            }
            if (cli.getArgs().length < 1) {
                printHelpAndExit(1, options);
            }
            if (cli.hasOption("chop") && cli.hasOption("zxid")) {
                return new TxnLogToolkit(cli.getArgs()[0], cli.getOptionValue("zxid"));
            }
            return new TxnLogToolkit(cli.hasOption("recover"), cli.hasOption("verbose"), cli.getArgs()[0], cli.hasOption("yes"));
        } catch (ParseException e) {
            throw new TxnLogToolkitParseException(options, ExitCode.UNEXPECTED_ERROR.getValue(), e.getMessage());
        }
    }

    private static void printHelpAndExit(int exitCode, Options options) {
        HelpFormatter help = new HelpFormatter();
        help.printHelp(120, "TxnLogToolkit [-dhrvc] <txn_log_file_name> (-z <zxid>)", "", options, "");
        ServiceUtils.requestSystemExit(exitCode);
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
