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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.io.IOException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test suite for validating the Create API.
 */
public class CreateTest extends ClientBase {

    private ZooKeeper zk;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        super.setUp();
        zk = createClient();
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        zk.close();
    }

    @Test
    public void testCreate() throws IOException, KeeperException, InterruptedException {
        createNoStatVerifyResult("/foo");
        createNoStatVerifyResult("/foo/child");
    }

    @Test
    public void testCreateWithStat() throws IOException, KeeperException, InterruptedException {
        String name = "/foo";
        Stat stat = createWithStatVerifyResult("/foo");
        Stat childStat = createWithStatVerifyResult("/foo/child");
        // Don't expect to get the same stats for different creates.
        assertFalse(stat.equals(childStat));
    }

    @Test
    public void testCreateWithNullStat() throws IOException, KeeperException, InterruptedException {
        String name = "/foo";
        assertNull(zk.exists(name, false));

        Stat stat = null;
        // If a null Stat object is passed the create should still
        // succeed, but no Stat info will be returned.
        String path = zk.create(name, name.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        assertNull(stat);
        assertNotNull(zk.exists(name, false));
    }

    private void createNoStatVerifyResult(String newName) throws KeeperException, InterruptedException {
        assertNull(zk.exists(newName, false), "Node existed before created");
        String path = zk.create(newName, newName.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertEquals(path, newName);
        assertNotNull(zk.exists(newName, false), "Node was not created as expected");
    }

    private Stat createWithStatVerifyResult(String newName) throws KeeperException, InterruptedException {
        assertNull(zk.exists(newName, false), "Node existed before created");
        Stat stat = new Stat();
        String path = zk.create(newName, newName.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        assertEquals(path, newName);
        validateCreateStat(stat, newName);

        Stat referenceStat = zk.exists(newName, false);
        assertNotNull(referenceStat, "Node was not created as expected");
        assertEquals(referenceStat, stat);

        return stat;
    }

    private void validateCreateStat(Stat stat, String name) {
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

}
