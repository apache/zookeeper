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

package org.apache.zookeeper.test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs.Ids;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DisconnectedWatcherTest extends ClientBase {
    protected static final Logger LOG = LoggerFactory.getLogger(DisconnectedWatcherTest.class);
    final int TIMEOUT = 5000;

    private class MyWatcher extends CountdownWatcher {
        LinkedBlockingQueue<WatchedEvent> events =
            new LinkedBlockingQueue<WatchedEvent>();

        public void process(WatchedEvent event) {
            super.process(event);
            if (event.getType() != Event.EventType.None) {
                try {
                    events.put(event);
                } catch (InterruptedException e) {
                    LOG.warn("ignoring interrupt during event.put");
                }
            }
        }
    }

    // @see jira issue ZOOKEEPER-961
    
    @Test
    public void testChildWatcherAutoResetWithChroot() throws Exception {
        ZooKeeper zk1 = createClient();

        zk1.create("/ch1", null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);

        MyWatcher watcher = new MyWatcher();
        ZooKeeper zk2 = createClient(watcher, hostPort + "/ch1");
        zk2.getChildren("/", true );

        // this call shouldn't trigger any error or watch
        zk1.create("/youdontmatter1", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        // this should trigger the watch
        zk1.create("/ch1/youshouldmatter1", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        WatchedEvent e = watcher.events.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(e);
        Assert.assertEquals(EventType.NodeChildrenChanged, e.getType());
        Assert.assertEquals("/", e.getPath());

        MyWatcher childWatcher = new MyWatcher();
        zk2.getChildren("/", childWatcher);
        
        stopServer();
        watcher.waitForDisconnected(3000);
        startServer();
        watcher.waitForConnected(3000);

        // this should trigger the watch
        zk1.create("/ch1/youshouldmatter2", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        e = childWatcher.events.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(e);
        Assert.assertEquals(EventType.NodeChildrenChanged, e.getType());
        Assert.assertEquals("/", e.getPath());
    }
    
    @Test
    public void testDefaultWatcherAutoResetWithChroot() throws Exception {
        ZooKeeper zk1 = createClient();

        zk1.create("/ch1", null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);

        MyWatcher watcher = new MyWatcher();
        ZooKeeper zk2 = createClient(watcher, hostPort + "/ch1");
        zk2.getChildren("/", true );

        // this call shouldn't trigger any error or watch
        zk1.create("/youdontmatter1", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        // this should trigger the watch
        zk1.create("/ch1/youshouldmatter1", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        WatchedEvent e = watcher.events.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(e);
        Assert.assertEquals(EventType.NodeChildrenChanged, e.getType());
        Assert.assertEquals("/", e.getPath());

        zk2.getChildren("/", true );

        stopServer();
        watcher.waitForDisconnected(3000);
        startServer();
        watcher.waitForConnected(3000);

        // this should trigger the watch
        zk1.create("/ch1/youshouldmatter2", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        e = watcher.events.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(e);
        Assert.assertEquals(EventType.NodeChildrenChanged, e.getType());
        Assert.assertEquals("/", e.getPath());
    }
    
    @Test
    public void testDeepChildWatcherAutoResetWithChroot() throws Exception {
        ZooKeeper zk1 = createClient();

        zk1.create("/ch1", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk1.create("/ch1/here", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk1.create("/ch1/here/we", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk1.create("/ch1/here/we/are", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        MyWatcher watcher = new MyWatcher();
        ZooKeeper zk2 = createClient(watcher, hostPort + "/ch1/here/we");
        zk2.getChildren("/are", true );

        // this should trigger the watch
        zk1.create("/ch1/here/we/are/now", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        WatchedEvent e = watcher.events.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(e);
        Assert.assertEquals(EventType.NodeChildrenChanged, e.getType());
        Assert.assertEquals("/are", e.getPath());

        MyWatcher childWatcher = new MyWatcher();
        zk2.getChildren("/are", childWatcher);
        
        stopServer();
        watcher.waitForDisconnected(3000);
        startServer();
        watcher.waitForConnected(3000);

        // this should trigger the watch
        zk1.create("/ch1/here/we/are/again", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        e = childWatcher.events.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(e);
        Assert.assertEquals(EventType.NodeChildrenChanged, e.getType());
        Assert.assertEquals("/are", e.getPath());
    }
}
