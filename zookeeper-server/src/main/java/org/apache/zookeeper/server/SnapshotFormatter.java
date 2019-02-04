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

package org.apache.zookeeper.server;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.InputArchive;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.ZKUtil;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.server.persistence.FileSnap;
import org.apache.zookeeper.server.persistence.Util;
import org.json.simple.JSONValue;

import static org.apache.zookeeper.server.persistence.FileSnap.SNAPSHOT_FILE_PREFIX;

/**
 * Dump a snapshot file to stdout.
 *
 * For JSON format, followed https://dev.yorhel.nl/ncdu/jsonfmt
 */
@InterfaceAudience.Public
public class SnapshotFormatter {

    // per-znode counter so ncdu treats each as a unique object
    private static Integer INODE_IDX = 1000;

    /**
     * USAGE: SnapshotFormatter snapshot_file
     */
    public static void main(String[] args) throws Exception {
        String snapshotFile = null;
        boolean dumpData = false;
        boolean dumpJson = false;

        int i;
        for (i = 0; i < args.length; i++) {
            if (args[i].equals("-d")) {
                dumpData = true;
            } else if (args[i].equals("-json")) {
                dumpJson = true;
            } else {
                snapshotFile = args[i];
                i++;
                break;
            }
        }
        if (args.length != i || snapshotFile == null) {
            System.err.println("USAGE: SnapshotFormatter [-d|-json] snapshot_file");
            System.err.println("       -d dump the data for each znode");
            System.err.println("       -json dump znode info in json format");
            System.exit(ExitCode.INVALID_INVOCATION.getValue());
        }
        
        String error = ZKUtil.validateFileInput(snapshotFile);
        if (null != error) {
            System.err.println(error);
            System.exit(ExitCode.INVALID_INVOCATION.getValue());
        }
        
        if (dumpData && dumpJson) {
            System.err.println("Cannot specify both data dump (-d) and json mode (-json) in same call");
            System.exit(ExitCode.INVALID_INVOCATION.getValue());
        }

        new SnapshotFormatter().run(snapshotFile, dumpData, dumpJson);
    }

    public void run(String snapshotFileName, boolean dumpData, boolean dumpJson)
        throws IOException {
        File snapshotFile = new File(snapshotFileName);
        try (InputStream is = new CheckedInputStream(
                new BufferedInputStream(new FileInputStream(snapshotFileName)),
                new Adler32())) {
            InputArchive ia = BinaryInputArchive.getArchive(is);

            FileSnap fileSnap = new FileSnap(null);

            DataTree dataTree = new DataTree();
            Map<Long, Integer> sessions = new HashMap<Long, Integer>();

            fileSnap.deserialize(dataTree, sessions, ia);
            long fileNameZxid = Util.getZxidFromName(snapshotFile.getName(), SNAPSHOT_FILE_PREFIX);

            if (dumpJson) {
                printSnapshotJson(dataTree);
            } else {
                printDetails(dataTree, sessions, dumpData, fileNameZxid);
            }
        }
    }

    private void printDetails(
        DataTree dataTree, Map<Long, Integer> sessions,
        boolean dumpData, long fileNameZxid
    ) {
        long dtZxid = printZnodeDetails(dataTree, dumpData);
        printSessionDetails(dataTree, sessions);
        System.out.println(String.format("----%nLast zxid: 0x%s", Long.toHexString(Math.max(fileNameZxid, dtZxid))));
    }

    private long printZnodeDetails(DataTree dataTree, boolean dumpData) {
        System.out.println(String.format(
            "ZNode Details (count=%d):",
            dataTree.getNodeCount()
        ));

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
                System.out.println("  data = " + (n.data == null ? "" :
                                                      Base64.getEncoder().encodeToString(n.data)));
            } else {
                System.out.println("  dataLength = "
                                       + (n.data == null ? 0 : n.data.length));
            }
            children = n.getChildren();
        }
        if (children != null) {
            for (String child : children) {
                long cxid = printZnode(
                    dataTree,
                    name + (name.equals("/") ? "" : "/") + child,
                    dumpData
                );
                zxid = Math.max(zxid, cxid);
            }
        }
        return zxid;
    }

    private void printSessionDetails(DataTree dataTree, Map<Long, Integer> sessions) {
        System.out.println("Session Details (sid, timeout, ephemeralCount):");
        for (Map.Entry<Long, Integer> e : sessions.entrySet()) {
            long sid = e.getKey();
            System.out.println(String.format("%#016x, %d, %d",
                                             sid, e.getValue(), dataTree.getEphemerals(sid).size()
            ));
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
        System.out.printf("[1,0,{\"progname\":\"SnapshotFormatter.java\",\"progver\":\"0.01\",\"timestamp\":%d}", System.currentTimeMillis());
        printZnodeJson(dataTree, "/");
        System.out.print("]");
    }

    private void printZnodeJson(final DataTree dataTree, final String fullPath) {

        final DataNode n = dataTree.getNode(fullPath);

        if (null == n) {
            System.err.println("DataTree Node for " + fullPath + " doesn't exist");
            return;
        }

        final String name = fullPath.equals("/") ? fullPath : fullPath.substring(fullPath.lastIndexOf(
            "/") + 1);

        System.out.print(",");

        int dataLen;
        synchronized (n) { // keep findbugs happy
            dataLen = (n.data == null) ? 0 : n.data.length;
        }
        StringBuilder nodeSB = new StringBuilder();
        nodeSB.append("{");
        nodeSB.append("\"name\":\"").append(JSONValue.escape(name)).append("\"").append(",");
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
                printZnodeJson(
                    dataTree,
                    fullPath + (fullPath.equals("/") ? "" : "/") + child
                );
            }
            System.out.print("]");
        } else {
            System.out.print(nodeSB);
        }
    }
}
