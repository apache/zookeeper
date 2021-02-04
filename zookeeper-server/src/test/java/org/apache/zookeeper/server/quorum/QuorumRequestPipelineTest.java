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

package org.apache.zookeeper.server.quorum;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.test.QuorumBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class QuorumRequestPipelineTest extends QuorumBase {

    protected final CountDownLatch callComplete = new CountDownLatch(1);
    protected boolean complete = false;
    protected static final String PARENT_PATH = "/foo";
    protected static final Set<String> CHILDREN = new HashSet<String>(Arrays.asList("1", "2", "3"));
    protected static final String AUTH_PROVIDER = "digest";
    protected static final byte[] AUTH = "hello".getBytes();
    protected static final byte[] DATA = "Hint Water".getBytes();

    protected TestableZooKeeper zkClient;

    public static Stream<Arguments> data() throws Exception {
        return Stream.of(
                Arguments.of(ServerState.LEADING),
                Arguments.of(ServerState.FOLLOWING),
                Arguments.of(ServerState.OBSERVING));
    }

    @BeforeEach
    @Override
    public void setUp() {
        //since parameterized test methods need a parameterized setUp method
        //the inherited method has to be overridden with an empty function body
    }

    public void setUp(ServerState serverState) throws Exception {
        CountdownWatcher clientWatch = new CountdownWatcher();
        super.setUp(true);
        zkClient = createClient(clientWatch, getPeersMatching(serverState));
        zkClient.addAuthInfo(AUTH_PROVIDER, AUTH);
        clientWatch.waitForConnected(CONNECTION_TIMEOUT);
    }

    @AfterEach
    public void tearDown() throws Exception {
        zkClient.close();
        super.tearDown();
    }

    private Stat create2EmptyNode(TestableZooKeeper zkClient, String path) throws Exception {
        Stat stat = new Stat();
        zkClient.create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        return stat;
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testCreate(ServerState serverState) throws Exception {
        setUp(serverState);
        zkClient.create(PARENT_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertArrayEquals(DATA, zkClient.getData(PARENT_PATH, false, null), String.format("%s Node created (create) with expected value", serverState));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testCreate2(ServerState serverState) throws Exception {
        setUp(serverState);
        zkClient.create(PARENT_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        assertArrayEquals(DATA, zkClient.getData(PARENT_PATH, false, null), String.format("%s Node created (create2) with expected value", serverState));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testDelete(ServerState serverState) throws Exception {
        setUp(serverState);
        create2EmptyNode(zkClient, PARENT_PATH);
        zkClient.delete(PARENT_PATH, -1);
        assertNull(zkClient.exists(PARENT_PATH, false), String.format("%s Node no longer exists", serverState));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testExists(ServerState serverState) throws Exception {
        setUp(serverState);
        Stat stat = create2EmptyNode(zkClient, PARENT_PATH);
        assertEquals(stat, zkClient.exists(PARENT_PATH, false), String.format("%s Exists returns correct node stat", serverState));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testSetAndGetData(ServerState serverState) throws Exception {
        setUp(serverState);
        create2EmptyNode(zkClient, PARENT_PATH);
        zkClient.setData(PARENT_PATH, DATA, -1);
        assertArrayEquals(DATA, zkClient.getData(PARENT_PATH, false, null), String.format("%s Node updated with expected value", serverState));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testSetAndGetACL(ServerState serverState) throws Exception {
        setUp(serverState);
        create2EmptyNode(zkClient, PARENT_PATH);
        assertEquals(Ids.OPEN_ACL_UNSAFE, zkClient.getACL(PARENT_PATH, new Stat()), String.format("%s Node has open ACL", serverState));
        zkClient.setACL(PARENT_PATH, Ids.READ_ACL_UNSAFE, -1);
        assertEquals(Ids.READ_ACL_UNSAFE, zkClient.getACL(PARENT_PATH, new Stat()), String.format("%s Node has world read-only ACL", serverState));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testSetAndGetChildren(ServerState serverState) throws Exception {
        setUp(serverState);
        create2EmptyNode(zkClient, PARENT_PATH);
        for (String child : CHILDREN) {
            create2EmptyNode(zkClient, PARENT_PATH + "/" + child);
        }
        assertEquals(CHILDREN, new HashSet<String>(zkClient.getChildren(PARENT_PATH, false)), String.format("%s Parent has expected children", serverState));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testSetAndGetChildren2(ServerState serverState) throws Exception {
        setUp(serverState);
        create2EmptyNode(zkClient, PARENT_PATH);
        for (String child : CHILDREN) {
            create2EmptyNode(zkClient, PARENT_PATH + "/" + child);
        }
        assertEquals(CHILDREN, new HashSet<String>(zkClient.getChildren(PARENT_PATH, false, null)), String.format("%s Parent has expected children", serverState));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testSync(ServerState serverState) throws Exception {
        setUp(serverState);
        complete = false;
        create2EmptyNode(zkClient, PARENT_PATH);
        VoidCallback onSync = new VoidCallback() {
            @Override
            public void processResult(int rc, String path, Object ctx) {
                complete = true;
                callComplete.countDown();
            }
        };
        zkClient.sync(PARENT_PATH, onSync, null);
        callComplete.await(30, TimeUnit.SECONDS);
        assertTrue(complete, String.format("%s Sync completed", serverState));
    }

}
