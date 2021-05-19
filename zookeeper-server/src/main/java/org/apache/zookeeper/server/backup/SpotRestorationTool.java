package org.apache.zookeeper.server.backup;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a tool for automatic zk path-based restoration of ZK data based on a zk node path using a collection of
 * snapshot and transaction logs.
 */
public class SpotRestorationTool {
  private static final Logger LOG = LoggerFactory.getLogger(SpotRestorationTool.class);

  private ZooKeeper zk;
  private FileTxnSnapLog snapLog;
  private boolean restoreRecursively;
  private String targetZNodePath;
  // A list storing messages about skipped nodes, which will be shown to the user at the end of spot restoration
  private List<String> messages;

  /**
   * Constructor
   * @param dataDir The directory contains fully-restored snapshot and transaction log files to be used for spot restoration
   * @param zk A client connection to the zk server to be restored
   * @param targetZNodePath The znode path to restore
   * @param restoreRecursively If true then restore the whole sub-data tree of the target znode;
   *                           if false then restore the target znode only
   * @throws IOException
   */
  public SpotRestorationTool(File dataDir, ZooKeeper zk, String targetZNodePath,
      boolean restoreRecursively) throws IOException {
    if (dataDir == null || !dataDir.exists()) {
      throw new IllegalArgumentException(
          "The provided dataDir is null or does not exist, please check the input.");
    }
    this.zk = zk;
    this.snapLog = new FileTxnSnapLog(dataDir, dataDir);
    this.targetZNodePath = targetZNodePath;
    this.restoreRecursively = restoreRecursively;
    this.messages = Lists.newArrayList();
  }

  /**
   * Run the spot restoration.
   * 1. If a node exists in restored data tree but not in the zk server, create the node in the server
   * 2. If a node exists in zk server but not the restored data tree, show messages that inform user
   *  to manually delete the node after this spot restoration run
   * 3. If a node exists in both zk server and restored data tree, show message that inform user to
   *  manually delete the node after this spot restoration run and run spot restoration on the node path again
   * @throws IOException
   */
  public void run() throws IOException {
    LOG.info(
        "Starting spot restoration for znode path " + targetZNodePath + ", using data provided in "
            + snapLog.getDataDir().getPath());
    DataTree dataTree = new DataTree();
    long zxidRestored = snapLog.restore(dataTree, new ConcurrentHashMap<>(), (hdr, rec, digest) -> {
      // Do nothing since we are trying to build a data tree in memory, not in actual zk server
    });

    DataNode restoredTargetNode = dataTree.getNode(targetZNodePath);
    if (restoredTargetNode == null) {
      LOG.info(
          "The target znode path does not exist in the restored zk data tree. Exit spot restoration.");
      return;
    }
    Stat restoredTargetNodeStat = new Stat();
    restoredTargetNode.copyStat(restoredTargetNodeStat);

    LOG.info(
        "The restored zk data tree is constructed. The highest zxid restored is: " + zxidRestored);
    LOG.info("Restored target node data: " + Arrays.toString(restoredTargetNode.getData()));
    LOG.info("Restored target node children: " + Arrays
        .toString(restoredTargetNode.getChildren().toArray()));
    LOG.info("Restored target node stat: " + restoredTargetNodeStat.toString());
    String requestMsg = "Do you want to restore to this point? Enter \"yes\" or \"no\".";
    String yesMsg = "Performing spot restoration of the ZNode path: " + targetZNodePath;
    String noMsg =
        "Spot restoration aborted. No change was made to the ZNode path: ." + targetZNodePath;
    if (getUserConfirmation(requestMsg, yesMsg, noMsg)) {
      recursiveRestore(zk, dataTree, targetZNodePath, true);
      printExitMessages();
    }
  }

  /**
   * 1. Restore the node value in zk server;
   * 2. check if there are nodes that exist in zk server but not in restored data tree in its child nodes;
   * 3. Do the above operations for all of its child nodes in the restored data tree
   * @param zk A client connection to zk server
   * @param dataTree The restored zk data tree
   * @param path The znode path to restore
   * @param shouldOverwrite If the node exist: true if the node will be overwritten;
   *                       false if the node will be skipped
   */
  private void recursiveRestore(ZooKeeper zk, DataTree dataTree, String path,
      boolean shouldOverwrite) {
    if (!singleNodeRestore(zk, dataTree, path, shouldOverwrite)) {
      // This node is skipped, there's no need to traverse its child nodes
      return;
    }
    if (!restoreRecursively) {
      // Non-recursive spot restoration, so no need to proceed further
      return;
    }

    // Find child nodes in restored data tree
    DataNode node = dataTree.getNode(path);
    Set<String> childrenSet = node.getChildren();
    String[] children = childrenSet.toArray(new String[0]);

    // Find child nodes that's in zk server but not in restored data tree
    try {
      List<String> destChildren = zk.getChildren(path, false);
      for (String destChild : destChildren) {
        if (!childrenSet.contains(destChild)) {
          String nodePath = path + "/" + destChild;
          messages.add(nodePath + ": This node does not exist in the restored data tree.");
        }
      }
    } catch (Exception e) {
      skipNodeOrStopRestoration(path, e);
    }

    // Traverse the child nodes of this node
    for (String child : children) {
      recursiveRestore(zk, dataTree, path + "/" + child, false);
    }
  }

