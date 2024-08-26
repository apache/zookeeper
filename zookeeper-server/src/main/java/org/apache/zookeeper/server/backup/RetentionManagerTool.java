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

package org.apache.zookeeper.server.backup;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Optional;
import com.twitter.finagle.Http$;
import com.twitter.finagle.http.HttpMuxer;
import com.twitter.finagle.stats.DefaultStatsReceiver;
import com.twitter.finagle.stats.MetricsExporter;
import com.twitter.finagle.stats.NullStatsReceiver;
import com.twitter.finagle.tracing.NullTracer;
import com.twitter.server.handler.HeapResourceHandler;
import com.twitter.server.handler.ProfileResourceHandler;
import com.twitter.util.Closable;
import com.twitter.util.Closable$;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import org.apache.zookeeper.cli.OptionBuilder;

/**
 * Tool for running the retention manager as a daemon
 */
public class RetentionManagerTool {
    private static final String LOCAL_PURGER = "local-purger";
    private static final String LOCAL_PURGER_INTERVAL = LOCAL_PURGER + "-interval";
    private static final String LOCAL_PURGER_SNAP_DIR = LOCAL_PURGER + "-snap-dir";
    private static final String LOCAL_PURGER_TXLOG_DIR = LOCAL_PURGER + "-txlog-dir";
    private static final String LOCAL_PURGER_BACKUP_STATUS_DIR = LOCAL_PURGER + "-backup-status-dir";
    private static final String LOCAL_PURGER_SNAP_RETAIN_COUNT = LOCAL_PURGER + "-snap-retain-count";

    private static final String HDFS_PURGER = "hdfs-purger";
    private static final String HDFS_PURGER_INTERVAL = HDFS_PURGER + "-interval";
    private static final String HDFS_PURGER_RETENTION_PERIOD = HDFS_PURGER + "-retention-period";
    private static final String HDFS_PURGER_CONFIG_DIR = HDFS_PURGER + "-config-dir";
    private static final String HDFS_PURGER_BASE_PATH = HDFS_PURGER + "-base-path";
    private static final String HDFS_PURGER_RECURSION_DEPTH = HDFS_PURGER + "-recursion-depth";
    private static final String HDFS_PURGER_USE_ADAPTIVE_SNAP_RETENTION = HDFS_PURGER + "-adaptive-snap-retention";

    private static final String VERBOSE = "verbose";
    private static final String VERBOSE_SHORT = "v";
    private static final String ADMIN_PORT = "admin_port";
    private static final String ADMIN_PORT_SHORT = "p";
    private static final String DRYRUN = "n";

    private static final String ADMIN_METRICS          = "/admin/metrics.json";
    private static final String ADMIN_PPROF_HEAP       = "/admin/pprof/heap";
    private static final String ADMIN_PPROF_PROFILE    = "/admin/pprof/profile";
    private static final String ADMIN_PPROF_CONTENTION = "/admin/pprof/contention";

    private static List<Option> base;
    private static List<Option> local;
    private static List<Option> hdfs;

    private static RetentionManager.Configuration config;
    private static Optional<Integer> adminPort = Optional.absent();

