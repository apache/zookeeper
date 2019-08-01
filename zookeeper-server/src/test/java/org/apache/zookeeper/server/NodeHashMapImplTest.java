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

package org.apache.zookeeper.server;

import java.util.Set;
import java.util.Map;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.server.util.DigestCalculator;

import org.junit.After;
import org.junit.Before;
import org.junit.Assert;
import org.junit.Test;

public class NodeHashMapImplTest extends ZKTestCase {

    @Before
    public void setUp() {
        DigestCalculator.setDigestEnabled(true);
    }

    @After
    public void tearDown() {
        DigestCalculator.setDigestEnabled(false);
    }

    /**
     * Test all the operations supported in NodeHashMapImpl.
     */
    @Test
    public void testOperations() {
        NodeHashMapImpl nodes = new NodeHashMapImpl();

        Assert.assertEquals(0, nodes.size());
        Assert.assertEquals(0L, nodes.getDigest());

        // add a new node
        String p1 = "p1";
        DataNode n1 = new DataNode(p1.getBytes(), 0L, new StatPersisted());
        nodes.put(p1, n1);

        Assert.assertEquals(n1, nodes.get(p1));
        Assert.assertNotEquals(0L, nodes.getDigest());
        Assert.assertEquals(1, nodes.size());

        // put another node
        String p2 = "p2";
        nodes.put(p2, new DataNode(p2.getBytes(), 0L, new StatPersisted()));

        Set<Map.Entry<String, DataNode>> entries = nodes.entrySet();
        Assert.assertEquals(2, entries.size());

        // remove a node
        nodes.remove(p1);
        Assert.assertEquals(1, nodes.size());

        nodes.remove(p2);
        Assert.assertEquals(0, nodes.size());
        Assert.assertEquals(0L, nodes.getDigest());

        // test preChange and postChange
        String p3 = "p3";
        DataNode n3 = new DataNode(p3.getBytes(), 0L, new StatPersisted());
        nodes.put(p3, n3);
        long preChangeDigest = nodes.getDigest();
        Assert.assertNotEquals(0L, preChangeDigest);

        nodes.preChange(p3, n3);
        Assert.assertEquals(0L, nodes.getDigest());

        n3.stat.setMzxid(1);
        n3.stat.setMtime(1);
        n3.stat.setVersion(1);
        nodes.postChange(p3, n3);

        long postChangeDigest = nodes.getDigest();
        Assert.assertNotEquals(0, postChangeDigest);
        Assert.assertNotEquals(preChangeDigest, postChangeDigest);
    }
}
