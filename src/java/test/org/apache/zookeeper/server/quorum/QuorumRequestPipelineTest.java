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

package org.apache.zookeeper.server.quorum;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.test.QuorumBase;
import org.apache.zookeeper.ZKParameterized;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(ZKParameterized.RunnerFactory.class)
public class QuorumRequestPipelineTest extends QuorumBase {
    protected ServerState serverState;
    protected final CountDownLatch callComplete = new CountDownLatch(1);
    protected boolean complete = false;
    protected final static String PARENT_PATH = "/foo";
    protected final static HashSet<String> CHILDREN = new HashSet<String>(Arrays.asList("1", "2", "3"));
    protected final static String AUTH_PROVIDER = "digest";
    protected final static byte[] AUTH = "hello".getBytes();
    protected final static byte[] DATA = "Hint Water".getBytes();

    protected TestableZooKeeper zkClient;

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(
            new Object[][] {
                {ServerState.LEADING},
                {ServerState.FOLLOWING},
                {ServerState.OBSERVING}});
    }

    public QuorumRequestPipelineTest(ServerState state) {
        this.serverState = state;
    }

    @Before
    public void setUp() throws Exception {
        CountdownWatcher clientWatch = new CountdownWatcher();
        super.setUp(true);
        zkClient = createClient(clientWatch, getPeersMatching(serverState));
        zkClient.addAuthInfo(AUTH_PROVIDER, AUTH);
        clientWatch.waitForConnected(CONNECTION_TIMEOUT);
    }

    @After
    public void tearDown() throws Exception {
        zkClient.close();
        super.tearDown();
    }

    private Stat create2EmptyNode(TestableZooKeeper zkClient, String path) throws Exception {
        Stat stat = new Stat();
        zkClient.create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
        return stat;
    }

    @Test
    public void testCreate() throws Exception {
        zkClient.create(PARENT_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        Assert.assertArrayEquals(
            String.format("%s Node created (create) with expected value", serverState),
            DATA,
            zkClient.getData(PARENT_PATH, false, null));
    }

    @Test
    public void testCreate2() throws Exception {
        zkClient.create(PARENT_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        Assert.assertArrayEquals(
            String.format("%s Node created (create2) with expected value", serverState),
            DATA,
            zkClient.getData(PARENT_PATH, false, null));
    }

    @Test
    public void testDelete() throws Exception {
        create2EmptyNode(zkClient, PARENT_PATH);
        zkClient.delete(PARENT_PATH, -1);
        Assert.assertNull(
            String.format("%s Node no longer exists", serverState),
            zkClient.exists(PARENT_PATH, false));
    }

    @Test
    public void testExists() throws Exception {
        Stat stat = create2EmptyNode(zkClient, PARENT_PATH);
        Assert.assertEquals(
            String.format("%s Exists returns correct node stat", serverState),
            stat,
            zkClient.exists(PARENT_PATH, false));
    }

    @Test
    public void testSetAndGetData() throws Exception {
        create2EmptyNode(zkClient, PARENT_PATH);
        zkClient.setData(PARENT_PATH, DATA, -1);
        Assert.assertArrayEquals(
            String.format("%s Node updated with expected value", serverState),
            DATA,
            zkClient.getData(PARENT_PATH, false, null));
    }

    @Test
    public void testSetAndGetACL() throws Exception {
        create2EmptyNode(zkClient, PARENT_PATH);
        Assert.assertEquals(
            String.format("%s Node has open ACL", serverState),
            Ids.OPEN_ACL_UNSAFE,
            zkClient.getACL(PARENT_PATH, new Stat()));
        zkClient.setACL(PARENT_PATH, Ids.READ_ACL_UNSAFE, -1);
        Assert.assertEquals(
            String.format("%s Node has world read-only ACL", serverState),
            Ids.READ_ACL_UNSAFE,
            zkClient.getACL(PARENT_PATH, new Stat()));
    }

    @Test
    public void testSetAndGetChildren() throws Exception {
        create2EmptyNode(zkClient, PARENT_PATH);
        for (String child : CHILDREN) {
            create2EmptyNode(zkClient, PARENT_PATH + "/" + child);
        }
        Assert.assertEquals(
            String.format("%s Parent has expected children", serverState),
            CHILDREN,
            new HashSet<String>(zkClient.getChildren(PARENT_PATH, false)));
    }

    @Test
    public void testSetAndGetChildren2() throws Exception {
        create2EmptyNode(zkClient, PARENT_PATH);
        for (String child : CHILDREN) {
            create2EmptyNode(zkClient, PARENT_PATH + "/" + child);
        }
        Assert.assertEquals(
            String.format("%s Parent has expected children", serverState),
            CHILDREN,
            new HashSet<String>(zkClient.getChildren(PARENT_PATH, false, null)));
    }

    @Test
    public void testSync() throws Exception {
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
        Assert.assertTrue(
            String.format("%s Sync completed", serverState),
            complete);
    }
}
