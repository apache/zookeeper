/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;

public class GetAllChildrenNumberTest extends ClientBase {
    private static final String BASE = "/getAllChildrenNumberTest";
    private static final String BASE_EXT = BASE + "EXT";
    private static final int PERSISTENT_CNT = 2;
    private static final int EPHEMERAL_CNT = 3;

    private ZooKeeper zk;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        zk = createClient();
        generatePaths(PERSISTENT_CNT, EPHEMERAL_CNT);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();

        zk.close();
    }

    @Test
    public void testGetAllChildrenNumberSync() throws KeeperException, InterruptedException {
        //a bad case
        try {
            zk.getAllChildrenNumber(null);
            Assert.fail("the path for getAllChildrenNumber must not be null.");
        } catch (IllegalArgumentException e) {
            //expected
        }

        Assert.assertEquals(EPHEMERAL_CNT, zk.getAllChildrenNumber(BASE + "/0"));
        Assert.assertEquals(0, zk.getAllChildrenNumber(BASE + "/0/ephem0"));
        Assert.assertEquals(0, zk.getAllChildrenNumber(BASE_EXT));
        Assert.assertEquals(PERSISTENT_CNT + PERSISTENT_CNT * EPHEMERAL_CNT, zk.getAllChildrenNumber(BASE));
        // 6(EPHEMERAL) + 2(PERSISTENT) + 3("/zookeeper,/zookeeper/quota,/zookeeper/config") + 1(BASE_EXT) + 1(BASE) = 13
        Assert.assertEquals(13, zk.getAllChildrenNumber("/"));
    }

    @Test
    public void testGetAllChildrenNumberAsync() throws IOException, KeeperException, InterruptedException {

        final CountDownLatch doneProcessing = new CountDownLatch(1);

        zk.getAllChildrenNumber("/", new AsyncCallback.AllChildrenNumberCallback() {
            @Override
            public void processResult(int rc, String path, Object ctx, int number) {
                if (path == null) {
                    Assert.fail((String.format("the path of getAllChildrenNumber was null.")));
                }
                Assert.assertEquals(13, number);
                doneProcessing.countDown();
            }
        }, null);
        long waitForCallbackSecs = 2L;
        if (!doneProcessing.await(waitForCallbackSecs, TimeUnit.SECONDS)) {
            Assert.fail(String.format("getAllChildrenNumber didn't callback within %d seconds",
                    waitForCallbackSecs));
        }
    }

    private void generatePaths(int persistantCnt, int ephemeralCnt)
            throws KeeperException, InterruptedException {

        zk.create(BASE, BASE.getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk.create(BASE_EXT, BASE_EXT.getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        for (int p = 0; p < persistantCnt; p++) {
            String base = BASE + "/" + p;
            zk.create(base, base.getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            for (int e = 0; e < ephemeralCnt; e++) {
                String ephem = base + "/ephem" + e;
                zk.create(ephem, ephem.getBytes(), Ids.OPEN_ACL_UNSAFE,
                        CreateMode.EPHEMERAL);
            }
        }
    }
}
