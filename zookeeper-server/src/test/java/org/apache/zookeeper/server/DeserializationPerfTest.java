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
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
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

public class DeserializationPerfTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(DeserializationPerfTest.class);

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

    private static void deserializeTree(int depth, int width, int len) throws InterruptedException,
            IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        int count;
        SnapShot snapLog;
        {
            ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<Long, Integer>();
            DataTree tree = new DataTree();
            long lastZxid = 1;
            SerializationPerfTest.createNodes(tree, "/", depth, width, tree.getNode("/").stat.getCversion(), new byte[len]);
            count = tree.getNodeCount();

            snapLog = SnapshotFactory.createSnapshot(snapDir);
            snapLog.serialize(tree, sessions, lastZxid, true);
        }

        Map<Long, Integer> deserializedSessions = new HashMap<Long, Integer>();
        DataTree dserTree = new DataTree();

        System.gc();
        long start = System.nanoTime();
        snapLog.deserialize(dserTree, deserializedSessions);
        long end = System.nanoTime();
        long durationms = (end - start) / 1000000L;
        long pernodeus = ((end - start) / 1000L) / count;

        assertEquals(count, dserTree.getNodeCount());

        LOG.info(
            "Deserialized {} nodes in {} ms ({}us/node), depth={} width={} datalen={}",
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
    public void testSingleDeserialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        deserializeTree(1, 0, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testWideDeserialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        deserializeTree(2, 10000, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testDeepDeserialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        deserializeTree(400, 1, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void test10Wide5DeepDeserialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        deserializeTree(5, 10, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void test15Wide5DeepDeserialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        deserializeTree(5, 15, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void test25Wide4DeepDeserialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        deserializeTree(4, 25, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void test40Wide4DeepDeserialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        deserializeTree(4, 40, 20);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void test300Wide3DeepDeserialize(String snapName) throws InterruptedException, IOException, KeeperException.NodeExistsException, KeeperException.NoNodeException {
        setUp(snapName);
        deserializeTree(3, 300, 20);
    }

}
