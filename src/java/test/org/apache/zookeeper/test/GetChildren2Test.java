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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.junit.Test;

/**
 *
 */
public class GetChildren2Test extends ClientBase {
    private ZooKeeper zk;
    
    protected void setUp() throws Exception {
        super.setUp();
        
        zk = createClient();
    }

    protected void tearDown() throws Exception {
        super.tearDown();
        
        zk.close();
    }
    
    @Test
    public void testChild()
        throws IOException, KeeperException, InterruptedException
    {
        String name = "/foo";
        zk.create(name, name.getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        String childname = name + "/bar";
        zk.create(childname, childname.getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);

        Stat stat = new Stat();
        List<String> s = zk.getChildren(name, false, stat);

        assertEquals(stat.getCzxid(), stat.getMzxid());
        assertEquals(stat.getCzxid() + 1, stat.getPzxid());
        assertEquals(stat.getCtime(), stat.getMtime());
        assertEquals(1, stat.getCversion());
        assertEquals(0, stat.getVersion());
        assertEquals(0, stat.getAversion());
        assertEquals(0, stat.getEphemeralOwner());
        assertEquals(name.length(), stat.getDataLength());
        assertEquals(1, stat.getNumChildren());
        assertEquals(s.size(), stat.getNumChildren());

        s = zk.getChildren(childname, false, stat);

        assertEquals(stat.getCzxid(), stat.getMzxid());
        assertEquals(stat.getCzxid(), stat.getPzxid());
        assertEquals(stat.getCtime(), stat.getMtime());
        assertEquals(0, stat.getCversion());
        assertEquals(0, stat.getVersion());
        assertEquals(0, stat.getAversion());
        assertEquals(zk.getSessionId(), stat.getEphemeralOwner());
        assertEquals(childname.length(), stat.getDataLength());
        assertEquals(0, stat.getNumChildren());
        assertEquals(s.size(), stat.getNumChildren());
    }

    @Test
    public void testChildren()
        throws IOException, KeeperException, InterruptedException
    {
        String name = "/foo";
        zk.create(name, name.getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        List<String> children = new ArrayList<String>();
        List<String> children_s = new ArrayList<String>();

        for (int i = 0; i < 10; i++) {
            String childname = name + "/bar" + i;
            String childname_s = "bar" + i;
            children.add(childname);
            children_s.add(childname_s);
        }

        for(int i = 0; i < children.size(); i++) {
            String childname = children.get(i);
            zk.create(childname, childname.getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL);

            Stat stat = new Stat();
            List<String> s = zk.getChildren(name, false, stat);

            assertEquals(stat.getCzxid(), stat.getMzxid());
            assertEquals(stat.getCzxid() + i + 1, stat.getPzxid());
            assertEquals(stat.getCtime(), stat.getMtime());
            assertEquals(i + 1, stat.getCversion());
            assertEquals(0, stat.getVersion());
            assertEquals(0, stat.getAversion());
            assertEquals(0, stat.getEphemeralOwner());
            assertEquals(name.length(), stat.getDataLength());
            assertEquals(i + 1, stat.getNumChildren());
            assertEquals(s.size(), stat.getNumChildren());
        }
        List<String> p = zk.getChildren(name, false, null);
        List<String> c_a = children_s;
        List<String> c_b = p;
        Collections.sort(c_a);
        Collections.sort(c_b);
        assertEquals(c_a.size(), 10);
        assertEquals(c_a, c_b);
    }
}