  /**
   * Restore the znode in the zk server, currently only doing creation of a single previously non-existent node,
   * and only supports persistent nodes
   * @param zk A client connection to zk server
   * @param dataTree The restored zk data tree
   * @param path The znode path to restore
   * @param shouldOverwrite If the node exist: true if the node will be overwritten;
   *                        false if the node will be skipped
   * @return True if the node is successfully restored, false if the node is skipped
   */
  private boolean singleNodeRestore(ZooKeeper zk, DataTree dataTree, String path,
      boolean shouldOverwrite) {
    DataNode node = dataTree.getNode(path);
    try {
      if (zk.exists(path, false) == null) {
        Stat stat = new Stat();
        node.copyStat(stat);
        if (stat.getEphemeralOwner() != 0) {
          messages.add(path
              + ": The node is an ephemeral node, which is not supported in spot restoration.");
        } else {
          return createNode(path, dataTree);
        }
      } else if (shouldOverwrite) {
        // If it is the originally target node path that the user wants to restore,
        //we don't skip it just because it already exists in the server,
        //because we already get user's confirmation to restore this node
        //and we need to get to its child nodes
        zk.setData(path, node.getData(), -1);
        return true;
      } else {
        messages.add(path + ": The node already exists in zk server.");
      }
    } catch (Exception e) {
      skipNodeOrStopRestoration(path, e);
    }
    return false;
  }

  @VisibleForTesting
  protected boolean getUserConfirmation(String requestMsg, String yesMsg, String noMsg) {
    Scanner scanner = new Scanner(System.in);
    int cnt = 3;
    while (cnt > 0) {
      System.out.println(requestMsg);
      String input = scanner.nextLine().toLowerCase();
      switch (input) {
        case "yes":
          System.out.println(yesMsg);
          return true;
        case "no":
          System.out.println(noMsg);
          return false;
        default:
          System.err.println("Could not recognize the input: " + input + ". Please try again.");
          cnt--;
          break;
      }
    }
    System.err.println("Could not recognize user's input for the request: " + requestMsg
        + ". Exiting spot restoration...");
    printExitMessages();
    System.exit(1);
    return false;
  }

  private void skipNodeOrStopRestoration(String errorNodePath, Exception exception) {
    String errorMsg = exception.toString() + "\n";
    String requestMsg =
        errorMsg + "Do you want to continue the spot restoration? Enter \"yes\" to skip this node "
            + errorNodePath
            + ", and continue the spot restoration for the other nodes; enter \"no\" to stop the spot restoration.";
    String yesMsg =
        "Skipping node " + errorNodePath + ". Continuing spot restoration for other nodes.";
    String noMsg = "Spot restoration is stopped. Reason: " + errorMsg;
    if (!getUserConfirmation(requestMsg, yesMsg, noMsg)) {
      printExitMessages();
      System.exit(1);
    } else {
      messages.add(errorNodePath + ": " + errorMsg);
    }
  }

  /**
   * Print out all the messages about skipped nodes during restoration
   */
  private void printExitMessages() {
    LOG.info("Spot restoration for " + targetZNodePath + " was successfully done.");
    if (!messages.isEmpty()) {
      LOG.warn("During the spot restoration, the following nodes were skipped.");
      messages.forEach(System.err::println);
      LOG.warn("Please examine the above nodes and take appropriate actions.");
    }
  }

  /**
   * Create the nodes which do not exist in the zk server
   * @param path The node path
   * @param dataTree The restored zk data tree
   * @return True if node is successfully created, otherwise false
   * @throws KeeperException
   * @throws InterruptedException
   */
  private boolean createNode(String path, DataTree dataTree)
      throws KeeperException, InterruptedException {
    DataNode node = dataTree.getNode(path);
    List<ACL> acls = dataTree.getACL(node);
    if (acls == null || acls.isEmpty()) {
      acls = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    }
    boolean retry = false;
    do {
      try {
        zk.create(path, node.getData(), acls, CreateMode.PERSISTENT);
        return true;
      } catch (KeeperException e) {
        if (e.code().equals(KeeperException.Code.NONODE)) { // Parent node does not exist
          String requestMsg = "The parent node for node " + path
              + " does not exist, do you want to create its parent node(s)? Enter \"yes\" to create,"
              + " enter \"no\" to skip this node or exit the spot restoration.";
          String yesMsg = "Creating parent node(s) for " + path;
          String noMsg = "Skip node " + path
              + " or exit the spot restoration due to non-existent parent node.";
          if (getUserConfirmation(requestMsg, yesMsg, noMsg)) {
            String parentPath = path.substring(0, path.lastIndexOf('/'));
            retry = createNode(parentPath, dataTree);
          } else {
            skipNodeOrStopRestoration(path, e);
          }
        } else if (e.code().equals(KeeperException.Code.NODEEXISTS)) {
          messages.add(path + ": The node already exists in zk server.");
        } else {
          throw e;
        }
      }
    } while (retry);
    return false;
  }
}
