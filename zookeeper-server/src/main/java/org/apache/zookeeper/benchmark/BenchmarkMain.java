package org.apache.zookeeper.benchmark;


import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;

public class BenchmarkMain {

    private static BenchmarkClient client = null;

    private static BenchmarkClient[] clients;

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
                config.setEndpoints(endpoints);
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
            int conns = config.getConns();
            String[] serverList = config.getEndpoints().split(",");
            int clientCount = config.getClients();

            clients = new BenchmarkClient[clientCount];

            for (int i = 0; i < clientCount; i++) {
                BenchmarkClient client = new SyncBenchmarkClient(config);
                client.start();
                clients[i] = client;
            }

            for (int i = 0; i < conns; i++) {
                
            }

            BenchmarkClient client = new SyncBenchmarkClient(config);
            client.start();
            //Thread workThread = new WorkThread(
        } catch (Exception e) {

        }
    }
}

class BenchmarkConfig {
    private String endpoints = "127.0.0.1:2181";//=
    private int conns = 100;//=10000
    private String cmd; //put
    private int clients = 10;//=10 put
    private String keySize;//;=8 --sequential-keys

    public String getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(String endpoints) {
        this.endpoints = endpoints;
    }

    public int getConns() {
        return conns;
    }

    public void setConns(int conns) {
        this.conns = conns;
    }

    public String getCmd() {
        return cmd;
    }

    public void setCmd(String cmd) {
        this.cmd = cmd;
    }

    public int getClients() {
        return clients;
    }

    public void setClients(int clients) {
        this.clients = clients;
    }

    public String getKeySize() {
        return keySize;
    }

    public void setKeySize(String keySize) {
        this.keySize = keySize;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getValSize() {
        return valSize;
    }

    public void setValSize(int valSize) {
        this.valSize = valSize;
    }

    private int total;//=100000 --
    private int valSize;//=256

}