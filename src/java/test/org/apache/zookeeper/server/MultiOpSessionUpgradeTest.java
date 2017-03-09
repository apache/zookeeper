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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumBase;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class MultiOpSessionUpgradeTest extends QuorumBase {
    protected static final Logger LOG = LoggerFactory.getLogger(MultiOpSessionUpgradeTest.class);

    @Override
    public void setUp() throws Exception {
        localSessionsEnabled = true;
        localSessionsUpgradingEnabled = true;
        super.setUp();
    }

    @Test
    public void ephemeralCreateMultiOpTest() throws KeeperException, InterruptedException, IOException {
        final ZooKeeper zk = createClient();
        waitForClient(zk, ZooKeeper.States.CONNECTED);

        String data = "test";
        String path = "/ephemeralcreatemultiop";
        zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        List<OpResult> multi = null;
        LOG.info("RUNNING MULTI-OP");
        try {
            multi = zk.multi(Arrays.asList(
                    Op.setData(path, data.getBytes(), 0),
                    Op.create(path + "/e", data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL),
                    Op.create(path + "/p", data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.create(path + "/q", data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)
            ));
        } catch (KeeperException.SessionExpiredException e) {
            LOG.info("SESSION EXPIRED", e);
        }
        LOG.info("TESTING RESULTS");
        Assert.assertNotNull(multi);
        Assert.assertEquals(4, multi.size());
        Assert.assertEquals(data, new String(zk.getData(path + "/e", false, null)));
        Assert.assertEquals(data, new String(zk.getData(path + "/p", false, null)));
        Assert.assertEquals(data, new String(zk.getData(path + "/q", false, null)));
    }

    private void waitForClient(ZooKeeper zk, ZooKeeper.States state) throws InterruptedException {
        for (int i = 0; i < ClientBase.CONNECTION_TIMEOUT / 1000; i++) {
            if (state.equals(zk.getState())) {
                return;
            }
            Thread.sleep(1000);
        }
        ClientBase.logAllStackTraces();
        throw new RuntimeException("Waiting too long for state " + state.name());
    }
}
