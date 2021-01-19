/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import static org.junit.Assert.fail;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.cli.SetQuotaCommand;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An unit case when Enforce Quota disables by default
 */
public class EnforceQuotaTest extends ClientBase {

    private ZooKeeper zk;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        super.setUp();
        zk = createClient();
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        System.clearProperty(ZooKeeperServer.ENFORCE_QUOTA);
        super.tearDown();
        zk.close();
    }

    @Test
    public void testSetQuotaDisableWhenExceedBytesHardQuota() throws Exception {
        final String path = "/c1";
        zk.create(path, "12345".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        StatsTrack st = new StatsTrack();
        st.setByteHardLimit(5L);
        SetQuotaCommand.createQuota(zk, path, st);

        try {
            zk.setData(path, "123456".getBytes(), -1);
        } catch (KeeperException.QuotaExceededException e) {
            fail("should not throw Byte Quota Exceeded Exception when enforce quota disables");
        }
    }

    @Test
    public void testSetQuotaDisableWhenExceedCountHardQuota() throws Exception {

        final String path = "/c1";
        zk.create(path, "data".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        int count = 2;
        StatsTrack st = new StatsTrack();
        st.setCountHardLimit(count);
        SetQuotaCommand.createQuota(zk, path, st);
        zk.create(path + "/c2", "data".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        try {
            zk.create(path + "/c2" + "/c3", "data".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException.QuotaExceededException e) {
            fail("should not throw Count Quota Exceeded Exception when enforce quota disables");
        }
    }
}
