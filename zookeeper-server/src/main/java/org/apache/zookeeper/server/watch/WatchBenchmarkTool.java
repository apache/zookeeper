/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.watch;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ConfigException;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.common.StringUtils;
import org.apache.zookeeper.server.metric.AvgMinMaxPercentileCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A benchmark tool that benchmarks the watch throughput and latency.
 * See ZOOKEEPER-3823 for the design document
 */

public class WatchBenchmarkTool {
    private static final Logger LOG = LoggerFactory.getLogger(WatchBenchmarkTool.class);

    // <znode path sequence number, watch trigger timestamp>
    private static final ConcurrentHashMap<Integer, Long> watchTriggerTimeMap = new ConcurrentHashMap<>();
    private static final String percentileCounterName = "watch notify latency";
    private static final AvgMinMaxPercentileCounter latencyPercentileCounter = new AvgMinMaxPercentileCounter(percentileCounterName);
    private static final AtomicLong totalStartTriggerWatchTime = new AtomicLong(System.currentTimeMillis());
    private static int timeout;
    private static boolean isVerbose;

    private static String rootPath;
    private static int znodeCount;
    private static int znodeSize = 1;
    private static int clientThreads;
    private static String connectString;
    private static int sessionTimeout;
    private static String configFilePath;
    private static final AtomicLong actualReceivedNotifyCnt = new AtomicLong();

    public static void main(String[] args) throws Exception {
        long totalStartTime = System.currentTimeMillis();
        Options options = getOptions();

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        if (args.length == 0 || cmd.hasOption("help")) {
            usage(options);
            System.exit(-1);
        }
        checkParameters(cmd);
        createWorkSpace();

        // submit tasks to thread pool
        ExecutorService executorService = Executors.newFixedThreadPool(clientThreads);
        CyclicBarrier createNodeCyclicBarrier = new CyclicBarrier(clientThreads);
        CyclicBarrier setWatchCyclicBarrier = new CyclicBarrier(clientThreads);
        CountDownLatch deleteNodeCountDownLatch = new CountDownLatch(clientThreads);
        CountDownLatch finishWatchCountDownLatch = new CountDownLatch(clientThreads * znodeCount);
        CountDownLatch closeClientCountDownLatch = new CountDownLatch(1);
        AtomicBoolean syncOnce = new AtomicBoolean(false);
        for (int i = 0; i < clientThreads; i++) {
            executorService.execute(new WatchClientThread(i, createNodeCyclicBarrier,
                    setWatchCyclicBarrier, deleteNodeCountDownLatch, finishWatchCountDownLatch, closeClientCountDownLatch, syncOnce));
        }

        // wait for deleting all nodes
        long deleteAwaitStart = System.currentTimeMillis();
        deleteNodeCountDownLatch.await();
        if (isVerbose) {
            LOG.info("deleteNodeCountDownLatch await time spent: {} ms", (System.currentTimeMillis() - deleteAwaitStart));
        }

        /*
          wait for all watch events arrival, especially network latency or overhead workloads
           In most cases, when znodes have been deleted, most of the watch events has been notified
         */
        long finishWatchAwaitStart = System.currentTimeMillis();
        boolean finishAwaitFlag = finishWatchCountDownLatch.await(timeout, TimeUnit.MILLISECONDS);
        if (isVerbose) {
            LOG.info("finishWatchCountDownLatch await time spent: {} ms, awaitFlag:{}", (System.currentTimeMillis() - finishWatchAwaitStart), finishAwaitFlag);
        }
        long endTime = System.currentTimeMillis();
        long totalWatchSpentTime = endTime - totalStartTriggerWatchTime.get();
        if (isVerbose) {
            LOG.info("totalStartTriggerWatchTime: {}, endTime: {}, totalWatchSpentTime: {} ms ", totalStartTriggerWatchTime, endTime, totalWatchSpentTime);
        }

        destroyWorkSpace();
        // close all the zk clients
        closeClientCountDownLatch.countDown();
        // shutdown thread pool
        shutDownThreadPool(executorService, timeout, TimeUnit.MILLISECONDS);
        // show the summary
        showBenchmarkReport(totalStartTime, totalWatchSpentTime);
    }

