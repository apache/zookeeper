/*
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

package org.apache.zookeeper.test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.Stat;

public class ZooKeeperTestClient extends ZKTestCase implements Watcher {

    protected String hostPort = "127.0.0.1:22801";

    protected static final String dirOnZK = "/test_dir";

    protected String testDirOnZK = dirOnZK + "/" + Time.currentElapsedTime();

    LinkedBlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<WatchedEvent>();

    private WatchedEvent getEvent(int numTries) throws InterruptedException {
        WatchedEvent event = null;
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

    private void deleteZKDir(ZooKeeper zk, String nodeName) throws IOException, InterruptedException, KeeperException {

        Stat stat = zk.exists(nodeName, false);
        if (stat == null) {
            return;
        }

        List<String> children1 = zk.getChildren(nodeName, false);
        List<String> c2 = zk.getChildren(nodeName, false, stat);

        if (!children1.equals(c2)) {
            fail("children lists from getChildren()/getChildren2() do not match");
        }

        if (!stat.equals(stat)) {
            fail("stats from exists()/getChildren2() do not match");
        }

        if (children1.size() == 0) {
            zk.delete(nodeName, -1);
            return;
        }
        for (String n : children1) {
            deleteZKDir(zk, n);
        }
    }

    private void checkRoot() throws IOException, InterruptedException {
        ZooKeeper zk = new ZooKeeper(hostPort, 10000, this);

        try {
            zk.create(dirOnZK, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException.NodeExistsException ke) {
            // expected, sort of
        } catch (KeeperException ke) {
            fail("Unexpected exception code for create " + dirOnZK + ": " + ke.getMessage());
        }

        try {
            zk.create(testDirOnZK, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException.NodeExistsException ke) {
            // expected, sort of
        } catch (KeeperException ke) {
            fail("Unexpected exception code for create " + testDirOnZK + ": " + ke.getMessage());
        }

        zk.close();
    }

    private void enode_test_1() throws IOException, InterruptedException, KeeperException {
        checkRoot();
        String parentName = testDirOnZK;
        String nodeName = parentName + "/enode_abc";
        ZooKeeper zk = new ZooKeeper(hostPort, 10000, this);

        Stat stat = zk.exists(parentName, false);
        if (stat == null) {
            try {
                zk.create(parentName, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            } catch (KeeperException ke) {
                fail("Creating node " + parentName + ke.getMessage());
            }
        }

        try {
            zk.create(nodeName, null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        } catch (KeeperException ke) {
            Code code = ke.code();
            boolean valid = code == KeeperException.Code.NODEEXISTS;
            if (!valid) {
                fail("Unexpected exception code for createin: " + ke.getMessage());
            }
        }

        stat = zk.exists(nodeName, false);
        if (stat == null) {
            fail("node " + nodeName + " should exist");
        }
        System.out.println("Closing client with sessionid: 0x" + Long.toHexString(zk.getSessionId()));
        zk.close();
        zk = new ZooKeeper(hostPort, 10000, this);

        for (int i = 0; i < 10; i++) {
            System.out.println("i = " + i);
            stat = zk.exists(nodeName, false);
            if (stat != null) {
                System.out.println("node " + nodeName + " should not exist after reconnection close");
            } else {
                System.out.println("node " + nodeName + " is gone after reconnection close!");
                break;
            }
            Thread.sleep(5000);
        }
        deleteZKDir(zk, nodeName);
        zk.close();

    }

    private void enode_test_2() throws IOException, InterruptedException, KeeperException {
        checkRoot();
        String parentName = testDirOnZK;
        String nodeName = parentName + "/enode_abc";
        ZooKeeper zk = new ZooKeeper(hostPort, 10000, this);
        ZooKeeper zk_1 = new ZooKeeper(hostPort, 10000, this);

        Stat stat_parent = zk_1.exists(parentName, false);
        if (stat_parent == null) {
            try {
                zk.create(parentName, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            } catch (KeeperException ke) {
                fail("Creating node " + parentName + ke.getMessage());
            }
        }

        Stat stat_node = zk_1.exists(nodeName, false);
        if (stat_node != null) {

            try {
                zk.delete(nodeName, -1);
            } catch (KeeperException ke) {
                Code code = ke.code();
                boolean valid = code == KeeperException.Code.NONODE || code == KeeperException.Code.NOTEMPTY;
                if (!valid) {
                    fail("Unexpected exception code for delete: " + ke.getMessage());
                }
            }
        }

        List<String> firstGen1 = zk_1.getChildren(parentName, true);
        Stat stat = new Stat();
        List<String> firstGen2 = zk_1.getChildren(parentName, true, stat);

        if (!firstGen1.equals(firstGen2)) {
            fail("children lists from getChildren()/getChildren2() do not match");
        }

        if (!stat_parent.equals(stat)) {
            fail("stat from exists()/getChildren() do not match");
        }

        try {
            zk.create(nodeName, null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        } catch (KeeperException ke) {
            Code code = ke.code();
            boolean valid = code == KeeperException.Code.NODEEXISTS;
            if (!valid) {
                fail("Unexpected exception code for createin: " + ke.getMessage());
            }
        }

        Thread.sleep(5000);
        WatchedEvent event = events.poll(10, TimeUnit.SECONDS);
        if (event == null) {
            throw new IOException("No event was delivered promptly");
        }
        if (event.getType() != EventType.NodeChildrenChanged || !event.getPath().equalsIgnoreCase(parentName)) {
            fail("Unexpected event was delivered: " + event.toString());
        }

        stat_node = zk_1.exists(nodeName, false);
        if (stat_node == null) {
            fail("node " + nodeName + " should exist");
        }

        try {
            zk.delete(parentName, -1);
            fail("Should be impossible to delete a non-empty node " + parentName);
        } catch (KeeperException ke) {
            Code code = ke.code();
            boolean valid = code == KeeperException.Code.NOTEMPTY;
            if (!valid) {
                fail("Unexpected exception code for delete: " + code);
            }
        }

        try {
            zk.create(nodeName + "/def", null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            fail("Should be impossible to create child off Ephemeral node " + nodeName);
        } catch (KeeperException ke) {
            Code code = ke.code();
            boolean valid = code == KeeperException.Code.NOCHILDRENFOREPHEMERALS;
            if (!valid) {
                fail("Unexpected exception code for createin: " + code);
            }
        }

        try {
            List<String> children1 = zk.getChildren(nodeName, false);
            List<String> children2 = zk.getChildren(nodeName, false, null);

            if (!children1.equals(children2)) {
                fail("children lists from getChildren()/getChildren2() does not match");
            }

            if (children1.size() > 0) {
                fail("ephemeral node " + nodeName + " should not have children");
            }
        } catch (KeeperException ke) {
            Code code = ke.code();
            boolean valid = code == KeeperException.Code.NONODE;
            if (!valid) {
                fail("Unexpected exception code for createin: " + code);
            }
        }
        firstGen1 = zk_1.getChildren(parentName, true);
        firstGen2 = zk_1.getChildren(parentName, true, null);

        if (!firstGen1.equals(firstGen2)) {
            fail("children list from getChildren()/getChildren2() does not match");
        }

        stat_node = zk_1.exists(nodeName, true);
        if (stat_node == null) {
            fail("node " + nodeName + " should exist");
        }
        System.out.println("session id of zk: " + zk.getSessionId());
        System.out.println("session id of zk_1: " + zk_1.getSessionId());
        zk.close();

        zk_1.exists("nosuchnode", false);

        event = this.getEvent(10);
        if (event == null) {
            throw new Error("First event was not delivered promptly");
        }
        if (!((event.getType() == EventType.NodeChildrenChanged && event.getPath().equalsIgnoreCase(parentName)) || (
                event.getType() == EventType.NodeDeleted
                        && event.getPath().equalsIgnoreCase(nodeName)))) {
            System.out.print(parentName
                                     + " "
                                     + EventType.NodeChildrenChanged
                                     + " "
                                     + nodeName
                                     + " "
                                     + EventType.NodeDeleted);
            fail("Unexpected first event was delivered: " + event.toString());
        }

        event = this.getEvent(10);

        if (event == null) {
            throw new Error("Second event was not delivered promptly");
        }
        if (!((event.getType() == EventType.NodeChildrenChanged && event.getPath().equalsIgnoreCase(parentName)) || (
                event.getType() == EventType.NodeDeleted
                        && event.getPath().equalsIgnoreCase(nodeName)))) {
            System.out.print(parentName
                                     + " "
                                     + EventType.NodeChildrenChanged
                                     + " "
                                     + nodeName
                                     + " "
                                     + EventType.NodeDeleted);
            fail("Unexpected second event was delivered: " + event.toString());
        }

        firstGen1 = zk_1.getChildren(parentName, false);
        stat_node = zk_1.exists(nodeName, false);
        if (stat_node != null) {
            fail("node " + nodeName + " should have been deleted");
        }
        if (firstGen1.contains(nodeName)) {
            fail("node " + nodeName + " should not be a children");
        }
        deleteZKDir(zk_1, nodeName);
        zk_1.close();
    }

    private void delete_create_get_set_test_1() throws IOException, InterruptedException, KeeperException {
        checkRoot();
        ZooKeeper zk = new ZooKeeper(hostPort, 10000, this);
        String parentName = testDirOnZK;
        String nodeName = parentName + "/benwashere";
        try {
            zk.delete(nodeName, -1);
        } catch (KeeperException ke) {
            Code code = ke.code();
            boolean valid = code == KeeperException.Code.NONODE || code == KeeperException.Code.NOTEMPTY;
            if (!valid) {
                fail("Unexpected exception code for delete: " + ke.getMessage());
            }
        }
        try {
            zk.create(nodeName, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException ke) {
            Code code = ke.code();
            boolean valid = code == KeeperException.Code.NODEEXISTS;
            if (!valid) {
                fail("Unexpected exception code for create: " + ke.getMessage());
            }
        }
        try {
            zk.setData(nodeName, "hi".getBytes(), 5700);
            fail("Should have gotten BadVersion exception");
        } catch (KeeperException ke) {
            if (ke.code() != Code.BADVERSION) {
                fail("Should have gotten BadVersion exception");
            }
        }
        zk.setData(nodeName, "hi".getBytes(), -1);
        Stat st = new Stat();
        byte[] bytes = zk.getData(nodeName, false, st);
        String retrieved = new String(bytes);
        if (!"hi".equals(retrieved)) {
            fail("The retrieved data [" + retrieved + "] is differented than the expected [hi]");
        }
        try {
            zk.delete(nodeName, 6800);
            fail("Should have gotten BadVersion exception");
        } catch (KeeperException ke) {
            Code code = ke.code();
            boolean valid = code == KeeperException.Code.NOTEMPTY || code == KeeperException.Code.BADVERSION;
            if (!valid) {
                fail("Unexpected exception code for delete: " + ke.getMessage());
            }
        }
        try {
            zk.delete(nodeName, -1);
        } catch (KeeperException ke) {
            Code code = ke.code();
            boolean valid = code == KeeperException.Code.NOTEMPTY;
            if (!valid) {
                fail("Unexpected exception code for delete: " + code);
            }
        }
        deleteZKDir(zk, nodeName);
        zk.close();
    }

    private void deleteNodeIfExists(ZooKeeper zk, String nodeName) throws InterruptedException {
        try {
            zk.delete(nodeName, -1);
        } catch (KeeperException ke) {
            Code code = ke.code();
            boolean valid = code == KeeperException.Code.NONODE || code == KeeperException.Code.NOTEMPTY;
            if (!valid) {
                fail("Unexpected exception code for delete: " + ke.getMessage());
            }
        }
    }

    private void create_get_stat_test() throws IOException, InterruptedException, KeeperException {
        checkRoot();
        ZooKeeper zk = new ZooKeeper(hostPort, 10000, this);
        String parentName = testDirOnZK;
        String nodeName = parentName + "/create_with_stat_tmp";
        deleteNodeIfExists(zk, nodeName);
        deleteNodeIfExists(zk, nodeName + "_2");
        Stat stat = new Stat();
        zk.create(nodeName, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        assertNotNull(stat);
        assertTrue(stat.getCzxid() > 0);
        assertTrue(stat.getCtime() > 0);

        Stat stat2 = new Stat();
        zk.create(nodeName + "_2", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat2);
        assertNotNull(stat2);
        assertTrue(stat2.getCzxid() > stat.getCzxid());
        assertTrue(stat2.getCtime() > stat.getCtime());

        deleteNodeIfExists(zk, nodeName);
        deleteNodeIfExists(zk, nodeName + "_2");
        zk.close();
    }

    public void my_test_1() throws IOException, InterruptedException, KeeperException {
        enode_test_1();
        enode_test_2();
        delete_create_get_set_test_1();
        create_get_stat_test();
    }

    public synchronized void process(WatchedEvent event) {
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
