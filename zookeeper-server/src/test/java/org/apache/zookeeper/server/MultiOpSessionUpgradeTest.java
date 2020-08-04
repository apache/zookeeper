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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.GetDataRequest;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumZooKeeperServer;
import org.apache.zookeeper.server.quorum.UpgradeableSessionTracker;
import org.apache.zookeeper.test.QuorumBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiOpSessionUpgradeTest extends QuorumBase {

    protected static final Logger LOG = LoggerFactory.getLogger(MultiOpSessionUpgradeTest.class);

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        localSessionsEnabled = true;
        localSessionsUpgradingEnabled = true;
        super.setUp();
    }

    @Test
    public void ephemeralCreateMultiOpTest() throws KeeperException, InterruptedException, IOException {
        final ZooKeeper zk = createClient();

        String data = "test";
        String path = "/ephemeralcreatemultiop";
        zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        QuorumZooKeeperServer server = getConnectedServer(zk.getSessionId());
        assertNotNull(server, "unable to find server interlocutor");
        UpgradeableSessionTracker sessionTracker = (UpgradeableSessionTracker) server.getSessionTracker();
        assertFalse(sessionTracker.isGlobalSession(zk.getSessionId()), "session already global");

        List<OpResult> multi = null;
        try {
            multi = zk.multi(Arrays.asList(
                    Op.setData(path, data.getBytes(), 0),
                    Op.create(path + "/e", data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL),
                    Op.create(path + "/p", data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.create(path + "/q", data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)));
        } catch (KeeperException.SessionExpiredException e) {
            // the scenario that inspired this unit test
            fail("received session expired for a session promotion in a multi-op");
        }

        assertNotNull(multi);
        assertEquals(4, multi.size());
        assertEquals(data, new String(zk.getData(path + "/e", false, null)));
        assertEquals(data, new String(zk.getData(path + "/p", false, null)));
        assertEquals(data, new String(zk.getData(path + "/q", false, null)));
        assertTrue(sessionTracker.isGlobalSession(zk.getSessionId()), "session not promoted");
    }

    @Test
    public void directCheckUpgradeSessionTest() throws IOException, InterruptedException, KeeperException {
        final ZooKeeper zk = createClient();

        String path = "/directcheckupgradesession";
        zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        QuorumZooKeeperServer server = getConnectedServer(zk.getSessionId());
        assertNotNull(server, "unable to find server interlocutor");

        Request readRequest = makeGetDataRequest(path, zk.getSessionId());
        Request createRequest = makeCreateRequest(path + "/e", zk.getSessionId());
        assertNull(server.checkUpgradeSession(readRequest), "tried to upgrade on a read");
        assertNotNull(server.checkUpgradeSession(createRequest), "failed to upgrade on a create");
        assertNull(server.checkUpgradeSession(createRequest), "tried to upgrade after successful promotion");
    }

    private Request makeGetDataRequest(String path, long sessionId) throws IOException {
        ByteArrayOutputStream boas = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(boas);
        GetDataRequest getDataRequest = new GetDataRequest(path, false);
        getDataRequest.serialize(boa, "request");
        ByteBuffer bb = ByteBuffer.wrap(boas.toByteArray());
        return new Request(null, sessionId, 1, ZooDefs.OpCode.getData, bb, new ArrayList<Id>());
    }

    private Request makeCreateRequest(String path, long sessionId) throws IOException {
        ByteArrayOutputStream boas = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(boas);
        CreateRequest createRequest = new CreateRequest(path, "data".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL.toFlag());
        createRequest.serialize(boa, "request");
        ByteBuffer bb = ByteBuffer.wrap(boas.toByteArray());
        return new Request(null, sessionId, 1, ZooDefs.OpCode.create2, bb, new ArrayList<Id>());
    }

    private QuorumZooKeeperServer getConnectedServer(long sessionId) {
        for (QuorumPeer peer : getPeerList()) {
            if (peer.getActiveServer().getSessionTracker().isTrackingSession(sessionId)) {
                return (QuorumZooKeeperServer) peer.getActiveServer();
            }
        }
        return null;
    }

}