    private static Options getOptions() {
        Options options = new Options();

        options.addOption("connect_string", true, "ZooKeeper connectString. Default: 127.0.0.1:2181");
        options.addOption("root_path", true, "Root Path for creating znodes for the benchmark.");
        options.addOption("znode_count", true, "The znode count. Default: 1000");
        options.addOption("znode_size", true, "The data length of per znode. Default: 1");
        options.addOption("threads", true, "The client thread number. Default: 1");
        options.addOption("session_timeout", true, "ZooKeeper sessionTimeout. Default: 40000 ms");
        options.addOption("timeout", true, "Timeout for each stage in the benchmark process. Default value: 30000, Unit: ms");
        options.addOption("client_configuration", true, "Client configuration file to set some special client setting");
        options.addOption("v", false, "Verbose output, print some logs for debugging");
        options.addOption("help", false, "Help message");

        return options;
    }

    private static void showBenchmarkReport(long totalStartTime, long totalWatchSpentTime) {
        long notifySize = actualReceivedNotifyCnt.get();

        // receive, loss notifications count and ratio summary
        double receivedRatio = (double) notifySize / (double) (clientThreads * znodeCount);
        long lossCount = (long) clientThreads * znodeCount - notifySize;
        double lossRatio = (double) lossCount / (double) (clientThreads * znodeCount);
        System.out.println();
        System.out.printf(
                "Notification expected count: %d, received count: %d (%.4f), loss count: %d (%.4f)%n",
                (clientThreads * znodeCount),
                notifySize,
                receivedRatio,
                lossCount,
                lossRatio);

        // latency distribution
        printLatencyDistribution();
        // throughput
        double perNotificationInMicro = (double) (totalWatchSpentTime * 1000) / (double) notifySize;
        System.out.printf(
                "Total time: %d ms, watch benchmark total time: %d ms, throughput: %.2f op/s%n",
                (System.currentTimeMillis() - totalStartTime),
                totalWatchSpentTime,
                (1000.0 * 1000 / perNotificationInMicro));
    }

