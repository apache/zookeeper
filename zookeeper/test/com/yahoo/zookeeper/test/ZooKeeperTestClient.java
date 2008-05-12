package com.yahoo.zookeeper.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.junit.Test;

import com.yahoo.zookeeper.KeeperException;
import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.ZooDefs;
import com.yahoo.zookeeper.ZooKeeper;
import com.yahoo.zookeeper.KeeperException.Code;
import com.yahoo.zookeeper.ZooDefs.Ids;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.proto.WatcherEvent;

public class ZooKeeperTestClient extends TestCase implements Watcher {
  protected String hostPort = "127.0.0.1:22801";

  protected static String dirOnZK = "/test_dir";

  protected String testDirOnZK = dirOnZK + "/" + System.currentTimeMillis();

  LinkedBlockingQueue<WatcherEvent> events = new LinkedBlockingQueue<WatcherEvent>();

  private WatcherEvent getEvent(int numTries) throws InterruptedException {
    WatcherEvent event = null;
    for (int i = 0; i < numTries; i++) {
      System.out.println("i = " + i);
      event = events.poll(10, TimeUnit.SECONDS);
      if (event != null) {
        break;
      }
      Thread.sleep(5000);
    }
    return event;

  }

  private void deleteZKDir(ZooKeeper zk, String nodeName)
          throws IOException, InterruptedException, KeeperException {

    Stat stat = zk.exists(nodeName, false);
    if (stat == null) {
      return;
    }

    ArrayList<String> children = zk.getChildren(nodeName, false);
    if (children.size() == 0) {
      zk.delete(nodeName, -1);
      return;
    }
    for (String n : children) {
      deleteZKDir(zk, n);
    }
  }

  private void checkRoot() throws IOException,
      InterruptedException {
    ZooKeeper zk = new ZooKeeper(hostPort, 10000, this);

    try {
      zk.create(dirOnZK, null, Ids.OPEN_ACL_UNSAFE, 0);
    } catch (KeeperException.NodeExistsException ke) {
        // expected, sort of
    } catch (KeeperException ke) {
        fail("Unexpected exception code for create " + dirOnZK + ": "
            + ke.getMessage());
    }

    try {
      zk.create(testDirOnZK, null, Ids.OPEN_ACL_UNSAFE, 0);
    } catch (KeeperException.NodeExistsException ke) {
        // expected, sort of
    } catch (KeeperException ke) {
        fail("Unexpected exception code for create " + testDirOnZK + ": "
            + ke.getMessage());
    }

    zk.close();
  }

  private void enode_test_1() throws IOException,
          InterruptedException, KeeperException {
    checkRoot();
    String parentName = testDirOnZK;
    String nodeName = parentName + "/enode_abc";
    ZooKeeper zk = new ZooKeeper(hostPort, 10000, this);

    Stat stat = zk.exists(parentName, false);
    if (stat == null) {
      try {
        zk.create(parentName, null, Ids.OPEN_ACL_UNSAFE, 0);
      } catch (KeeperException ke) {
        fail("Creating node " + parentName + ke.getMessage());
      }
    }

    try {
      zk.create(nodeName, null, Ids.OPEN_ACL_UNSAFE, ZooDefs.CreateFlags.EPHEMERAL);
    } catch (KeeperException ke) {
      int code = ke.getCode();
      boolean valid = code == KeeperException.Code.NodeExists;
      if (!valid) {
        fail("Unexpected exception code for createin: " + ke.getMessage());
      }
    }

    stat = zk.exists(nodeName, false);
    if (stat == null) {
      fail("node " + nodeName + " should exist");
    }
    long prevSessionId = zk.getSessionId();
    byte[] prevSessionPasswd = zk.getSessionPasswd();
    System.out.println("Closing client with sessioid:  " + prevSessionId);
    zk.close();
    zk = new ZooKeeper(hostPort, 10000, this);

    for (int i = 0; i < 10; i++) {
      System.out.println("i = " + i);
      stat = zk.exists(nodeName, false);
      if (stat != null) {
        System.out.println("node " + nodeName
            + " should not exist after reconnection close");
      } else {
        System.out.println("node " + nodeName
            + " is gone after reconnection close!");
        break;
      }
      Thread.sleep(5000);
    }
    deleteZKDir(zk, nodeName);
    zk.close();

  }

