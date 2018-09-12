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
import org.apache.zookeeper.txn.CreateContainerTxn;
import org.apache.zookeeper.txn.CreateTTLTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.SetDataTxn;
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

    /**
     * @param args Command line arguments
     */
    public static void main(String[] args) throws Exception {
        try (final TxnLogToolkit lt = parseCommandLine(args)) {
            lt.dump(new Scanner(System.in));
            lt.printStat();
        } catch (TxnLogToolkitParseException e) {
            System.err.println(e.getMessage() + "\n");
            printHelpAndExit(e.getExitCode(), e.getOptions());
        } catch (TxnLogToolkitException e) {
            System.err.println(e.getMessage());
            System.exit(e.getExitCode());
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
        String txnStr = getDataStrFromTxn(txn);
        String txns = String.format("%s session 0x%s cxid 0x%s zxid 0x%s %s %s",
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.LONG).format(new Date(hdr.getTime())),
                Long.toHexString(hdr.getClientId()),
                Long.toHexString(hdr.getCxid()),
                Long.toHexString(hdr.getZxid()),
                TraceFormatter.op2String(hdr.getType()),
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
        CommandLineParser parser = new PosixParser();
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

        try {
            CommandLine cli = parser.parse(options, args);
            if (cli.hasOption("help")) {
                printHelpAndExit(0, options);
            }
            if (cli.getArgs().length < 1) {
                printHelpAndExit(1, options);
            }
            return new TxnLogToolkit(cli.hasOption("recover"), cli.hasOption("verbose"), cli.getArgs()[0], cli.hasOption("yes"));
        } catch (ParseException e) {
            throw new TxnLogToolkitParseException(options, 1, e.getMessage());
        }
    }

    private static void printHelpAndExit(int exitCode, Options options) {
        HelpFormatter help = new HelpFormatter();
        help.printHelp(120,"TxnLogToolkit [-dhrv] <txn_log_file_name>", "", options, "");
        System.exit(exitCode);
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
