/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper;
import static org.junit.Assert.assertEquals;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Test;

public class GetStatPersistedTest extends ClientBase {

    private static final String BASE = "/getStatPersistedTest";

    private ZooKeeper zk;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        zk = createClient();
        System.setProperty("zookeeper.extendedTypesEnabled", "true");
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();

        zk.close();
        System.clearProperty("zookeeper.extendedTypesEnabled");
    }

    @Test
    public void testGetNodeType() throws KeeperException, InterruptedException {
        String path = BASE + "_PERSISTENT";
        zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        CreateMode mode = zk.getNodeType(path);
        assertEquals(CreateMode.PERSISTENT, mode);

        path = BASE + "_EPHEMERAL";
        zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        mode = zk.getNodeType(path);
        assertEquals(CreateMode.EPHEMERAL, mode);

        path = BASE + "_CONTAINER";
        zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER);
        mode = zk.getNodeType(path);
        assertEquals(CreateMode.CONTAINER, mode);

        long ttl = 60000;
        path = BASE + "_PERSISTENT_WITH_TTL";
        zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, new Stat(), ttl);
        mode = zk.getNodeType(path);
        assertEquals(CreateMode.PERSISTENT_WITH_TTL, mode);
    }

    @Test
    public void testGetTTL() throws KeeperException, InterruptedException {
        String path = BASE + "_PERSISTENT";
        zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        long ttl = zk.getTTL(path);
        assertEquals(-1, ttl);

        path = BASE + "_PERSISTENT_WITH_TTL";
        long expectedTTL = 60000;
        zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_WITH_TTL, new Stat(), expectedTTL);
        long actualTTL = zk.getTTL(path);
        assertEquals(expectedTTL, actualTTL);

    }
}
