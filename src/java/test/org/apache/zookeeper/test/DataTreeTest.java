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

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.junit.Test;

public class DataTreeTest extends TestCase {
    protected static final Logger LOG = Logger.getLogger(DataTreeTest.class);

    private DataTree dt;

    @Override
    public void setUp() throws Exception {
        LOG.info("STARTING " + getName());
        dt=new DataTree();
    }

    @Override
    public void tearDown() throws Exception {
        dt=null;
        LOG.info("FINISHED " + getName());
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
        dt.createNode("/xyz", new byte[0], null, 0, 1, 1);
        assertFalse("Root node watch not triggered",!watcher.fired);
    }

}
