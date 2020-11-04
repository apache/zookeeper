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

package org.apache.zookeeper.server;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.zip.CheckedInputStream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.InputArchive;
import org.apache.zookeeper.server.persistence.FileSnap;
import org.apache.zookeeper.server.persistence.SnapStream;
import org.apache.zookeeper.util.ServiceUtils;

/**
 * SnapshotComparer is a tool that loads and compares two snapshots with configurable threshold and various filters, and outputs information about the delta.
 * The delta includes specific znode paths added, updated, deleted comparing one snapshot to another.
 * It's useful in use cases that involve snapshot analysis, such as offline data consistency checking, and data trending analysis (e.g. what's growing under which zNode path during when).
 * Only outputs information about permanent nodes, ignoring both sessions and ephemeral nodes.
 */
public class SnapshotComparer {
  private final Options options;
  private static final String leftOption = "left";
  private static final String rightOption = "right";
  private static final String byteThresholdOption = "bytes";
  private static final String nodeThresholdOption = "nodes";
  private static final String debugOption = "debug";
  private static final String interactiveOption = "interactive";

  @SuppressWarnings("static")
  private SnapshotComparer() {
    options = new Options();
    options.addOption(
        Option.builder("l")
            .hasArg()
            .required(true)
            .longOpt(leftOption)
            .desc("(Required) The left snapshot file.")
            .argName("LEFT")
            .type(File.class)
            .build());
    options.addOption(
        Option.builder("r")
            .hasArg()
            .required(true)
            .longOpt(rightOption)
            .desc("(Required) The right snapshot file.")
            .argName("RIGHT")
            .type(File.class)
            .build());
    options.addOption(
        Option.builder("b")
            .hasArg()
            .required(true)
            .longOpt(byteThresholdOption)
            .desc("(Required) The node data delta size threshold, in bytes, for printing the node.")
            .argName("BYTETHRESHOLD")
            .type(String.class)
            .build());
    options.addOption(
        Option.builder("n")
            .hasArg()
            .required(true)
            .longOpt(nodeThresholdOption)
            .desc("(Required) The descendant node delta size threshold, in nodes, for printing the node.")
            .argName("NODETHRESHOLD")
            .type(String.class)
            .build());
    options.addOption("d", debugOption, false, "Use debug output.");
    options.addOption("i", interactiveOption, false, "Enter interactive mode.");
  }

  private void usage() {
    HelpFormatter help = new HelpFormatter();

    help.printHelp(
        120,
        "java -cp <classPath> " + SnapshotComparer.class.getName(),
        "",
        options,
        "");
  }

  public static void main(String[] args) throws Exception {
    SnapshotComparer app = new SnapshotComparer();
    app.compareSnapshots(args);
  }

  private void compareSnapshots(String[] args) throws Exception {
    CommandLine parsedOptions;
    try {
      parsedOptions = new DefaultParser().parse(options, args);
    } catch (ParseException e) {
      System.err.println(e.getMessage());
      usage();
      ServiceUtils.requestSystemExit(ExitCode.INVALID_INVOCATION.getValue());
      return;
    }

    File left = (File) parsedOptions.getParsedOptionValue(leftOption);
    File right = (File) parsedOptions.getParsedOptionValue(rightOption);
    int byteThreshold = Integer.parseInt((String) parsedOptions.getParsedOptionValue(byteThresholdOption));
    int nodeThreshold = Integer.parseInt((String) parsedOptions.getParsedOptionValue(nodeThresholdOption));
    boolean debug = parsedOptions.hasOption(debugOption);
    boolean interactive = parsedOptions.hasOption(interactiveOption);
    System.out.println("Successfully parsed options!");
    TreeInfo leftTree = new TreeInfo(left);
    TreeInfo rightTree = new TreeInfo(right);

    System.out.println(leftTree.toString());
    System.out.println(rightTree.toString());

    compareTrees(leftTree, rightTree, byteThreshold, nodeThreshold, debug, interactive);
  }

  private static class TreeInfo {
    public static class TreeNode {
      final String label;
      final long size;
      final List<TreeNode> children;
      long descendantSize;
      long descendantCount;

      public static class AlphabeticComparator implements Comparator<TreeNode>, Serializable {
        private static final long serialVersionUID = 2601197766392565593L;

