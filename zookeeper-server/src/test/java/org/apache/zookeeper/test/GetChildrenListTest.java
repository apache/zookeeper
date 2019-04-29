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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.junit.Assert;
import org.junit.Test;

public class GetChildrenListTest extends ClientBase {
    private ZooKeeper zk;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        zk = createClient();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();

        zk.close();
    }

    @Test
    public void testChildrenList()
            throws KeeperException, InterruptedException
    {
        List<String> topLevelNodes = new ArrayList<String>();
        Map<String, List<String>> childrenNodes = new HashMap<String, List<String>>();
        // Creating a database where '/fooX' nodes has 'barXY' named children.
        for (int i = 0; i < 10; i++) {
            String name = "/foo" + i;
            zk.create(name, name.getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            topLevelNodes.add(name);
            childrenNodes.put(name, new ArrayList<>());
            for (int j = 0; j < 10; j++) {
                String childname = name + "/bar" + i + j;
                String childname_s = "bar" + i + j;
                zk.create(childname, childname.getBytes(), Ids.OPEN_ACL_UNSAFE,
                        CreateMode.EPHEMERAL);
                childrenNodes.get(name).add(childname_s);
            }
        }

        List<List<String>> childrenList = zk.getChildren(topLevelNodes, false);
        for (int i = 0; i < topLevelNodes.size(); i++) {
            String nodeName = topLevelNodes.get(i);
            // In general, we do not demand an order from the children list but to contain every child.
            Assert.assertEquals(new TreeSet<String>(childrenList.get(i)),
                                new TreeSet<String>(childrenNodes.get(nodeName)));

            List<String> children = zk.getChildren(nodeName, false);
            Assert.assertEquals(childrenList.get(i), children);
        }

        // Check for getting the children of the same node twice
        List<String> sameNodes = Arrays.asList(topLevelNodes.get(0), topLevelNodes.get(0));
        List<List<String>> sameChildrenList = zk.getChildren(sameNodes, false);
        Assert.assertEquals(sameChildrenList.get(0), childrenList.get(0));
        Assert.assertEquals(sameChildrenList.get(0), sameChildrenList.get(1));
        Assert.assertEquals(sameChildrenList.size(), sameNodes.size());
    }


    @Test
    public void testAuthentication()
            throws KeeperException, InterruptedException
    {
        List<ACL> writeOnly = Collections.singletonList(new ACL(ZooDefs.Perms.WRITE,
                new Id("world", "anyone")));
        zk.create("/foo_auth", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/foo_no_auth", null, writeOnly, CreateMode.PERSISTENT);

        zk.create("/foo_auth/bar", null, Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);

        // Check for normal behaviour.
        List<List<String>> childrenList = zk.getChildren(Collections.singletonList("/foo_auth"), false);
        Assert.assertEquals(childrenList.size(), 1);
        Assert.assertEquals(childrenList.get(0).size(), 1);
        Assert.assertEquals(childrenList.get(0).get(0), "bar");

        // Check for authentication violation.
        try {
            childrenList = zk.getChildren(Collections.singletonList("/foo_no_auth"), false);
            Assert.fail("Expected NoAuthException for getting the childrens of a write only node");
        } catch (KeeperException.NoAuthException e) {
            // Expected. Do nothing
        }

        // Check for using mixed nodes - throws exception as well.
        try {
            childrenList = zk.getChildren(Arrays.asList("/foo_auth" ,"/foo_no_auth"), false);
            Assert.fail("Expected NoAuthException for getting the childrens of a write only node");
        } catch (KeeperException.NoAuthException e) {
            // Expected. Do nothing
        }
    }
}
