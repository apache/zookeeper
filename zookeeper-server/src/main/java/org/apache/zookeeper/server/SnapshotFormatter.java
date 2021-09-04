/*
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

package org.apache.zookeeper.server;

import static java.lang.System.err;
import static org.apache.zookeeper.server.ExitCode.EXECUTION_FINISHED;
import static org.apache.zookeeper.server.ExitCode.INVALID_INVOCATION;
import static org.apache.zookeeper.server.persistence.FileSnap.SNAPSHOT_FILE_PREFIX;
import static org.apache.zookeeper.server.persistence.TxnLogToolkit.checkNullToEmpty;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.InputArchive;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.ZKUtil;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.server.persistence.FileSnap;
import org.apache.zookeeper.server.persistence.SnapStream;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.util.ServiceUtils;

/**
 * Dump a snapshot file to stdout.
 *
 * For JSON format, followed https://dev.yorhel.nl/ncdu/jsonfmt
 */
@InterfaceAudience.Public
public class SnapshotFormatter {

    // per-znode counter so ncdu treats each as a unique object
    private static Integer INODE_IDX = 1000;

    private final Options options;
    private static final String dumpDataOption = "d";
    private static final String dumpJsonOption = "json";
    private static final String largestOption = "largest";
    private static final String bytesOption = "bytes";
    private static final String helpOption = "help";

    @SuppressWarnings("static")
    private SnapshotFormatter() {
        options = new Options();

        options.addOption(dumpDataOption, null, false, "dump the data for each znode");
        options.addOption(dumpJsonOption, null, false, "dump znode info in json format");
        options.addOption("l", largestOption, true, "limit output to only the top N znodes(by bytes)");
        options.addOption("b", bytesOption, true, "limit output the znode greater than or equal to this value (by bytes)");
        options.addOption("h", helpOption, false, "print help message");
    }

    /**
     * USAGE: SnapshotFormatter snapshot_file or the ready-made script: zkSnapShotToolkit.sh
     */
    public static void main(String[] args) throws Exception {
        SnapshotFormatter tool = new SnapshotFormatter();
        tool.runArgs(args);
    }

    private void printHelpAndExit(int exitCode) {
        HelpFormatter help = new HelpFormatter();

        help.printHelp(
                120,
                "java -cp <classPath> " + SnapshotFormatter.class.getName()
                + " [-options] snapshot_file",
                "",
                options,
                "");
        ServiceUtils.requestSystemExit(exitCode);
    }

    private Integer getAndCheckOptionValue(CommandLine cli, String option) {
        Integer optionVal = null;
        if (cli.hasOption(option)) {
            optionVal = Integer.parseInt(cli.getOptionValue(option));
            if (optionVal < 0) {
                err.println("The option:" + option + " must be greater than or equal 0");
                ServiceUtils.requestSystemExit(INVALID_INVOCATION.getValue());
            } else {
                return optionVal;
            }
        }
        return optionVal;
    }

    private void runArgs(String[] args) throws Exception {
        long startTime = Time.currentWallTime();
        CommandLine cli;
        try {
            cli = new DefaultParser().parse(options, args);
            if (cli.hasOption(helpOption) || cli.getArgs() == null || cli.getArgs().length < 1) {
                printHelpAndExit(EXECUTION_FINISHED.getValue());
            }
        } catch (ParseException e) {
            err.println(e.getMessage());
            printHelpAndExit(INVALID_INVOCATION.getValue());
            return;
        }

        String snapshotFile = cli.getArgs()[0];
        boolean dumpData = cli.hasOption(dumpDataOption);
        boolean dumpJson = cli.hasOption(dumpJsonOption);
        Integer largestThreshold = getAndCheckOptionValue(cli, largestOption);
        Integer byteThreshold = getAndCheckOptionValue(cli, bytesOption);

        String error = ZKUtil.validateFileInput(snapshotFile);
        if (null != error) {
            err.println(error);
            ServiceUtils.requestSystemExit(INVALID_INVOCATION.getValue());
        }

        if (dumpData && dumpJson) {
            err.println("Cannot specify both data dump (-d) and json mode (-json) in same call");
            ServiceUtils.requestSystemExit(INVALID_INVOCATION.getValue());
        }

        run(snapshotFile, dumpData, dumpJson, largestThreshold, byteThreshold);
        System.out.println("SnapshotTool total spent time (ms): " + (Time.currentWallTime() - startTime));
    }