    static {
        base = new ArrayList<>();
        base.add(new Option("h", "help", false, "Print usage."));

        base.add(new OptionBuilder()
                    .longOpt(LOCAL_PURGER_INTERVAL)
                    .desc("The interval, in minutes, at which to run the local snap and "
                            + "txnlog purger")
                    .argName("MINUTES")
                    .type(Number.class)
                    .hasArg()
                    .build());
        base.add(new OptionBuilder()
                    .longOpt(HDFS_PURGER_INTERVAL)
                    .desc(
                            "The interval, in hours, at which to run the backup cleanup.")
                    .argName("HOURS")
                    .type(Number.class)
                    .hasArg()
                    .build());
        base.add(new OptionBuilder(VERBOSE_SHORT).longOpt(VERBOSE).build());
        base.add(new OptionBuilder(ADMIN_PORT_SHORT)
                    .longOpt(ADMIN_PORT)
                    .desc("The port to use for the admin server")
                    .argName("PORT")
                    .type(Number.class)
                    .hasArg()
                    .build());
        base.add(new Option("n",
                "Dryrun -- report actions that would be taken but do not take them."));

        local = new ArrayList<>();
        local.add(new OptionBuilder()
                    .longOpt(LOCAL_PURGER_SNAP_DIR)
                    .desc("The location for ZooKeeper snapshots")
                    .argName("PATH")
                    .type(File.class)
                    .hasArg()
                    .required()
                    .build());
        local.add(new OptionBuilder()
                    .longOpt(LOCAL_PURGER_TXLOG_DIR)
                    .desc("The location for ZooKeeper transaction logs")
                    .argName("PATH")
                    .type(File.class)
                    .hasArg()
                    .build());
        local.add(new OptionBuilder()
                    .longOpt(LOCAL_PURGER_BACKUP_STATUS_DIR)
                    .desc("The location for the backup status file")
                    .argName("PATH")
                    .type(File.class)
                    .hasArg()
                    .required()
                    .build());
        local.add(new OptionBuilder()
                    .longOpt(LOCAL_PURGER_SNAP_RETAIN_COUNT)
                    .desc("Minimum number of snaps to retain")
                    .argName("SNAPS")
                    .type(Number.class)
                    .hasArg()
                    .required()
                    .build());

        hdfs = new ArrayList<>();
        hdfs.add(new OptionBuilder()
                    .longOpt(HDFS_PURGER_RETENTION_PERIOD)
                    .desc("The duration for which backups should be retained")
                    .argName("DAYS")
                    .type(Number.class)
                    .hasArg()
                    .required()
                    .build());
        hdfs.add(new OptionBuilder()
                    .longOpt(HDFS_PURGER_CONFIG_DIR)
                    .desc("The hdfs config locations")
                    .argName("PATH")
                    .type(File.class)
                    .hasArg()
                    .required()
                    .build());
        hdfs.add(new OptionBuilder()
                    .longOpt(HDFS_PURGER_BASE_PATH)
                    .desc("The hdfs base path for the backups")
                    .argName("RELATIVE-PATH")
                    .hasArg()
                    .required()
                    .build());
        hdfs.add(new OptionBuilder()
                    .longOpt(HDFS_PURGER_RECURSION_DEPTH)
                    .desc("The depth to recurse into directories")
                    .argName("DEPTH")
                    .type(Number.class)
                    .hasArg()
                    .required()
                    .build());
        hdfs.add(new OptionBuilder()
                    .longOpt(HDFS_PURGER_USE_ADAPTIVE_SNAP_RETENTION)
                    .desc("Use adaptive snap retention intervals")
                    .build());
    }