  private void enode_test_2() throws IOException,
          InterruptedException, KeeperException {
    checkRoot();
    String parentName = testDirOnZK;
    String nodeName = parentName + "/enode_abc";
    ZooKeeper zk = new ZooKeeper(hostPort, 10000, this);
    ZooKeeper zk_1 = new ZooKeeper(hostPort, 10000, this);

    Stat stat = zk_1.exists(parentName, false);
    if (stat == null) {
      try {
        zk.create(parentName, null, Ids.OPEN_ACL_UNSAFE, 0);
      } catch (KeeperException ke) {
        fail("Creating node " + parentName + ke.getMessage());
      }
    }

    stat = zk_1.exists(nodeName, false);
    if (stat != null) {

      try {
        zk.delete(nodeName, -1);
      } catch (KeeperException ke) {
        int code = ke.getCode();
        boolean valid = code == KeeperException.Code.NoNode
            || code == KeeperException.Code.NotEmpty;
        if (!valid) {
          fail("Unexpected exception code for delete: " + ke.getMessage());
        }
      }
    }

    ArrayList<String> firstGen = zk_1.getChildren(parentName, true);

    try {
      zk.create(nodeName, null, Ids.OPEN_ACL_UNSAFE, ZooDefs.CreateFlags.EPHEMERAL);
    } catch (KeeperException ke) {
      int code = ke.getCode();
      boolean valid = code == KeeperException.Code.NodeExists;
      if (!valid) {
        fail("Unexpected exception code for createin: " + ke.getMessage());
      }
    }

    Thread.sleep(5000);
    WatcherEvent event = events.poll(10, TimeUnit.SECONDS);
    if (event == null) {
      throw new IOException("No event was delivered promptly");
    }
    if (event.getType() != Watcher.Event.EventNodeChildrenChanged
        || !event.getPath().equalsIgnoreCase(parentName)) {
      fail("Unexpected event was delivered: " + event.toString());
    }

    stat = zk_1.exists(nodeName, false);
    if (stat == null) {
      fail("node " + nodeName + " should exist");
    }

    try {
      zk.delete(parentName, -1);
      fail("Should be impossible to delete a non-empty node " + parentName);
    } catch (KeeperException ke) {
      int code = ke.getCode();
      boolean valid = code == KeeperException.Code.NotEmpty;
      if (!valid) {
        fail("Unexpected exception code for delete: " + code);
      }
    }

    try {
      zk.create(nodeName + "/def", null, Ids.OPEN_ACL_UNSAFE, ZooDefs.CreateFlags.EPHEMERAL);
      fail("Should be impossible to create child off Ephemeral node "
          + nodeName);
    } catch (KeeperException ke) {
      int code = ke.getCode();
      boolean valid = code == KeeperException.Code.NoChildrenForEphemerals;
      if (!valid) {
        fail("Unexpected exception code for createin: " + code);
      }
    }

    try {
      ArrayList<String> children = zk.getChildren(nodeName, false);
      if (children.size() > 0) {
        fail("ephemeral node " + nodeName + " should not have children");
      }
    } catch (KeeperException ke) {
      int code = ke.getCode();
      boolean valid = code == KeeperException.Code.NoNode;
      if (!valid) {
        fail("Unexpected exception code for createin: " + code);
      }
    }
    firstGen = zk_1.getChildren(parentName, true);
    stat = zk_1.exists(nodeName, true);
    if (stat == null) {
      fail("node " + nodeName + " should exist");
    }
    System.out.println("session id of zk: " + zk.getSessionId());
    System.out.println("session id of zk_1: " + zk_1.getSessionId());
    zk.close();
  
    stat = zk_1.exists("nosuchnode", false);
    
    event = this.getEvent(10);
    if (event == null) {
      fail("First event was not delivered promptly");
    }
    if (!((event.getType() == Watcher.Event.EventNodeChildrenChanged && 
           event.getPath().equalsIgnoreCase(parentName)) ||
         (event.getType() == Watcher.Event.EventNodeDeleted && 
           event.getPath().equalsIgnoreCase(nodeName)))) {
      System.out.print(parentName + " " 
          + Watcher.Event.EventNodeChildrenChanged + " " + nodeName + " " + Watcher.Event.EventNodeDeleted);
      fail("Unexpected first event was delivered: " + event.toString());
    }

    event = this.getEvent(10);

    if (event == null) {
      fail("Second event was not delivered promptly");
    }
    if (!((event.getType() == Watcher.Event.EventNodeChildrenChanged && 
        event.getPath().equalsIgnoreCase(parentName)) ||
      (event.getType() == Watcher.Event.EventNodeDeleted && 
        event.getPath().equalsIgnoreCase(nodeName)))) {
      System.out.print(parentName + " " 
          + Watcher.Event.EventNodeChildrenChanged + " " + nodeName + " " + Watcher.Event.EventNodeDeleted);
      fail("Unexpected second event was delivered: " + event.toString());
    }

    firstGen = zk_1.getChildren(parentName, false);
    stat = zk_1.exists(nodeName, false);
    if (stat != null) {
      fail("node " + nodeName + " should have been deleted");
    }
    if (firstGen.contains(nodeName)) {
      fail("node " + nodeName + " should not be a children");
    }
    deleteZKDir(zk_1, nodeName);
    zk_1.close();
  }