    public void run(String snapshotFileName, boolean dumpData, boolean dumpJson, Integer largestThreshold, Integer byteThreshold)
            throws IOException {
        File snapshotFile = new File(snapshotFileName);
        try (InputStream is = SnapStream.getInputStream(snapshotFile)) {
            InputArchive ia = BinaryInputArchive.getArchive(is);

            FileSnap fileSnap = new FileSnap(null);

            DataTree dataTree = new DataTree();
            Map<Long, Integer> sessions = new HashMap<>();
            long now = Time.currentWallTime();
            fileSnap.deserialize(dataTree, sessions, ia);
            System.out.println("Deserialize snapshot spent time (ms): " + (Time.currentWallTime() - now));

            long fileNameZxid = Util.getZxidFromName(snapshotFile.getName(), SNAPSHOT_FILE_PREFIX);

            if (dumpJson) {
                printSnapshotJson(dataTree);
            } else if (largestThreshold != null || byteThreshold != null) {
                printBigZnodeSummary(dataTree, largestThreshold, byteThreshold);
            } else {
                printDetails(dataTree, sessions, dumpData, fileNameZxid);
            }
        }
    }

    private void printDetails(DataTree dataTree, Map<Long, Integer> sessions, boolean dumpData, long fileNameZxid) {
        long dtZxid = printZnodeDetails(dataTree, dumpData);
        printSessionDetails(dataTree, sessions);
        DataTree.ZxidDigest targetZxidDigest = dataTree.getDigestFromLoadedSnapshot();
        if (targetZxidDigest != null) {
            System.out.println(String.format("Target zxid digest is: %s, %s",
                    Long.toHexString(targetZxidDigest.zxid), targetZxidDigest.digest));
        }
        System.out.println(String.format("----%nLast zxid: 0x%s", Long.toHexString(Math.max(fileNameZxid, dtZxid))));
    }

    private long printZnodeDetails(DataTree dataTree, boolean dumpData) {
        System.out.println(String.format("ZNode Details (count=%d):", dataTree.getNodeCount()));

        final long zxid = printZnode(dataTree, "/", dumpData);
        System.out.println("----");
        return zxid;
    }

    private long printZnode(DataTree dataTree, String name, boolean dumpData) {
        System.out.println("----");
        DataNode n = dataTree.getNode(name);
        Set<String> children;
        long zxid;
        synchronized (n) { // keep findbugs happy
            System.out.println(name);
            printStat(n.stat);
            zxid = Math.max(n.stat.getMzxid(), n.stat.getPzxid());
            if (dumpData) {
                System.out.println("  data = " + (n.data == null ? "" : checkNullToEmpty(n.data)));
            } else {
                System.out.println("  dataLength = " + (n.data == null ? 0 : n.data.length));
            }
            children = n.getChildren();
        }
        if (children != null) {
            for (String child : children) {
                long cxid = printZnode(dataTree, name + (name.equals("/") ? "" : "/") + child, dumpData);
                zxid = Math.max(zxid, cxid);
            }
        }
        return zxid;
    }

    private void printSessionDetails(DataTree dataTree, Map<Long, Integer> sessions) {
        System.out.println("Session Details (sid, timeout, ephemeralCount):");
        for (Map.Entry<Long, Integer> e : sessions.entrySet()) {
            long sid = e.getKey();
            System.out.println(String.format("%#016x, %d, %d", sid, e.getValue(), dataTree.getEphemerals(sid).size()));
        }
    }

    private void printStat(StatPersisted stat) {
        printHex("cZxid", stat.getCzxid());
        System.out.println("  ctime = " + new Date(stat.getCtime()).toString());
        printHex("mZxid", stat.getMzxid());
        System.out.println("  mtime = " + new Date(stat.getMtime()).toString());
        printHex("pZxid", stat.getPzxid());
        System.out.println("  cversion = " + stat.getCversion());
        System.out.println("  dataVersion = " + stat.getVersion());
        System.out.println("  aclVersion = " + stat.getAversion());
        printHex("ephemeralOwner", stat.getEphemeralOwner());
    }