    /**
     * Main entry point
     * @param args arguments; only one expected: the location of the ZooKeeper configuration file.
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        parseArgs(args);
        RetentionManager mgr = new RetentionManager(config, DefaultStatsReceiver.self());

        // Admin
        Closable adminServer;

        if (adminPort.isPresent()) {
            System.out.println("Starting admin server on port " + adminPort.get());
            HttpMuxer muxerSvc = new HttpMuxer()
                .withHandler(ADMIN_METRICS, new MetricsExporter())
                .withHandler(ADMIN_PPROF_HEAP, new HeapResourceHandler())
                .withHandler(ADMIN_PPROF_PROFILE,
                    new ProfileResourceHandler(Thread.State.RUNNABLE))
                .withHandler(ADMIN_PPROF_CONTENTION,
                    new ProfileResourceHandler(Thread.State.BLOCKED));
            adminServer = Http$.MODULE$.server()
                .withTracer(new NullTracer())
                .withStatsReceiver(NullStatsReceiver.get())
                .withLabel("admin")
                .serve(new InetSocketAddress(adminPort.get()), muxerSvc);
        } else {
            adminServer = Closable$.MODULE$.nop();
        }

        try {
            mgr.run();
        } catch (Exception e) {
            System.err.println("Retention manager failed with an exception: " + e.getMessage());
            System.exit(1);
        } finally {
            adminServer.close();
        }
    }

    /**
     * Parse the arguments configuration.
     * @param args command line arguments
     * @return the parsed ZooKeeper quorum peer configuration
     */
    public static void parseArgs(String[] args) {
        try {
            long localPurgerInterval = 0;
            File localPurgerSnapDir = null;
            File localPurgerTxlogDir = null;
            File localPurgerBackupStatusDir = null;
            int localPurgerSnapRetainCount = Integer.MAX_VALUE;

            long hdfsPurgerInterval = 0;
            File hdfsPurgerConfigDir = null;
            String hdfsPurgerBasePath = null;
            int hdfsPurgerRecursionDepth = 0;
            int hdfsPurgerRetentionDays = Integer.MAX_VALUE;
            boolean hdfsPurgerUseAdaptiveSnapRetention = false;

            boolean dryRun = false;

            BasicParser parser = new BasicParser();
            Options options = new Options();

            addOptions(options, base);

            // First parse the base options to determine which tasks to run; this will not
            // fail on unknown options
            CommandLine cl = parser.parse(options, args, true);

            if (cl.hasOption(ADMIN_PORT)) {
                adminPort = Optional.of(((Number)cl.getParsedOptionValue(ADMIN_PORT)).intValue());
            }

            if (cl.hasOption(LOCAL_PURGER_INTERVAL)) {
                localPurgerInterval = TimeUnit.MINUTES.toMillis(
                        ((Number) cl.getParsedOptionValue(LOCAL_PURGER_INTERVAL)).longValue());
            }

            if (cl.hasOption(HDFS_PURGER_INTERVAL)) {
                hdfsPurgerInterval = TimeUnit.HOURS.toMillis(
                        ((Number) cl.getParsedOptionValue(HDFS_PURGER_INTERVAL)).longValue());
            }

            if (cl.hasOption(DRYRUN)) {
                dryRun = true;
            }

            if (localPurgerInterval > 0) {
                addOptions(options, local);
            }

            if (hdfsPurgerInterval > 0) {
                addOptions(options, hdfs);
            }

            // Now parse with the required options for each of the requested tasks; this validates
            // that all options are legal
            cl = parser.parse(options, args);

            if (localPurgerInterval > 0) {
                localPurgerSnapDir = (File)cl.getParsedOptionValue(LOCAL_PURGER_SNAP_DIR);
                localPurgerTxlogDir = localPurgerSnapDir;

                if (cl.hasOption(LOCAL_PURGER_TXLOG_DIR)) {
                    localPurgerTxlogDir = (File)cl.getParsedOptionValue(LOCAL_PURGER_TXLOG_DIR);
                }

                localPurgerBackupStatusDir =
                    (File)cl.getParsedOptionValue(LOCAL_PURGER_BACKUP_STATUS_DIR);
                localPurgerSnapRetainCount =
                    ((Number)cl.getParsedOptionValue(LOCAL_PURGER_SNAP_RETAIN_COUNT)).intValue();
            }

            if (hdfsPurgerInterval > 0) {
                hdfsPurgerConfigDir = (File)cl.getParsedOptionValue(HDFS_PURGER_CONFIG_DIR);
                hdfsPurgerBasePath = cl.getOptionValue(HDFS_PURGER_BASE_PATH);
                hdfsPurgerRetentionDays =
                    ((Number)cl.getParsedOptionValue(HDFS_PURGER_RETENTION_PERIOD)).intValue();
                hdfsPurgerRecursionDepth =
                    ((Number)cl.getParsedOptionValue(HDFS_PURGER_RECURSION_DEPTH)).intValue();

                if (cl.hasOption(HDFS_PURGER_USE_ADAPTIVE_SNAP_RETENTION)) {
                    hdfsPurgerUseAdaptiveSnapRetention = true;
                }
            }

            if (cl.hasOption("h") || cl.hasOption("help")) {
                printHelp();
            }

            config = new RetentionManager.Configuration(
                        dryRun,
                        localPurgerInterval,
                        localPurgerSnapDir,
                        localPurgerTxlogDir,
                        localPurgerBackupStatusDir,
                        localPurgerSnapRetainCount,
                        hdfsPurgerInterval,
                        hdfsPurgerConfigDir,
                        hdfsPurgerBasePath,
                        hdfsPurgerRecursionDepth,
                        hdfsPurgerRetentionDays,
                        hdfsPurgerUseAdaptiveSnapRetention);

            if (cl.hasOption("v") || cl.hasOption("verbose")) {
                System.out.println("Configuration:");
                System.out.println(config);
            }

        } catch (ParseException pe) {
            System.err.println(pe.getMessage());
            printHelp();
            System.exit(2);
        }
    }

    private static void addOptions(Options target, List<Option> options) {
        for (Option o : options) {
            target.addOption(o);
        }
    }

    private static void printHelp() {
        Options full = new Options();
        HelpFormatter help = new HelpFormatter();

        addOptions(full, base);
        addOptions(full, local);
        addOptions(full, hdfs);

        String header = "Options starting with " + LOCAL_PURGER
                + "- are required if --" + LOCAL_PURGER_INTERVAL
                + " specifies a value greater than 0.\n"
                + "Options starting with " + HDFS_PURGER
                + "- are required if --" + HDFS_PURGER_INTERVAL
                + " specifies a value greater than 0.";

        help.printHelp(
                120,
                "java -cp <class-path> " + RetentionManagerTool.class.getName(),
                header,
                full,
                "NOTE: Console output may include logging output.");

    }
}