        public int compare(TreeNode left, TreeNode right) {
          if (left == right) {
            return 0;
          }
          if (left == null) {
            return -1;
          }
          if (right == null) {
            return 1;
          }
          return left.label.compareTo(right.label);
        }
      }

      public TreeNode(String label, long size) {
        this.label = label;
        this.size = size;
        this.children = new ArrayList<TreeNode>();
      }

      void populateChildren(String path, DataTree dataTree, TreeInfo treeInfo) throws Exception {
        populateChildren(path, dataTree, treeInfo, 1);
      }

      void populateChildren(String path, DataTree dataTree, TreeInfo treeInfo, int currentDepth) throws Exception {
        List<String> childLabels = null;
        childLabels = dataTree.getChildren(path, null, null);

        if (childLabels != null && !childLabels.isEmpty()) {
          for (String childName : childLabels){
            String childPath = path + "/" + childName;
            DataNode childNode = dataTree.getNode(childPath);
            long size;
            synchronized (childNode) {
              size = childNode.data == null ? 0 : childNode.data.length;
            }
            TreeNode childTreeNode = new TreeNode(childPath, size);
            childTreeNode.populateChildren(childPath, dataTree, treeInfo, currentDepth + 1);
            children.add(childTreeNode);
          }
        }
        descendantSize = 0;
        descendantCount = 0;
        for (TreeNode child : children) {
          descendantSize += child.descendantSize;
          descendantCount += child.descendantCount;
        }
        descendantSize += this.size;
        descendantCount += this.children.size();

        treeInfo.registerNode(this, currentDepth);
      }
    }

    final TreeNode root;
    long count;
    List<ArrayList<TreeNode>> nodesAtDepths = new ArrayList<ArrayList<TreeNode>>();
    Map<String, TreeNode> nodesByName = new HashMap<String, TreeNode>();

    TreeInfo(File snapshot) throws Exception {
      DataTree dataTree = getSnapshot(snapshot);

      count = 0;
      long beginning = System.nanoTime();
      DataNode root = dataTree.getNode("");
      long size = root.data == null ? 0 : root.data.length;
      this.root = new TreeNode("", size);
      // Construct TreeInfo tree from DataTree
      this.root.populateChildren("", dataTree, this);
      long end = System.nanoTime();

      System.out.println(String.format("Processed data tree in %f seconds",
          ((((double) end - beginning) / 1000000)) / 1000));
    }

    void registerNode(TreeNode node, int depth) {
      while (depth > nodesAtDepths.size()) {
        nodesAtDepths.add(new ArrayList<TreeNode>());
      }
      nodesAtDepths.get(depth - 1).add(node);
      nodesByName.put(node.label, node);

      this.count++;
    }

    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append(String.format("Node count: %d%n", count));
      builder.append(String.format("Total size: %d%n", root.descendantSize));
      builder.append(String.format("Max depth: %d%n", nodesAtDepths.size()));
      for (int i = 0; i < nodesAtDepths.size(); i++) {
        builder.append(String.format("Count of nodes at depth %d: %d%n", i, nodesAtDepths.get(i).size()));
      }
      return builder.toString();
    }

