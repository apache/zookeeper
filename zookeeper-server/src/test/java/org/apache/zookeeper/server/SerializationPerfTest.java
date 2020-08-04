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

import java.io.IOException;
import java.io.OutputStream;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SerializationPerfTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(SerializationPerfTest.class);

    private static class NullOutputStream extends OutputStream {

        public void write(int b) {
            // do nothing - exclude persistence from perf
        }

    }

    static int createNodes(DataTree tree, String path, int depth, int childcount, int parentCVersion, byte[] data) throws KeeperException.NodeExistsException, KeeperException.NoNodeException {
        path += "node" + depth;
        tree.createNode(path, data, null, -1, ++parentCVersion, 1, 1);

        if (--depth == 0) {
            return 1;
        }

        path += "/";

        int count = 1;
        for (int i = 0; i < childcount; i++) {
            count += createNodes(tree, path + i, depth, childcount, 1, data);
        }

        return count;
    }

    private static void serializeTree(int depth, int width, int len) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        DataTree tree = new DataTree();
        createNodes(tree, "/", depth, width, tree.getNode("/").stat.getCversion(), new byte[len]);
        int count = tree.getNodeCount();

        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(new NullOutputStream());
        System.gc();
        long start = System.nanoTime();
        tree.serialize(oa, "test");
        long end = System.nanoTime();
        long durationms = (end - start) / 1000000L;
        long pernodeus = ((end - start) / 1000L) / count;
        LOG.info(
            "Serialized {} nodes in {} ms ({}us/node), depth={} width={} datalen={}",
            count,
            durationms,
            pernodeus,
            depth,
            width,
            len);
    }

    @Test
    public void testSingleSerialize() throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        serializeTree(1, 0, 20);
    }

    @Test
    public void testWideSerialize() throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        serializeTree(2, 10000, 20);
    }

    @Test
    public void testDeepSerialize() throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        serializeTree(400, 1, 20);
    }

    @Test
    public void test10Wide5DeepSerialize() throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        serializeTree(5, 10, 20);
    }

    @Test
    public void test15Wide5DeepSerialize() throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        serializeTree(5, 15, 20);
    }

    @Test
    public void test25Wide4DeepSerialize() throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        serializeTree(4, 25, 20);
    }

    @Test
    public void test40Wide4DeepSerialize() throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        serializeTree(4, 40, 20);
    }

    @Test
    public void test300Wide3DeepSerialize() throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        serializeTree(3, 300, 20);
    }

}