    private void printHex(String prefix, long value) {
        System.out.println(String.format("  %s = %#016x", prefix, value));
    }

    private void printSnapshotJson(final DataTree dataTree) {
        JsonStringEncoder encoder = JsonStringEncoder.getInstance();
        System.out.printf(
            "[1,0,{\"progname\":\"SnapshotFormatter.java\",\"progver\":\"0.01\",\"timestamp\":%d}",
            System.currentTimeMillis());
        printZnodeJson(dataTree, "/", encoder);
        System.out.print("]");
        System.out.println();
    }

    private void printZnodeJson(final DataTree dataTree, final String fullPath, JsonStringEncoder encoder) {


        final DataNode n = dataTree.getNode(fullPath);

        if (null == n) {
            System.err.println("DataTree Node for " + fullPath + " doesn't exist");
            return;
        }

        final String name = fullPath.equals("/")
            ? fullPath
            : fullPath.substring(fullPath.lastIndexOf("/") + 1);

        System.out.print(",");

        int dataLen;
        synchronized (n) { // keep findbugs happy
            dataLen = (n.data == null) ? 0 : n.data.length;
        }
        StringBuilder nodeSB = new StringBuilder();
        nodeSB.append("{");
        nodeSB.append("\"name\":\"").append(encoder.quoteAsString(name)).append("\"").append(",");
        nodeSB.append("\"asize\":").append(dataLen).append(",");
        nodeSB.append("\"dsize\":").append(dataLen).append(",");
        nodeSB.append("\"dev\":").append(0).append(",");
        nodeSB.append("\"ino\":").append(++INODE_IDX);
        nodeSB.append("}");

        Set<String> children;
        synchronized (n) { // keep findbugs happy
            children = n.getChildren();
        }
        if (children != null && children.size() > 0) {
            System.out.print("[" + nodeSB);
            for (String child : children) {
                printZnodeJson(dataTree, fullPath + (fullPath.equals("/") ? "" : "/") + child, encoder);
            }
            System.out.print("]");
        } else {
            System.out.print(nodeSB);
        }
    }

    private void printBigZnodeSummary(DataTree dataTree, Integer largestThreshold, Integer byteThreshold) {
        // If the data set is very very large, Heapsort may be better
        TreeSet<BigZnode> treeSet = new TreeSet<>(
                Comparator.comparingInt(BigZnode::getDataLength).reversed()
                        .thenComparing(BigZnode::getPath));

        printBigZnodeDetail(dataTree, "/", treeSet);
        System.out.println("path" + "\t" + "bytes");
        int count = 0;
        for (BigZnode znode : treeSet) {
            if (largestThreshold != null) {
                if (count >= largestThreshold) {
                    break;
                }
            }
            if (byteThreshold != null) {
                if (znode.getDataLength() < byteThreshold) {
                    break;
                }
            }
            System.out.println(znode.getPath() + "\t" + znode.getDataLength());
            count++;
        }
        System.out.println(String.format("DataTree count=%d, selected znodes count=%d",
        dataTree.getNodeCount(), count));
    }

    private void printBigZnodeDetail(DataTree dataTree, String path, TreeSet<BigZnode> treeSet) {
        DataNode n = dataTree.getNode(path);
        Set<String> children;
        synchronized (n) { // keep findbugs happy
            int dataLength = (n.data == null) ? 0 : n.data.length;
            treeSet.add(new BigZnode(path, dataLength));
            children = n.getChildren();
        }
        if (children != null) {
            for (String child : children) {
                printBigZnodeDetail(dataTree, path + (path.equals("/") ? "" : "/") + child, treeSet);
            }
        }
    }

    static class BigZnode {
        private String path;
        private Integer dataLength;

        public BigZnode(String path, Integer dataLength) {
            this.path = path;
            this.dataLength = dataLength;
        }

        public String getPath() {
            return path;
        }

        public Integer getDataLength() {
            return dataLength;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            BigZnode that = (BigZnode) o;

            if (!path.equals(that.path)) {
                return false;
            }
            return dataLength.equals(that.dataLength);
        }

        @Override
        public int hashCode() {
            int result = path.hashCode();
            result = 31 * result + dataLength.hashCode();
            return result;
        }
    }

}
