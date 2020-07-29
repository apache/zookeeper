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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertSame;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class NullDataTest extends ClientBase implements StatCallback {

    String snapCount;
    CountDownLatch cn = new CountDownLatch(1);

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        // Change the snapcount to happen more often
        snapCount = System.getProperty("zookeeper.snapCount", "1024");
        System.setProperty("zookeeper.snapCount", "10");
        super.setUp();
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        System.setProperty("zookeeper.snapCount", snapCount);
        super.tearDown();
    }

    @Test
    public void testNullData() throws IOException, InterruptedException, KeeperException {
        String path = "/SIZE";
        ZooKeeper zk = null;
        zk = createClient();
        try {
            zk.create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            // try sync zk exists
            zk.exists(path, false);
            zk.exists(path, false, this, null);
            cn.await(10, TimeUnit.SECONDS);
            assertSame(0L, cn.getCount());
        } finally {
            if (zk != null) {
                zk.close();
            }
        }

    }

    public void processResult(int rc, String path, Object ctx, Stat stat) {
        cn.countDown();
    }

}