    public static Comparator<TreeNode> MakeAlphabeticComparator() {
      return new TreeNode.AlphabeticComparator();
    }
  }

  /**
   * Parse a Zookeeper snapshot file to DataTree
   * @param file the snapshot file
   * @throws Exception
   */
  private static DataTree getSnapshot(File file) throws Exception {
    FileSnap fileSnap = new FileSnap(null);
    DataTree dataTree = new DataTree();
    Map<Long, Integer> sessions = new HashMap<Long, Integer>();
    CheckedInputStream snapIS = SnapStream.getInputStream(file);

    long beginning = System.nanoTime();
    InputArchive ia = BinaryInputArchive.getArchive(snapIS);
    fileSnap.deserialize(dataTree, sessions, ia);
    long end = System.nanoTime();
    System.out.println(String.format("Deserialized snapshot in %s in %f seconds", file.getName(),
        (((double) (end - beginning) / 1000000)) / 1000));
    return dataTree;
  }

  private static void printThresholdInfo(int byteThreshold, int nodeThreshold) {
    System.out.println(String.format("Printing analysis for nodes difference larger than %d bytes or node count difference larger than %d.", byteThreshold, nodeThreshold));
  }

  private static void compareTrees(TreeInfo left, TreeInfo right, int byteThreshold, int nodeThreshold, boolean debug, boolean interactive) {
    int maxDepth = Math.max(left.nodesAtDepths.size(), right.nodesAtDepths.size());

    if (!interactive) {
      printThresholdInfo(byteThreshold, nodeThreshold);
      for (int i = 0; i < maxDepth; i++) {
        System.out.println(String.format("Analysis for depth %d", i));
        compareLine(left, right, i, byteThreshold, nodeThreshold, debug, interactive);
      }
    } else {
      // interactive mode
      Scanner scanner = new Scanner(System.in);
      int currentDepth = 0;
      while (currentDepth < maxDepth) {
        System.out.println(String.format("Current depth is %d", currentDepth));
        System.out.println("- Press enter to move to print current depth layer;\n- Type a number to jump to and print all nodes at a given depth;\n- Enter an ABSOLUTE path to print the immediate subtree of a node. Path must start with '/'.");
        String input = scanner.nextLine();
        printThresholdInfo(byteThreshold, nodeThreshold);
        if (input.isEmpty()) {
          // input is Enter
          System.out.println(String.format("Analysis for depth %d", currentDepth));
          compareLine(left, right, currentDepth, byteThreshold, nodeThreshold, debug, interactive);
          currentDepth++;
        } else {
          // input is a path
          if (input.startsWith("/")){
            System.out.println(String.format("Analysis for node %s", input));
            compareSubtree(left, right, input, byteThreshold, nodeThreshold, debug, interactive);
          } else {
            // input is a number
            try {
              int depth = Integer.parseInt(input);
              if (depth < 0 || depth >= maxDepth) {
                System.out.println(String.format("Depth must be in range [%d, %d]", 0, maxDepth - 1));
                continue;
              }
              currentDepth = depth;
              System.out.println(String.format("Analysis for depth %d", currentDepth));
              compareLine(left, right, currentDepth, byteThreshold, nodeThreshold, debug, interactive);
            } catch (NumberFormatException ex) {
              // input is invalid
              System.out.println(String.format("Input %s is not valid. Depth must be in range [%d, %d]. Path must be an absolute path which starts with '/'.", input, 0, maxDepth - 1));
            }
          }
        }
        System.out.println("");
      }
    }
    System.out.println("All layers compared.");
  }

  private static void compareSubtree(TreeInfo left, TreeInfo right, String path, int byteThreshold, int nodeThreshold, boolean debug, boolean interactive) {
    TreeInfo.TreeNode leftRoot = left.nodesByName.get(path);
    TreeInfo.TreeNode rightRoot = right.nodesByName.get(path);

    List<TreeInfo.TreeNode> leftList = leftRoot == null ? new ArrayList<TreeInfo.TreeNode>() : leftRoot.children;
    List<TreeInfo.TreeNode> rightList = rightRoot == null ? new ArrayList<TreeInfo.TreeNode>() : rightRoot.children;

    if (leftRoot == null && rightRoot == null) {
      System.out.println(String.format("Path %s is neither found in left tree nor right tree.", path));
    } else {
      compareNodes(leftList, rightList, byteThreshold, nodeThreshold, debug, interactive);
    }
  }

  /**
   * Compare left tree and right tree at the same depth.
   * @param left the left data tree
   * @param right the right data tree
   * @param depth the depth of the data tree to be compared at
   * @param byteThreshold the node data delta size threshold, in bytes, for printing the node
   * @param nodeThreshold the descendant node delta size threshold, in nodes, for printing the node
   * @param debug If true, print more detailed debug information
   * @param interactive If true, enter interactive mode
   */
  private static void compareLine(TreeInfo left, TreeInfo right, int depth, int byteThreshold, int nodeThreshold, boolean debug, boolean interactive) {
    List<TreeInfo.TreeNode> leftList = depth >= left.nodesAtDepths.size() ? new ArrayList<>() : left.nodesAtDepths.get(depth);
    List<TreeInfo.TreeNode> rightList = depth >= right.nodesAtDepths.size() ? new ArrayList<>() : right.nodesAtDepths.get(depth);

    compareNodes(leftList, rightList, byteThreshold, nodeThreshold, debug, interactive);
  }

  private static void compareNodes(List<TreeInfo.TreeNode> leftList, List<TreeInfo.TreeNode> rightList, int byteThreshold, int nodeThreshold, boolean debug, boolean interactive) {
    Comparator<TreeInfo.TreeNode> alphabeticComparator = TreeInfo.MakeAlphabeticComparator();
    Collections.sort(leftList, alphabeticComparator);
    Collections.sort(rightList, alphabeticComparator);

    int leftIndex = 0;
    int rightIndex = 0;

    boolean leftRemaining = leftList.size() > leftIndex;
    boolean rightRemaining = rightList.size() > rightIndex;
    while (leftRemaining || rightRemaining) {
      TreeInfo.TreeNode leftNode = null;
      if (leftRemaining) {
        leftNode = leftList.get(leftIndex);
      }

      TreeInfo.TreeNode rightNode = null;
      if (rightRemaining) {
        rightNode = rightList.get(rightIndex);
      }

      if (leftNode != null && rightNode != null) {
        if (debug) {
          System.out.println(String.format("Comparing %s to %s", leftNode.label, rightNode.label));
        }
        int result = leftNode.label.compareTo(rightNode.label);
        if (result < 0) {
          if (debug) {
            System.out.println("left is less");
          }
          printLeftOnly(leftNode, byteThreshold, nodeThreshold, debug, interactive);
          leftIndex++;
        } else if (result > 0) {
          if (debug) {
            System.out.println("right is less");
          }
          printRightOnly(rightNode, byteThreshold, nodeThreshold, debug, interactive);
          rightIndex++;
        } else {
          if (debug) {
            System.out.println("same");
          }
          printBoth(leftNode, rightNode, byteThreshold, nodeThreshold, debug, interactive);
          leftIndex++;
          rightIndex++;
        }
      } else if (leftNode != null) {
        printLeftOnly(leftNode, byteThreshold, nodeThreshold, debug, interactive);
        leftIndex++;
      } else {
        printRightOnly(rightNode, byteThreshold, nodeThreshold, debug, interactive);
        rightIndex++;
      }

      leftRemaining = leftList.size() > leftIndex;
      rightRemaining = rightList.size() > rightIndex;
    }
  }

  static void printLeftOnly(TreeInfo.TreeNode node, int byteThreshold, int nodeThreshold, boolean debug, boolean interactive) {
    if (node.descendantSize > byteThreshold || node.descendantCount > nodeThreshold) {
      StringBuilder builder = new StringBuilder();
      builder.append(String.format("Node %s found only in left tree. ", node.label));
      printNode(node, builder);
      System.out.println(builder.toString());
    } else if (debug || interactive) {
      System.out.println(String.format("Filtered left node %s of size %d", node.label, node.descendantSize));
    }
  }

  static void printRightOnly(TreeInfo.TreeNode node, int byteThreshold, int nodeThreshold, boolean debug, boolean interactive) {
    if (node.descendantSize > byteThreshold || node.descendantCount > nodeThreshold) {
      StringBuilder builder = new StringBuilder();
      builder.append(String.format("Node %s found only in right tree. ", node.label));
      printNode(node, builder);
      System.out.println(builder.toString());
    } else if (debug || interactive) {
      System.out.println(String.format("Filtered right node %s of size %d", node.label, node.descendantSize));
    }
  }

  static void printBoth(TreeInfo.TreeNode leftNode, TreeInfo.TreeNode rightNode, int byteThreshold, int nodeThreshold, boolean debug, boolean interactive) {
    if (Math.abs(rightNode.descendantSize - leftNode.descendantSize) > byteThreshold
        || Math.abs(rightNode.descendantCount - leftNode.descendantCount) > nodeThreshold) {
      System.out.println(String.format(
          "Node %s found in both trees. Delta: %d bytes, %d descendants",
          leftNode.label,
          rightNode.descendantSize - leftNode.descendantSize,
          rightNode.descendantCount - leftNode.descendantCount));
    } else if (debug || interactive) {
      System.out.println(String.format("Filtered node %s of left size %d, right size %d", leftNode.label, leftNode.descendantSize, rightNode.descendantSize));
    }
  }

  static void printNode(TreeInfo.TreeNode node, StringBuilder builder) {
    builder.append(String.format("Descendant size: %d. Descendant count: %d", node.descendantSize, node.descendantCount));
  }
}