    private static void shutDownThreadPool(ExecutorService executorService, long timeout, TimeUnit unit) {
        long start = System.currentTimeMillis();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(timeout, unit)) {
                List<Runnable> dropped = executorService.shutdownNow();
                LOG.warn("Executor did not terminate in {} {} — shutdownNow() returned {} tasks not executed",
                        timeout, unit, dropped.size());
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOG.error("Executor still did not terminate after shutdownNow()");
                }
            }
        } catch (InterruptedException ie) {
            List<Runnable> dropped = executorService.shutdownNow();
            LOG.warn("Interrupted while waiting for executor termination; shutdownNow returned {} tasks", dropped.size(), ie);
            Thread.currentThread().interrupt();
        } finally {
            if (isVerbose) {
                LOG.info("Shutdown all the WatchClientThread in {} ms", System.currentTimeMillis() - start);
            }
        }
    }

    private static void usage(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("WatchBenchmarkTool <options>", options);
    }

    private static void checkParameters(CommandLine cmd) {
        // root_path
        rootPath = cmd.getOptionValue("root_path");
        PathUtils.validatePath(rootPath);
        if ("/".equals(rootPath)) {
            throw new IllegalArgumentException("root_path must not be set with '/'");
        }

        sessionTimeout = Integer.parseInt(cmd.getOptionValue("session_timeout", "40000"));
        checkOptionNumber("session_timeout", sessionTimeout);
        connectString = cmd.getOptionValue("connect_string", "127.0.0.1:2181");
        configFilePath = cmd.getOptionValue("client_configuration");
        // znodes
        znodeCount = Integer.parseInt(cmd.getOptionValue("znode_count", "1000"));
        checkOptionNumber("znode_count", znodeCount);
        // znode_size
        znodeSize = Integer.parseInt(cmd.getOptionValue("znode_size", "1"));
        checkOptionNumber("znode_size", znodeSize);
        // threads
        clientThreads = Integer.parseInt(cmd.getOptionValue("threads", "1"));
        checkOptionNumber("threads", clientThreads);
        if (clientThreads > 60) {
            LOG.warn("The clientThreads set {} has exceeded the default maxClientCnxns value:60. Note you should also set this property in the server side", clientThreads);
        }
        timeout = Integer.parseInt(cmd.getOptionValue("timeout", "30000"));
        checkOptionNumber("timeout", timeout);
        isVerbose = cmd.hasOption("v");
    }

    private static void checkOptionNumber(String optionName, int optionVal) {
        if (optionVal <= 0) {
            throw new IllegalArgumentException(optionName + " must be greater than " + 0);
        }
    }

    private static void createWorkSpace() {
        try (ZooKeeper zk = initZKClient()) {
            if (zk.exists(rootPath, null) != null) {
                throw new IllegalArgumentException("cannot benchmark under an existing rootPath: " + rootPath);
            } else {
                // help user to create the znode: rootPath
                String[] paths = rootPath.split("/");
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < paths.length; i++) {
                    sb.append("/").append(paths[i]);
                    try {
                        zk.create(sb.toString(), "".getBytes(UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                CreateMode.PERSISTENT);
                    } catch (KeeperException.NodeExistsException e) {
                        // ignore it
                    }
                }
            }
        } catch (IOException | InterruptedException | KeeperException | ConfigException e) {
            logError("Error createWorkSpace", e);
            System.exit(-1);
        }
    }

    private static void destroyWorkSpace() {
        try (ZooKeeper zk = initZKClient()) {
            if (zk.exists(rootPath, false) != null) {
                List<String> children = zk.getChildren(rootPath, false);
                if (children.isEmpty()) {
                    zk.delete(rootPath, -1);
                } else {
                    LOG.warn("Cannot delete rootPath: {} because it has children: {}", rootPath, children);
                }
            } else {
                LOG.warn("Root path does not exist: {}", rootPath);
            }
        } catch (IOException | InterruptedException | KeeperException | ConfigException e) {
            logError("Error destroyWorkSpace", e);
        }
    }

    private static ZooKeeper initZKClient() throws IOException, ConfigException {
        ZooKeeper zk;
        if (StringUtils.isBlank(configFilePath)) {
            zk = new ZooKeeper(connectString, sessionTimeout, null);
        } else {
            Path configPath = Paths.get(configFilePath);
            ZKClientConfig config = new ZKClientConfig(configPath);
            zk = new ZooKeeper(connectString, sessionTimeout, null, false, config);
        }
        return zk;
    }

    private static void printLatencyDistribution() {
        StringBuilder output = new StringBuilder();
        int keyWidth = 30;

        for (Map.Entry<String, Object> oneEntry : latencyPercentileCounter.values().entrySet()) {
            String key = oneEntry.getKey().replace("_", " ");
            String value = oneEntry.getValue().toString();
            String suffix = key.contains("cnt") ? "" : " ms";
            output.append(String.format("%-" + keyWidth + "s = %s%s%n", key, value, suffix));
        }

        System.out.print(output);
    }

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Random RANDOM = new Random();
    private static String getRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private static void logError(String message, Exception e) {
        LOG.error(message, e);
    }

    /**
     * WatchClientThread does the following things:
     *     create corresponding znodes if needed(when threads > znodes, some threads don't do this operation)
     *     set watch for all znodes
     *     trigger watch by issuing write requests
     *     delete corresponding znodes if needed(when threads > znodes, some threads don't do this operation)
     *     close zk client
     */
    static class WatchClientThread implements Runnable {
        private final Integer threadIndex;
        private final CyclicBarrier createNodeCyclicBarrier;
        private final CyclicBarrier setWatchCyclicBarrier;
        private final CountDownLatch deleteNodeCountDownLatch;
        private final CountDownLatch finishWatchCountDownLatch;
        private final CountDownLatch closeClientCountDownLatch;
        private final AtomicBoolean syncOnce;

        public WatchClientThread(Integer threadIndex, CyclicBarrier createNodeCyclicBarrier,
                                 CyclicBarrier setWatchCyclicBarrier, CountDownLatch deleteNodeCountDownLatch,
                                 CountDownLatch finishWatchCountDownLatch, CountDownLatch closeClientCountDownLatch,
                                 AtomicBoolean syncOnce) {
            this.threadIndex = threadIndex;
            this.createNodeCyclicBarrier = createNodeCyclicBarrier;
            this.setWatchCyclicBarrier = setWatchCyclicBarrier;
            this.deleteNodeCountDownLatch = deleteNodeCountDownLatch;
            this.finishWatchCountDownLatch = finishWatchCountDownLatch;
            this.closeClientCountDownLatch = closeClientCountDownLatch;
            this.syncOnce = syncOnce;
        }

        @Override
        public void run() {
            Thread.currentThread().setName("WatchClientThread-" + threadIndex);
            ZooKeeper zk = null;
            try {
                zk = initZKClient();

                // create
                createNode(zk);
                // block here waiting for all the threads creating its corresponding znodes, then go ahead together
                createNodeCyclicBarrier.await();
                if (isVerbose) {
                    LOG.info("{} has finished creating its corresponding znodes", Thread.currentThread().getName());
                }

                // set watch
                SimpleWatcher simpleWatcher = new SimpleWatcher(finishWatchCountDownLatch);
                setWatchForAllNodes(zk, simpleWatcher);
                // block here waiting for all the threads setting watch for all znodes, then go ahead together
                setWatchCyclicBarrier.await();
                if (isVerbose) {
                    LOG.info("{} has finished setting watch for all znodes", Thread.currentThread().getName());
                }

                // make sure only one thread(the fastest one) enters this code to record/assign the total start trigger Watch Time
                if (syncOnce.compareAndSet(false, true)) {
                    totalStartTriggerWatchTime.set(System.currentTimeMillis());
                }

                // delete to trigger watch, also as a function to clean up the workspace
                deleteNode(zk);
                deleteNodeCountDownLatch.countDown();
                if (isVerbose) {
                    LOG.info("{} has finished deleting its corresponding znodes", Thread.currentThread().getName());
                }
            } catch (InterruptedException | BrokenBarrierException | IOException | ConfigException e) {
                LOG.warn("{} encounters exception", Thread.currentThread().getName(), e);
            } finally {
                if (zk != null) {
                    try {
                        if (isVerbose) {
                            LOG.info("{} has started to close the zk client", Thread.currentThread().getName());
                        }
                        closeClientCountDownLatch.await();
                        zk.close();
                    } catch (InterruptedException e) {
                        logError("Error while closing ZooKeeper client", e);
                    }
                }
            }
            if (isVerbose) {
                LOG.info("{} has finished its task and exited", Thread.currentThread().getName());
            }
        }

        private void createNode(ZooKeeper zk) {
            for (int i = 0; (i * clientThreads + threadIndex) < znodeCount; i++) {
                int path = (i * clientThreads + threadIndex);
                try {
                    String data = getRandomString(znodeSize);
                    zk.create(rootPath + "/" + path, data.getBytes(UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT);
                } catch (KeeperException | InterruptedException e) {
                    logError("Error creating node: " + path, e);
                }
            }
        }

        private void setWatchForAllNodes(ZooKeeper zk, SimpleWatcher simpleWatcher) {
            for (int i = 0; i < znodeCount; i++) {
                try {
                    zk.exists(rootPath + "/" + i, simpleWatcher);
                } catch (KeeperException | InterruptedException e) {
                    logError("Error setWatchForAllNodes: ", e);
                }
            }
        }

        private void deleteNode(ZooKeeper zk) {
            for (int i = 0; (i * clientThreads + threadIndex) < znodeCount; i++) {
                int path = (i * clientThreads + threadIndex);
                watchTriggerTimeMap.put(path, System.currentTimeMillis());
                try {
                    zk.delete(rootPath + "/" + path, -1);
                } catch (KeeperException | InterruptedException e) {
                    logError("Error delete node: " + path, e);
                }
            }
        }
    }

    private static class SimpleWatcher implements Watcher {
        private final CountDownLatch finishWatchCountDownLatch;

        public SimpleWatcher(CountDownLatch finishWatchCountDownLatch) {
            this.finishWatchCountDownLatch = finishWatchCountDownLatch;
        }

        public void process(WatchedEvent e) {
            try {
                if (e.getType() == Event.EventType.None) {
                    return;
                }
                if (e.getState() == Event.KeeperState.SyncConnected) {
                    actualReceivedNotifyCnt.incrementAndGet();
                    String pathIndex = e.getPath().substring(e.getPath().lastIndexOf("/") + 1);
                    Long startTriggerTime = watchTriggerTimeMap.get(Integer.parseInt(pathIndex));
                    if (startTriggerTime != null) {
                        long timeSpent = System.currentTimeMillis() - startTriggerTime;
                        latencyPercentileCounter.add(timeSpent);
                    }
                    finishWatchCountDownLatch.countDown();
                    if (isVerbose) {
                        LOG.info("finishWatchCountDownLatchCount: {}, pathIndex: {}", finishWatchCountDownLatch.getCount()
                                , pathIndex);
                    }
                }
            } catch (Exception ex) {
                LOG.warn("SimpleWatcher process watch path:{}, exception", e.getPath(), ex);
            }
        }
    }
}