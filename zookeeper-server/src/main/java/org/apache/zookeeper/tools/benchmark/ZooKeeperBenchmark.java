package org.apache.zookeeper.tools.benchmark;


import java.io.FileNotFoundException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.persistence.TxnLogToolkit;

public class ZooKeeperBenchmark {


    private static BenchmarkConfig parseCommandLine(String[] args) throws FileNotFoundException {
        CommandLineParser parser = new PosixParser();
        Options options = new Options();

        Option helpOpt = new Option("h", "help", false, "Print help message");
        options.addOption(helpOpt);

        try {
            CommandLine cli = parser.parse(options, args);
            if (cli.hasOption("help")) {
                printHelpAndExit(0, options);
            }
            if (cli.getArgs().length < 1) {
                printHelpAndExit(1, options);
            }
            if (cli.hasOption("chop") && cli.hasOption("zxid")) {
                //return new TxnLogToolkit(cli.getArgs()[0], cli.getOptionValue("zxid"));
            }
            //return new TxnLogToolkit(cli.hasOption("recover"), cli.hasOption("verbose"), cli.getArgs()[0], cli.hasOption("yes"));
        } catch (ParseException e) {
            //throw new TxnLogToolkit.TxnLogToolkitParseException(options, ExitCode.UNEXPECTED_ERROR.getValue(), e.getMessage());
        }
    }

    private static void printHelpAndExit(int exitCode, Options options) {
        HelpFormatter help = new HelpFormatter();
        help.printHelp(120,"TxnLogToolkit [-dhrvc] <txn_log_file_name> (-z <zxid>)", "", options, "");
        System.exit(exitCode);
    }

    class BenchmarkConfig {
        int re

    }

    public static void main(String[] args) {

    }
}