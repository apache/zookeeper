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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import java.io.IOException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.Test;

public class StatTest extends ClientBase {

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

    /**
     * Create a new Stat, fill in dummy values trying to catch failure
     * to copy in client or server code.
     *
     * @return a new stat with dummy values
     */
    private Stat newStat() {
        Stat stat = new Stat();

        stat.setAversion(100);
        stat.setCtime(100);
        stat.setCversion(100);
        stat.setCzxid(100);
        stat.setDataLength(100);
        stat.setEphemeralOwner(100);
        stat.setMtime(100);
        stat.setMzxid(100);
        stat.setNumChildren(100);
        stat.setPzxid(100);
        stat.setVersion(100);

        return stat;
    }

    @Test
    public void testBasic() throws IOException, KeeperException, InterruptedException {
        String name = "/foo";
        zk.create(name, name.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        Stat stat;

        stat = newStat();
        zk.getData(name, false, stat);

        assertEquals(stat.getCzxid(), stat.getMzxid());
        assertEquals(stat.getCzxid(), stat.getPzxid());
        assertEquals(stat.getCtime(), stat.getMtime());
        assertEquals(0, stat.getCversion());
        assertEquals(0, stat.getVersion());
        assertEquals(0, stat.getAversion());
        assertEquals(0, stat.getEphemeralOwner());
        assertEquals(name.length(), stat.getDataLength());
        assertEquals(0, stat.getNumChildren());
    }

    @Test
    public void testChild() throws IOException, KeeperException, InterruptedException {
        String name = "/foo";
        zk.create(name, name.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        String childname = name + "/bar";
        zk.create(childname, childname.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

        Stat stat;

        stat = newStat();
        zk.getData(name, false, stat);

        assertEquals(stat.getCzxid(), stat.getMzxid());
        assertEquals(stat.getCzxid() + 1, stat.getPzxid());
        assertEquals(stat.getCtime(), stat.getMtime());
        assertEquals(1, stat.getCversion());
        assertEquals(0, stat.getVersion());
        assertEquals(0, stat.getAversion());
        assertEquals(0, stat.getEphemeralOwner());
        assertEquals(name.length(), stat.getDataLength());
        assertEquals(1, stat.getNumChildren());

        stat = newStat();
        zk.getData(childname, false, stat);

        assertEquals(stat.getCzxid(), stat.getMzxid());
        assertEquals(stat.getCzxid(), stat.getPzxid());
        assertEquals(stat.getCtime(), stat.getMtime());
        assertEquals(0, stat.getCversion());
        assertEquals(0, stat.getVersion());
        assertEquals(0, stat.getAversion());
        assertEquals(zk.getSessionId(), stat.getEphemeralOwner());
        assertEquals(childname.length(), stat.getDataLength());
        assertEquals(0, stat.getNumChildren());
    }

    @Test
    public void testChildren() throws IOException, KeeperException, InterruptedException {
        String name = "/foo";
        zk.create(name, name.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        for (int i = 0; i < 10; i++) {
            String childname = name + "/bar" + i;
            zk.create(childname, childname.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

            Stat stat;

            stat = newStat();
            zk.getData(name, false, stat);

            assertEquals(stat.getCzxid(), stat.getMzxid());
            assertEquals(stat.getCzxid() + i + 1, stat.getPzxid());
            assertEquals(stat.getCtime(), stat.getMtime());
            assertEquals(i + 1, stat.getCversion());
            assertEquals(0, stat.getVersion());
            assertEquals(0, stat.getAversion());
            assertEquals(0, stat.getEphemeralOwner());
            assertEquals(name.length(), stat.getDataLength());
            assertEquals(i + 1, stat.getNumChildren());
        }
    }

    @Test
    public void testDataSizeChange() throws IOException, KeeperException, InterruptedException {
        String name = "/foo";
        zk.create(name, name.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        Stat stat;

        stat = newStat();
        zk.getData(name, false, stat);

        assertEquals(stat.getCzxid(), stat.getMzxid());
        assertEquals(stat.getCzxid(), stat.getPzxid());
        assertEquals(stat.getCtime(), stat.getMtime());
        assertEquals(0, stat.getCversion());
        assertEquals(0, stat.getVersion());
        assertEquals(0, stat.getAversion());
        assertEquals(0, stat.getEphemeralOwner());
        assertEquals(name.length(), stat.getDataLength());
        assertEquals(0, stat.getNumChildren());

        zk.setData(name, (name + name).getBytes(), -1);

        stat = newStat();
        zk.getData(name, false, stat);

        assertNotSame(stat.getCzxid(), stat.getMzxid());
        assertEquals(stat.getCzxid(), stat.getPzxid());
        assertNotSame(stat.getCtime(), stat.getMtime());
        assertEquals(0, stat.getCversion());
        assertEquals(1, stat.getVersion());
        assertEquals(0, stat.getAversion());
        assertEquals(0, stat.getEphemeralOwner());
        assertEquals(name.length() * 2, stat.getDataLength());
        assertEquals(0, stat.getNumChildren());
    }

}
