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

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.InputArchive;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.server.persistence.FileSnap;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;

/**
 * Recursively processes a snapshot file collecting child node count and summarizes the data size
 * below each node.
 * "starting_node" defines the node where the recursion starts
 * "max_depth" defines the depth where the tool still writes to the output.
 * 0 means there is no depth limit, every node's stats will be displayed, 1 means it will
 * only contain the starting node's and it's children's stats, 2 ads another level and so on.
 * This ONLY affects the level of details displayed, NOT the calculation.
 */
@InterfaceAudience.Public public class SnapshotSumFormatter {

  /**
   * USAGE: SnapshotSumFormatter snapshot_file starting_node max_depth
   *
   */
  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      System.err.println("USAGE: SnapshotSumFormatter snapshot_file starting_node max_depth");
      System.exit(2);
    }
    int maxDepth = 0;
    try {
      maxDepth = Integer.valueOf(args[2]).intValue();
    } catch (NumberFormatException e) {
      System.err.println("USAGE: SnapshotSumFormatter snapshot_file starting_node max_depth");
      System.exit(2);
    }

    new SnapshotSumFormatter().run(args[0], args[1], maxDepth);
  }

  public void run(String snapshotFileName, String startingNode, int maxDepth) throws IOException {
    InputStream is =
        new CheckedInputStream(new BufferedInputStream(new FileInputStream(snapshotFileName)),
            new Adler32());
    InputArchive ia = BinaryInputArchive.getArchive(is);

    FileSnap fileSnap = new FileSnap(null);

    DataTree dataTree = new DataTree();
    Map<Long,Integer> sessions = new HashMap<Long,Integer>();

    fileSnap.deserialize(dataTree, sessions, ia);

    printZnodeDetails(dataTree, startingNode, maxDepth);
  }

  private void printZnodeDetails(DataTree dataTree, String startingNode, int maxDepth) {
    StringBuilder builder = new StringBuilder();
    printZnode(dataTree, startingNode, builder, 0, maxDepth);
    System.out.println(builder);
  }

  private long[] printZnode(DataTree dataTree, String name, StringBuilder builder, int level,
      int maxDepth) {
    DataNode n = dataTree.getNode(name);
    Set<String> children;
    long dataSum = 0l;
    synchronized (n) { // keep findbugs happy
      if (n.data != null) {
        dataSum += n.data.length;
      }
      children = n.getChildren();
    }

    long[] result = {1l, dataSum};
    if (children.size() == 0) {
      return result;
    }
    StringBuilder childBuilder = new StringBuilder();
    for (String child : children) {
      long[] childResult =
          printZnode(dataTree, name + (name.equals("/") ? "" : "/") + child, childBuilder,
              level + 1, maxDepth);
      result[0] = result[0] + childResult[0];
      result[1] = result[1] + childResult[1];
    }

    if (maxDepth == 0 || level <= maxDepth) {
      String tab = String.join("", Collections.nCopies(level, "--"));
      builder.append(tab + " " + name + "\n");
      builder.append(tab + "   children: " + (result[0] - 1) + "\n");
      builder.append(tab + "   data: " + result[1] + "\n");
      builder.append(childBuilder);
    }
    return result;
  }
}