  private void delete_create_get_set_test_1() throws
          IOException, InterruptedException, KeeperException {
    checkRoot();
    ZooKeeper zk = new ZooKeeper(hostPort, 10000, this);
    String parentName = testDirOnZK;
    String nodeName = parentName + "/benwashere";
    try {
      zk.delete(nodeName, -1);
    } catch (KeeperException ke) {
      int code = ke.getCode();
      boolean valid = code == KeeperException.Code.NoNode
          || code == KeeperException.Code.NotEmpty;
      if (!valid) {
        fail("Unexpected exception code for delete: " + ke.getMessage());
      }
    }
    try {
      zk.create(nodeName, null, Ids.OPEN_ACL_UNSAFE, 0);
    } catch (KeeperException ke) {
      int code = ke.getCode();
      boolean valid = code == KeeperException.Code.NodeExists;
      if (!valid) {
        fail("Unexpected exception code for create: " + ke.getMessage());
      }
    }
    try {
      zk.setData(nodeName, "hi".getBytes(), 5700);
      fail("Should have gotten BadVersion exception");
    } catch (KeeperException ke) {
      if (ke.getCode() != Code.BadVersion) {
        fail("Should have gotten BadVersion exception");
      }
    }
    zk.setData(nodeName, "hi".getBytes(), -1);
    Stat st = new Stat();
    byte[] bytes = zk.getData(nodeName, false, st);
    String retrieved = new String(bytes);
    if (!"hi".equals(retrieved)) {
      fail("The retrieved data [" + retrieved
          + "] is differented than the expected [hi]");
    }
    try {
      zk.delete(nodeName, 6800);
      fail("Should have gotten BadVersion exception");
    } catch (KeeperException ke) {
      int code = ke.getCode();
      boolean valid = code == KeeperException.Code.NotEmpty
          || code == KeeperException.Code.BadVersion;
      if (!valid) {
        fail("Unexpected exception code for delete: " + ke.getMessage());
      }
    }
    try {
      zk.delete(nodeName, -1);
    } catch (KeeperException ke) {
      int code = ke.getCode();
      boolean valid = code == KeeperException.Code.NotEmpty;
      if (!valid) {
        fail("Unexpected exception code for delete: " + code);
      }
    }
    deleteZKDir(zk, nodeName);
    zk.close();
  }

  @Test
  public void my_test_1() throws IOException,
          InterruptedException, KeeperException {
    enode_test_1();
    enode_test_2();
    delete_create_get_set_test_1();
  }

  synchronized public void process(WatcherEvent event) {
    try {
      System.out.println("Got an event " + event.toString());
      events.put(event);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public static void main(String[] args) {
    ZooKeeperTestClient zktc = new ZooKeeperTestClient();
    try {
      zktc.my_test_1();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}