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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.persistence.FileSnap;
import org.apache.zookeeper.server.persistence.RocksDBSnap;
import org.apache.zookeeper.server.persistence.SnapShot;
import org.apache.zookeeper.server.persistence.SnapshotFactory;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SerializationPerfTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(SerializationPerfTest.class);

    private File tmpDir;
    private static File snapDir;
    public static final int VERSION = 2;
    public static final String version = "version-";

    public static Stream<Arguments> data() {
        return Stream.of(
                Arguments.of(FileSnap.class.getName()),
                Arguments.of(RocksDBSnap.class.getName()));
    }

    public void setUp(String snapName) throws IOException {
        System.setProperty(SnapshotFactory.ZOOKEEPER_SNAPSHOT_NAME, snapName);
        tmpDir = ClientBase.createEmptyTestDir();
        File snapshotDir = new File(tmpDir, "snapdir");
        snapDir = new File((new File(snapshotDir, version + VERSION).toString()));
        snapDir.mkdirs();
    }

    @AfterEach
    public void tearDown() throws Exception {
        System.clearProperty(SnapshotFactory.ZOOKEEPER_SNAPSHOT_NAME);
        tmpDir = null;
        snapDir = null;
    }

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
        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<Long, Integer>();
        DataTree tree = new DataTree();
        long lastZxid = 1;
        createNodes(tree, "/", depth, width, tree.getNode("/").stat.getCversion(), new byte[len]);
        int count = tree.getNodeCount();

        SnapShot snapLog = SnapshotFactory.createSnapshot(snapDir);
        System.gc();
        long start = System.nanoTime();
        snapLog.serialize(tree, sessions, lastZxid, true);
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
        snapLog.close();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testSingleSerialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        serializeTree(1, 0, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testWideSerialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        serializeTree(2, 10000, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testDeepSerialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        serializeTree(400, 1, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void test10Wide5DeepSerialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        serializeTree(5, 10, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void test15Wide5DeepSerialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        serializeTree(5, 15, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void test25Wide4DeepSerialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        serializeTree(4, 25, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void test40Wide4DeepSerialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        serializeTree(4, 40, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void test300Wide3DeepSerialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        serializeTree(3, 300, 20);
    }

}
