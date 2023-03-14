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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import java.util.Map;
import java.util.Set;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.data.StatPersisted;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class NodeHashMapImplTest extends ZKTestCase {

    @BeforeEach
    public void setUp() {
        ZooKeeperServer.setDigestEnabled(true);
    }

    @AfterEach
    public void tearDown() {
        ZooKeeperServer.setDigestEnabled(false);
    }

    /**
     * Test all the operations supported in NodeHashMapImpl.
     */
    @Test
    public void testOperations() {
        NodeHashMapImpl nodes = new NodeHashMapImpl(new DigestCalculator());

        assertEquals(0, nodes.size());
        assertEquals(0L, nodes.getDigest());

        // add a new node
        String p1 = "p1";
        DataNode n1 = new DataNode(p1.getBytes(), 0L, new StatPersisted());
        nodes.put(p1, n1);

        assertEquals(n1, nodes.get(p1));
        assertNotEquals(0L, nodes.getDigest());
        assertEquals(1, nodes.size());

        // put another node
        String p2 = "p2";
        nodes.put(p2, new DataNode(p2.getBytes(), 0L, new StatPersisted()));

        Set<Map.Entry<String, DataNode>> entries = nodes.entrySet();
        assertEquals(2, entries.size());

        // remove a node
        nodes.remove(p1);
        assertEquals(1, nodes.size());

        nodes.remove(p2);
        assertEquals(0, nodes.size());
        assertEquals(0L, nodes.getDigest());

        // test preChange and postChange
        String p3 = "p3";
        DataNode n3 = new DataNode(p3.getBytes(), 0L, new StatPersisted());
        nodes.put(p3, n3);
        long preChangeDigest = nodes.getDigest();
        assertNotEquals(0L, preChangeDigest);

        nodes.preChange(p3, n3);
        assertEquals(0L, nodes.getDigest());

        n3.stat.setMzxid(1);
        n3.stat.setMtime(1);
        n3.stat.setVersion(1);
        nodes.postChange(p3, n3);

        long postChangeDigest = nodes.getDigest();
        assertNotEquals(0, postChangeDigest);
        assertNotEquals(preChangeDigest, postChangeDigest);
    }

}
