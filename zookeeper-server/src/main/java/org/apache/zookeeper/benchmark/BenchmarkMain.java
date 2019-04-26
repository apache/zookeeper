package org.apache.zookeeper.benchmark;


import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;

public class BenchmarkMain {

    private static BenchmarkConfig parseCommandLine2(String[] args) {
        BenchmarkConfig config = new BenchmarkConfig();

        CommandLineParser parser = new PosixParser();
        Options options = new Options();

        Option endpoints = new Option("endpoints", true, "endpoints");
        options.addOption(endpoints);


        try {
            CommandLine cli = parser.parse(options, args);
            if (cli.hasOption("help")) {
                printHelpAndExit(0, options);
            }
            if (cli.getArgs().length < 1) {
                printHelpAndExit(1, options);
            }
            if (cli.hasOption("endpoints")) {
                System.out.println("fuck__endpoints:" + cli.getOptionValue("endpoints"));
                //return new TxnLogToolkit(cli.getArgs()[0], cli.getOptionValue("zxid"));
            }
            //String database = cli.getOptionValue("d");
            //System.out.println("database: " + database);
            //return new TxnLogToolkit(cli.hasOption("recover"), cli.hasOption("verbose"), cli.getArgs()[0], cli.hasOption("yes"));
        } catch (Exception e) {
            System.out.println(e);
            //throw new TxnLogToolkit.TxnLogToolkitParseException(options, ExitCode.UNEXPECTED_ERROR.getValue(), e.getMessage());
        }

        return config;
    }

    private static BenchmarkConfig parseCommandLine(String[] args) {
        BenchmarkConfig config = new BenchmarkConfig();

        Options options = new Options();

        options.addOption("h", "help", false, "print options' information");
        Option endpointsOption = new Option("endpoints", true, "endpoints");
        options.addOption(endpointsOption);
        Option cmdOption = new Option("cmd", true, "cmd");
        options.addOption(cmdOption);

        CommandLineParser parser = new BasicParser();
        try {
            CommandLine cli = parser.parse(options, args);
            if (cli.hasOption("h")) {
                HelpFormatter hf = new HelpFormatter();
                hf.printHelp("Options", options);
            } else {
                String endpoints = cli.getOptionValue("endpoints");
                config.endpoints = endpoints;
                System.out.println("endpoints: " + endpoints);
//                String table = cli.getOptionValue("t");
//                System.out.println("table: " + table);
//                String[] files = cli.getOptionValues("f");
//                System.out.println("files: " + Arrays.asList(files));

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return config;
    }

    private static void printHelpAndExit(int exitCode, Options options) {
        HelpFormatter help = new HelpFormatter();
        help.printHelp(120, "TxnLogToolkit [-dhrvc] <txn_log_file_name> (-z <zxid>)", "", options, "");
        System.exit(exitCode);
    }

    public static void main(String[] args) {
        try {
            //-endpoints 127.0.0.1:2181 -cmd put
            BenchmarkConfig config = parseCommandLine(args);
        } catch (Exception e) {

        }
    }
}

class BenchmarkConfig {
    protected String endpoints = "127.0.0.1:2181";//=
    private int conns = 10000;//=10000
    private String cmd; //put
    private int clients = 10;//=10 put
    private String keySize;//;=8 --sequential-keys
    private int total;//=100000 --
    private int valSize;//=256

}