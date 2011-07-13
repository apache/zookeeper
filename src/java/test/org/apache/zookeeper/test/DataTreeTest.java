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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.apache.zookeeper.server.DataNode;

public class DataTreeTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(DataTreeTest.class);

    private DataTree dt;

    @Before
    public void setUp() throws Exception {
        dt=new DataTree();
    }

    @After
    public void tearDown() throws Exception {
        dt=null;
    }

    @Test
    public void testRootWatchTriggered() throws Exception {
        class MyWatcher implements Watcher{
            boolean fired=false;
            public void process(WatchedEvent event) {
                if(event.getPath().equals("/"))
                    fired=true;
            }
        }
        MyWatcher watcher=new MyWatcher();
        // set a watch on the root node
        dt.getChildren("/", new Stat(), watcher);
        // add a new node, should trigger a watch
        dt.createNode("/xyz", new byte[0], null, 0, dt.getNode("/").stat.getCversion()+1, 1, 1);
        Assert.assertFalse("Root node watch not triggered",!watcher.fired);
    }

    /**
     * For ZOOKEEPER-1046 test if cversion is getting incremented correctly.
     */
    @Test
    public void testIncrementCversion() throws Exception {
        dt.createNode("/test", new byte[0], null, 0, dt.getNode("/").stat.getCversion()+1, 1, 1);
        DataNode zk = dt.getNode("/test");
        int prevCversion = zk.stat.getCversion();
        long prevPzxid = zk.stat.getPzxid();
        dt.setCversionPzxid("/test/",  prevCversion + 1, prevPzxid + 1);
        int newCversion = zk.stat.getCversion();
        long newPzxid = zk.stat.getPzxid();
        Assert.assertTrue("<cversion, pzxid> verification failed. Expected: <" +
                (prevCversion + 1) + ", " + (prevPzxid + 1) + ">, found: <" +
                newCversion + ", " + newPzxid + ">",
                (newCversion == prevCversion + 1 && newPzxid == prevPzxid + 1));
    }
}
