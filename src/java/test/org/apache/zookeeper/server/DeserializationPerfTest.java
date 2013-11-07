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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import junit.framework.TestCase;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.junit.Test;

public class DeserializationPerfTest extends TestCase {
    protected static final Logger LOG = Logger.getLogger(DeserializationPerfTest.class);

    private static void deserializeTree(int depth, int width, int len)
            throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        BinaryInputArchive ia;
        int count;
        {
            DataTree tree = new DataTree();
            SerializationPerfTest.createNodes(tree, "/", depth, width, new byte[len]);
            count = tree.getNodeCount();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
            tree.serialize(oa, "test");
            baos.flush();

            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            ia = BinaryInputArchive.getArchive(bais);
        }

        DataTree dserTree = new DataTree();

        System.gc();
        long start = System.nanoTime();
        dserTree.deserialize(ia, "test");
        long end = System.nanoTime();
        long durationms = (end - start) / 1000000L;
        long pernodeus = ((end - start) / 1000L) / count;

        assertEquals(count, dserTree.getNodeCount());

        LOG.info("Deserialized " + count + " nodes in " + durationms
                + " ms (" + pernodeus + "us/node), depth=" + depth + " width="
                + width + " datalen=" + len);
    }

    @Test
    public void testSingleDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(1, 0, 20);
    }

    @Test
    public void testWideDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(2, 10000, 20);
    }

    @Test
    public void testDeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(400, 1, 20);
    }

    @Test
    public void test10Wide5DeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(5, 10, 20);
    }

    @Test
    public void test15Wide5DeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(5, 15, 20);
    }

    @Test
    public void test25Wide4DeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(4, 25, 20);
    }

    @Test
    public void test40Wide4DeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(4, 40, 20);
    }

    @Test
    public void test300Wide3DeepDeserialize() throws
            InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        deserializeTree(3, 300, 20);
    }

}
